package com.dropp.dropp.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Routes active Cellular AND VoIP (WhatsApp, Telegram, Instagram, Meet, etc.) call audio
 * through Bluetooth Hands-Free Profile (HFP / eSCO) to the paired Windows PC, so PC speakers
 * play the caller's voice and the PC microphone feeds the user's voice back into the call.
 *
 * Important Architectural Rule:
 * - NEVER invoke unprivileged `AudioManager.setCommunicationDevice()` from `com.dropp.dropp` (`uid`),
 *   because Android 14/15 `AudioDeviceBroker` revokes unprivileged communication routes after 6s
 *   (`stopBluetoothSco: updateCommunicationClientActivity`) when the calling UID has no AudioTrack,
 *   and Windows 11 `bthhfenum.sys` rejects non-Telecom SCO with `HCI_ERR_PEER_USER`.
 * - Instead:
 *   1. Cellular SIM calls are routed natively by system `Telecom` (`uid=1000`) once HFP SLC is connected.
 *   2. VoIP calls are routed via `DroppVoipConnectionService` (`CAPABILITY_SELF_MANAGED` Telecom bridge),
 *      where privileged `Telecom` (`uid=1000`) holds `bt_sco` and advertises active HFP call state (`+CLCC`)
 *      to Windows `PhoneLineTransportDevice`.
 */
object CallAudioBridgeService {
    private const val TAG = "CallAudioBridge"

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var shouldRouteToPc = false

    @Volatile
    private var suppressManualScoTriggerUntilMs: Long = 0L

    private var headsetProxy: BluetoothHeadset? = null

    @Volatile
    private var isProxyReady = false

    @Volatile
    private var passiveObserverRegistered = false

    private var scoReceiver: BroadcastReceiver? = null
    private var commDeviceObserver: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var routingJob: Job? = null

