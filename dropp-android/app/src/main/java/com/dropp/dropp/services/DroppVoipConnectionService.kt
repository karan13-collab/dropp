package com.dropp.dropp.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Self-managed Android Telecom ConnectionService that bridges third-party VoIP calls
 * (WhatsApp, WhatsApp Business, Telegram, Instagram, Messenger, Signal, Discord, Meet, etc.)
 * into Android's Telecom + BluetoothInCallService stack.
 *
 * Why this is required for Windows 11 Bluetooth HFP Hands-Free:
 * - Windows 11's HFP driver (`bthhfenum.sys` / `PhoneSvc`) immediately terminates raw
 *   `AudioManager.startBluetoothSco()` / virtual voice call eSCO links after ~17ms with
 *   `HCI_ERR_PEER_USER` because `AT+CLCC` reports zero active Telecom calls (`numActive=0`).
 * - Additionally, on Android 14/15 (`AudioDeviceBroker`), unprivileged `setCommunicationDevice()`
 *   from a companion app without an active `AudioTrack` gets revoked after 6 seconds
 *   (`stopBluetoothSco: updateCommunicationClientActivity`).
 * - By registering a `CAPABILITY_SELF_MANAGED` Telecom `Connection` with `audioModeIsVoip = true`
 *   (keeping DSP in `MODE_IN_COMMUNICATION` and opening zero AudioRecord streams so the VoIP app
 *   retains exclusive microphone & playback ownership), Android's `BluetoothInCallService`
 *   advertises a genuine active HFP call (`+CIEV: call=1` + `+CLCC: status=0`) to Windows and
 *   privileged `Telecom` (`uid=1000`) holds the `bt_sco` communication route open for the entire call.
 */
class DroppVoipConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "DroppVoipBridge"
        private const val ACCOUNT_ID = "dropp_voip_hfp_bridge"

        @Volatile
        var activeConnection: DroppVoipConnection? = null
            private set

        @Volatile
        private var lastPlaceCallMs: Long = 0L

        @Volatile
        private var lastEndCallMs: Long = 0L

        @Volatile
        private var suppressRemoteEndCallback = false

        @Volatile
        private var handoffProtectedUntilMs: Long = 0L

        val isBridgeActive: Boolean
            get() = activeConnection != null

        /**
         * True while our self-managed VoIP bridge is being placed, active, or just torn down,
         * allowing CallPassthroughManager to ignore TelephonyCallback OFFHOOK/IDLE echoes on Android 12+.
         */
        val isSelfManagedBridgeActiveOrTransitioning: Boolean
            get() {
                val now = System.currentTimeMillis()
                return activeConnection != null ||
                    (now - lastPlaceCallMs < 4000L) ||
                    (now - lastEndCallMs < 2000L)
            }

        fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
            return PhoneAccountHandle(
                ComponentName(context.applicationContext, DroppVoipConnectionService::class.java),
                ACCOUNT_ID
            )
        }

        fun ensurePhoneAccountRegistered(context: Context): PhoneAccountHandle? {
            return try {
                val appCtx = context.applicationContext
                val tm = appCtx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
                val handle = getPhoneAccountHandle(appCtx)
                val account = PhoneAccount.builder(handle, "Dropp PC Call Bridge")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .build()
                tm.registerPhoneAccount(account)
                handle
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register self-managed PhoneAccount: ${e.message}", e)
                null
            }
        }

        private fun buildValidTelUri(rawPhoneOrSub: String): Uri {
            val digits = rawPhoneOrSub.filter { it.isDigit() || it == '+' }
            val normalized = when {
                digits.length >= 7 && digits.startsWith("+") -> digits
                digits.length >= 7 -> "+$digits"
                else -> "+10000000000"
            }
            return Uri.fromParts(PhoneAccount.SCHEME_TEL, normalized, null)
        }

        @SuppressLint("MissingPermission")
        fun activateVoipBridgeForPc(context: Context, callerName: String, phoneNumber: String) {
            val appCtx = context.applicationContext
            val now = System.currentTimeMillis()
            lastPlaceCallMs = now
            handoffProtectedUntilMs = now + 5500L

            val existing = activeConnection
            if (existing != null) {
                existing.updateCallerMetadata(callerName, phoneNumber)
                existing.activateAndRouteToPc(appCtx)
                return
            }

            val handle = ensurePhoneAccountRegistered(appCtx) ?: return
            val tm = appCtx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return

            try {
                val uri = buildValidTelUri(phoneNumber)
                val callExtras = Bundle().apply {
                    putString("dropp_caller_name", callerName)
                    putString("dropp_phone_number", phoneNumber)
                    putBoolean("dropp_auto_activate", true)
                }
                val placeExtras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)
                }
                Log.i(TAG, "Placing active self-managed VoIP bridge call in Telecom for '$callerName' ($uri)")
                tm.placeCall(uri, placeExtras)
            } catch (e: Exception) {
                Log.e(TAG, "placeCall failed, trying addNewIncomingCall fallback: ${e.message}")
                try {
                    val uri = buildValidTelUri(phoneNumber)
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, uri)
                        putString("dropp_caller_name", callerName)
                        putString("dropp_phone_number", phoneNumber)
                        putBoolean("dropp_auto_activate", true)
                    }
                    tm.addNewIncomingCall(handle, extras)
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback addNewIncomingCall also failed: ${e2.message}", e2)
                }
            }
        }

        fun routeToPc(context: Context) {
            handoffProtectedUntilMs = System.currentTimeMillis() + 4500L
            activeConnection?.routeToWindowsBluetooth(context.applicationContext)
        }

        fun endVoipBridge() {
            val conn = activeConnection ?: return
            lastEndCallMs = System.currentTimeMillis()
            activeConnection = null
            suppressRemoteEndCallback = true
            try {
                Log.i(TAG, "Ending Telecom self-managed VoIP bridge connection.")
                conn.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                conn.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying VoIP bridge connection: ${e.message}")
            } finally {
                suppressRemoteEndCallback = false
            }
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callerName = extras?.getString("dropp_caller_name").orEmpty()
        val phoneNumber = extras?.getString("dropp_phone_number").orEmpty()
        val autoActivate = extras?.getBoolean("dropp_auto_activate", false) == true

        Log.i(TAG, "onCreateIncomingConnection: caller='$callerName', autoActivate=$autoActivate")
        val conn = DroppVoipConnection(applicationContext, callerName, phoneNumber)
        activeConnection = conn

        if (autoActivate || CallPassthroughManager.currentCallState == "ACTIVE") {
            conn.activateAndRouteToPc(applicationContext)
        } else {
            conn.setRinging()
        }
        return conn
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.w(TAG, "onCreateIncomingConnectionFailed: $request")
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callerName = extras?.getString("dropp_caller_name").orEmpty()
        val phoneNumber = extras?.getString("dropp_phone_number").orEmpty()

        Log.i(TAG, "onCreateOutgoingConnection: caller='$callerName'")
        val conn = DroppVoipConnection(applicationContext, callerName, phoneNumber)
        activeConnection = conn
        conn.activateAndRouteToPc(applicationContext)
        return conn
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.w(TAG, "onCreateOutgoingConnectionFailed: $request")
    }

    class DroppVoipConnection(
        private val appContext: Context,
        callerName: String,
        phoneNumber: String
    ) : Connection() {

        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            audioModeIsVoip = true
            connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
            updateCallerMetadata(callerName, phoneNumber)
        }

        fun updateCallerMetadata(callerName: String, phoneNumber: String) {
            val uri = buildValidTelUri(phoneNumber)
            setAddress(uri, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(callerName.ifBlank { "VoIP Call" }, TelecomManager.PRESENTATION_ALLOWED)
        }

        fun activateAndRouteToPc(context: Context) {
            if (state != STATE_ACTIVE) {
                setActive()
                Log.i(TAG, "DroppVoipConnection state -> STATE_ACTIVE")
            }
            if (CallAudioBridgeService.shouldRouteToPc) {
                CoroutineScope(Dispatchers.IO).launch {
                    // Allow ~280ms for BluetoothInCallService to deliver +CIEV: call=1 (CLCC status=0)
                    // to Windows 11 HFP before opening eSCO so Windows accepts SCO on the first attempt!
                    delay(280)
                    if (activeConnection === this@DroppVoipConnection && CallAudioBridgeService.shouldRouteToPc) {
                        routeToWindowsBluetooth(context)
                    }
                    delay(900)
                    if (activeConnection === this@DroppVoipConnection && CallAudioBridgeService.shouldRouteToPc) {
                        routeToWindowsBluetooth(context)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun routeToWindowsBluetooth(context: Context) {
            try {
                val winDevice: BluetoothDevice? = AudioRoutingManager.findWindowsDevice(context)
                val audioState = callAudioState
                if (audioState != null && audioState.route == CallAudioState.ROUTE_BLUETOOTH) {
                    val activeBt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioState.activeBluetoothDevice else null
                    if (winDevice != null && activeBt?.address.equals(winDevice.address, ignoreCase = true)) {
                        Log.i(TAG, "Telecom CallAudioState already on ROUTE_BLUETOOTH (${activeBt?.name})")
                        return
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && winDevice != null) {
                    Log.i(TAG, "Requesting Telecom Bluetooth audio route to Windows PC: ${winDevice.name} (${winDevice.address})")
                    requestBluetoothAudio(winDevice)
                } else {
                    Log.i(TAG, "Setting Telecom CallAudioState.ROUTE_BLUETOOTH")
                    setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                }
            } catch (e: Exception) {
                Log.w(TAG, "routeToWindowsBluetooth warning: ${e.message}")
            }
        }

        override fun onShowIncomingCallUi() {
            Log.d(TAG, "onShowIncomingCallUi (UI handled natively by VoIP app & Windows CallOverlayWindow)")
        }

        @SuppressLint("MissingPermission")
        override fun onCallAudioStateChanged(state: CallAudioState?) {
            if (state == null) return
            val activeBt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) state.activeBluetoothDevice?.name else null
            Log.i(
                TAG,
                "onCallAudioStateChanged: route=${state.route}, mask=${state.supportedRouteMask}, activeBt=$activeBt"
            )
            if (CallAudioBridgeService.shouldRouteToPc &&
                this.state == STATE_ACTIVE &&
                state.route != CallAudioState.ROUTE_BLUETOOTH &&
                (state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
            ) {
                if (System.currentTimeMillis() < handoffProtectedUntilMs) {
                    Log.i(TAG, "Route bounced during initial handoff window; re-asserting Windows Bluetooth route in 220ms.")
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(220)
                        if (activeConnection === this@DroppVoipConnection && CallAudioBridgeService.shouldRouteToPc) {
                            routeToWindowsBluetooth(appContext)
                        }
                    }
                } else {
                    Log.i(TAG, "User switched VoIP audio route on phone to route=${state.route}; releasing PC audio bridge.")
                    CallAudioBridgeService.shouldRouteToPc = false
                    endVoipBridge()
                    CallPassthroughManager.notifyAudioRouteChanged(appContext, false)
                }
            }
        }

        override fun onAnswer() {
            Log.i(TAG, "onAnswer() invoked via Telecom/Bluetooth HFP!")
            activateAndRouteToPc(appContext)
            if (CallPassthroughManager.currentCallState == "RINGING") {
                CallPassthroughManager.answerCall(appContext)
            }
        }

        override fun onAnswer(videoState: Int) {
            onAnswer()
        }

        override fun onReject() {
            Log.i(TAG, "onReject() invoked via Telecom/Bluetooth HFP!")
            handleRemoteDisconnect(DisconnectCause.REJECTED)
        }

        override fun onDisconnect() {
            Log.i(TAG, "onDisconnect() invoked via Telecom/Bluetooth HFP!")
            handleRemoteDisconnect(DisconnectCause.REMOTE)
        }

        private fun handleRemoteDisconnect(causeCode: Int) {
            lastEndCallMs = System.currentTimeMillis()
            if (activeConnection === this) {
                activeConnection = null
            }
            setDisconnected(DisconnectCause(causeCode))
            destroy()
            if (!suppressRemoteEndCallback &&
                (CallPassthroughManager.currentCallState == "RINGING" || CallPassthroughManager.currentCallState == "ACTIVE")
            ) {
                CallPassthroughManager.rejectOrEndCall(appContext)
            }
        }
    }
}