    /**
     * Initialise the BluetoothHeadset (HFP) profile proxy so we can query connection state.
     */
    fun initHeadsetProxy(context: Context) {
        if (headsetProxy != null || isProxyReady) return

        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return

            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = proxy as? BluetoothHeadset
                        isProxyReady = true
                        Log.i(TAG, "BluetoothHeadset (HFP) proxy connected.")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = null
                        isProxyReady = false
                        Log.i(TAG, "BluetoothHeadset (HFP) proxy disconnected.")
                    }
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HFP proxy: ${e.message}", e)
        }
    }

    /**
     * Passive observer that detects if the user manually selects the paired Windows PC ("SHASHIKANT")
     * inside a VoIP app's speaker/Bluetooth menu on the phone, and automatically activates
     * `DroppVoipConnectionService` so Windows HFP accepts and holds the eSCO audio channel!
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun registerPassiveObserver(context: Context) {
        if (passiveObserverRegistered) return
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (state) {
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        Log.d(TAG, "Bluetooth SCO audio CONNECTING...")
                        onPotentialManualPcSelection(appContext)
                    }
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO audio CONNECTED — full-duplex call audio active on PC!")
                    }
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO audio DISCONNECTED.")
                    }
                }
            }
        }
        scoReceiver = receiver
        @Suppress("DEPRECATION")
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering passive SCO receiver: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val observer = AudioManager.OnCommunicationDeviceChangedListener { device ->
                Log.i(TAG, "Passive onCommunicationDeviceChanged -> ${device?.productName} (type=${device?.type})")
                if (device != null &&
                    (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                ) {
                    val winBt = AudioRoutingManager.findWindowsDevice(appContext)
                    val matchesWin = winBt == null ||
                        device.address.equals(winBt.address, ignoreCase = true) ||
                        (winBt.name != null && device.productName?.toString()?.contains(winBt.name, ignoreCase = true) == true)
                    if (matchesWin) {
                        onPotentialManualPcSelection(appContext)
                    }
                }
            }
            try {
                audioManager.addOnCommunicationDeviceChangedListener(appContext.mainExecutor, observer)
                commDeviceObserver = observer
            } catch (e: Exception) {
                Log.w(TAG, "Could not add passive OnCommunicationDeviceChangedListener: ${e.message}")
            }
        }

        passiveObserverRegistered = true
    }

    private fun onPotentialManualPcSelection(appContext: Context) {
        if (System.currentTimeMillis() < suppressManualScoTriggerUntilMs) return
        if (CallPassthroughManager.currentCallType == "VOIP" &&
            CallPassthroughManager.currentCallState == "ACTIVE" &&
            !DroppVoipConnectionService.isBridgeActive
        ) {
            Log.i(TAG, "Detected user selecting Windows Bluetooth during active VoIP call -> activating Telecom VoIP HFP bridge!")
            shouldRouteToPc = true
            isRunning = true
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) ensureCallVolumeAudible(am)
            DroppVoipConnectionService.activateVoipBridgeForPc(
                appContext,
                CallPassthroughManager.currentCallerName,
                CallPassthroughManager.currentPhoneNumber
            )
            CallPassthroughManager.notifyAudioRouteChanged(appContext, true)
        }
    }

    /**
     * Request both Windows (PhoneLineTransportDevice.ConnectAsync) and Android (BluetoothDevice.connect)
     * to bring up the Bluetooth HFP Hands-Free Service Level Connection without triggering MODIFY_PHONE_STATE errors.
     */
    @SuppressLint("MissingPermission")
    fun connectHfp(context: Context): Boolean {
        initHeadsetProxy(context)
        val device = AudioRoutingManager.findWindowsDevice(context)
        if (device == null) {
            Log.w(TAG, "Cannot connect HFP: No paired Windows PC found!")
            return false
        }

        val proxy = headsetProxy
        if (proxy != null) {
            try {
                val state = proxy.getConnectionState(device)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "BluetoothHeadset (HFP) already CONNECTED to ${device.name} (${device.address})")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking HFP connection state: ${e.message}")
            }
        }

        // 1. Ask Windows BluetoothHandsFreeService to initiate PhoneLineTransportDevice.ConnectAsync()
        try {
            val req = JSONObject().apply {
                put("type", "HFP_CONNECT_REQ")
                put("Type", "HFP_CONNECT_REQ")
            }
            CommandTransferManager.sendCommand(context, req.toString())
            Log.d(TAG, "Sent HFP_CONNECT_REQ to Windows for ${device.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send HFP_CONNECT_REQ: ${e.message}")
        }

        // 2. Also invoke BluetoothDevice.connect() on Android 12+ (allowed with BLUETOOTH_CONNECT permission)
        try {
            val devConnect = android.bluetooth.BluetoothDevice::class.java.getDeclaredMethod("connect")
            devConnect.isAccessible = true
            val res = devConnect.invoke(device)
            Log.i(TAG, "BluetoothDevice.connect() invoked for ${device.name}, result: $res")
            if (res == true || (res is Int && res == 0)) return true
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothDevice.connect() notice: ${e.message}")
        }

        return false
    }

    /**
     * Ensure Bluetooth SCO call volume is audible (at least 75% of max volume) so caller voice isn't muted on PC.
     */
    fun ensureCallVolumeAudible(audioManager: AudioManager) {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val minTarget = (maxVol * 0.75).toInt().coerceAtLeast(1)
            if (curVol < minTarget) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, minTarget, 0)
                Log.i(TAG, "Boosted STREAM_VOICE_CALL volume from $curVol to $minTarget (max=$maxVol) for clear PC call audio.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust STREAM_VOICE_CALL volume: ${e.message}")
        }
    }

    /**
     * Start routing call audio to the Bluetooth HFP/eSCO channel (Windows PC).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startAudioBridge(context: Context, @Suppress("UNUSED_PARAMETER") targetIp: String = "") {
        shouldRouteToPc = true
        isRunning = true
        val appContext = context.applicationContext
        registerPassiveObserver(appContext)
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        routingJob?.cancel()
        routingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val callType = CallPassthroughManager.currentCallType
                Log.i(TAG, "Activating Bluetooth HFP call audio routing to Windows PC (callType=$callType)...")
                val alreadyConnected = connectHfp(appContext)
                ensureCallVolumeAudible(audioManager)

                if (callType == "VOIP") {
                    DroppVoipConnectionService.activateVoipBridgeForPc(
                        appContext,
                        CallPassthroughManager.currentCallerName,
                        CallPassthroughManager.currentPhoneNumber
                    )

                    if (!alreadyConnected) {
                        for (attempt in 1..8) {
                            if (!shouldRouteToPc) return@launch
                            delay(500)
                            if (isHfpConnected()) {
                                DroppVoipConnectionService.routeToPc(appContext)
                                break
                            }
                            if (attempt == 3 || attempt == 6) {
                                connectHfp(appContext)
                            }
                        }
                    } else {
                        delay(350)
                        if (shouldRouteToPc) {
                            DroppVoipConnectionService.routeToPc(appContext)
                        }
                    }
                } else {
                    // CELLULAR SIM call: system Telecom (uid=1000) automatically routes SIM calls
                    // to SHASHIKANT once HFP SLC is connected. Retry HFP connection if needed.
                    if (!alreadyConnected) {
                        for (attempt in 1..6) {
                            if (!shouldRouteToPc) return@launch
                            delay(600)
                            if (isHfpConnected()) break
                            if (attempt == 3) connectHfp(appContext)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startAudioBridge coroutine: ${e.message}", e)
            }
        }
    }

    /**
     * Stop routing call audio via Bluetooth HFP (returns call audio to phone earpiece/speaker).
     */
    @Synchronized
    fun stopAudioBridge() {
        suppressManualScoTriggerUntilMs = System.currentTimeMillis() + 1800L
        shouldRouteToPc = false
        routingJob?.cancel()
        routingJob = null

        val wasRunning = isRunning
        isRunning = false

        DroppVoipConnectionService.endVoipBridge()

        if (wasRunning) {
            Log.i(TAG, "Stopped Call Audio Bridge (call audio returned to phone).")
        }
    }

    @SuppressLint("MissingPermission")
    fun isHfpConnected(): Boolean {
        val proxy = headsetProxy ?: return false
        return try {
            proxy.connectedDevices?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }
}
