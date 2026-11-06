package com.dropp.dropp.services

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages incoming, active, and ended call detection, signaling, and Answer/Decline/HangUp execution
 * across BOTH Cellular SIM calls AND VoIP calls (WhatsApp, WhatsApp Business, Telegram, Instagram,
 * Messenger, Signal, Google Meet, Discord, etc.).
 */
object CallPassthroughManager {
    private const val TAG = "CallPassthrough"

    @Volatile
    var currentCallState: String = "IDLE"
        private set

    @Volatile
    var currentPhoneNumber: String = ""
        private set

    @Volatile
    var currentCallerName: String = ""
        private set

    @Volatile
    var currentAppName: String = ""
        private set

    @Volatile
    var currentCallType: String = "CELLULAR" // "CELLULAR" or "VOIP"
        private set

    @Volatile
    private var answeredFromPc: Boolean = false

    @Volatile
    private var realSimCallActive: Boolean = false

    @Volatile
    private var activeCallPackage: String = ""

    @Volatile
    private var activeCallNotificationKey: String = ""

    private var telephonyCallback: Any? = null
    private var audioModeListener: AudioManager.OnModeChangedListener? = null
    private var isListening = false
    private var lastDispatchedSignature: String = ""
    private var voipEndCheckJob: Job? = null

    // Separate cached PendingIntents for Answer, Decline (incoming), and HangUp (ongoing)
    @Volatile
    var cachedAnswerIntent: PendingIntent? = null

    @Volatile
    var cachedDeclineIntent: PendingIntent? = null

    @Volatile
    var cachedHangUpIntent: PendingIntent? = null

    fun start(context: Context) {
        if (isListening) return
        val appContext = context.applicationContext

        // Initialize Bluetooth HFP proxy, passive SCO observer, and self-managed Telecom VoIP PhoneAccount early
        CallAudioBridgeService.initHeadsetProxy(appContext)
        CallAudioBridgeService.registerPassiveObserver(appContext)
        DroppVoipConnectionService.ensurePhoneAccountRegistered(appContext)

        // 1. Register TelephonyCallback for SIM Cellular Calls
        val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            onTelephonyStateChanged(appContext, state, null)
                        }
                    }
                    telephonyManager.registerTelephonyCallback(appContext.mainExecutor, callback)
                    telephonyCallback = callback
                    Log.d(TAG, "Registered TelephonyCallback for cellular call state detection.")
                } else {
                    @Suppress("DEPRECATION")
                    val listener = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            onTelephonyStateChanged(appContext, state, phoneNumber)
                        }
                    }
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    telephonyCallback = listener
                    Log.d(TAG, "Registered PhoneStateListener for cellular call state detection.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register telephony listener: ${e.message}", e)
            }
        }

        // 2. Register AudioManager.OnModeChangedListener (API 31+) to catch real-time VoIP (WhatsApp/Telegram/etc.) transitions!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val modeListener = AudioManager.OnModeChangedListener { mode ->
                    onAudioModeChanged(appContext, mode)
                }
                try {
                    audioManager.addOnModeChangedListener(appContext.mainExecutor, modeListener)
                    audioModeListener = modeListener
                    Log.d(TAG, "Registered AudioManager.OnModeChangedListener for VoIP & Cellular call mode tracking.")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register OnModeChangedListener: ${e.message}")
                }
            }
        }

        isListening = true
    }

    fun stop(context: Context) {
        if (!isListening) return
        val appContext = context.applicationContext
        val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyCallback?.let { cb ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
                    telephonyManager?.unregisterTelephonyCallback(cb)
                } else if (cb is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    telephonyManager?.listen(cb, PhoneStateListener.LISTEN_NONE)
                }
            } catch (_: Exception) {}
        }
        telephonyCallback = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioModeListener?.let { listener ->
                try { audioManager?.removeOnModeChangedListener(listener) } catch (_: Exception) {}
            }
            audioModeListener = null
        }

        isListening = false
    }

    /**
     * Returns true ONLY if an actual SIM/managed cellular call is active (returns false for self-managed VoIP calls!).
     */
    private fun isInManagedSimCall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return realSimCallActive
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.isInManagedCall == true
            } else {
                realSimCallActive
            }
        } catch (_: Exception) {
            realSimCallActive
        }
    }

    private fun isBluetoothScoCurrentlySelected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return CallAudioBridgeService.shouldRouteToPc
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val t = am?.communicationDevice?.type
            t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET
        } catch (_: Exception) {
            CallAudioBridgeService.shouldRouteToPc
        }
    }

    private fun onAudioModeChanged(context: Context, mode: Int) {
        Log.d(TAG, "AudioManager mode changed -> $mode (currentCallState=$currentCallState, type=$currentCallType)")
        when (mode) {
            AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_IN_CALL -> {
                voipEndCheckJob?.cancel()
                if (currentCallState == "RINGING") {
                    Log.i(TAG, "Audio mode entered call/communication while RINGING -> transitioning to ACTIVE (answeredFromPc=$answeredFromPc)!")
                    currentCallState = "ACTIVE"
                    refreshCallNotificationIntents()
                    if (answeredFromPc || CallAudioBridgeService.shouldRouteToPc) {
                        startAudioBridge(context)
                    }
                    dispatchCallEvent(context, "ACTIVE")
                } else if (currentCallState == "ACTIVE" && CallAudioBridgeService.shouldRouteToPc && !DroppVoipConnectionService.isBridgeActive) {
                    CallAudioBridgeService.startAudioBridge(context)
                }
            }
            AudioManager.MODE_NORMAL -> {
                if (currentCallType == "VOIP" && (currentCallState == "ACTIVE" || currentCallState == "RINGING")) {
                    voipEndCheckJob?.cancel()
                    voipEndCheckJob = CoroutineScope(Dispatchers.IO).launch {
                        // Brief debounce because some VoIP apps flicker MODE_RINGTONE -> MODE_NORMAL -> MODE_IN_COMMUNICATION when answering
                        delay(900)
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val hasActiveNotif = hasActiveCallNotificationForPackage(activeCallPackage)
                        if (!hasActiveNotif && !isInManagedSimCall(context) &&
                            (am?.mode == AudioManager.MODE_NORMAL || !DroppVoipConnectionService.isBridgeActive)
                        ) {
                            Log.i(TAG, "VoIP call ended (call notification gone and audio returned to normal).")
                            markCallEnded(context, forceDispatch = true)
                        }
                    }
                }
            }
        }
    }

    private fun onTelephonyStateChanged(context: Context, state: Int, incomingNumber: String?) {
        val managedSim = isInManagedSimCall(context)
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(TAG, "Cellular Telephony state changed: $state -> RINGING (number: $incomingNumber, managedSim=$managedSim)")
                voipEndCheckJob?.cancel()
                realSimCallActive = true
                if (currentCallState != "RINGING") {
                    answeredFromPc = false
                    CallAudioBridgeService.shouldRouteToPc = false
                }
                currentCallType = "CELLULAR"
                if (currentAppName.isBlank()) currentAppName = "Phone"
                currentCallState = "RINGING"
                updateCallerFromNumber(context, incomingNumber)
                CallAudioBridgeService.connectHfp(context)
                dispatchCallEvent(context, "RINGING")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // CRITICAL: On Android 12+ (especially Vivo Android 15), TelephonyCallback fires CALL_STATE_OFFHOOK
                // for self-managed ConnectionServices (like DroppVoipConnectionService), where isInManagedCall == false!
                if (!managedSim && (DroppVoipConnectionService.isSelfManagedBridgeActiveOrTransitioning || currentCallType == "VOIP")) {
                    Log.i(TAG, "Ignoring Telephony OFFHOOK from self-managed VoIP bridge (isInManagedCall=$managedSim, type=$currentCallType)")
                    return
                }

                Log.i(TAG, "Cellular Telephony state changed: $state -> ACTIVE (number: $incomingNumber, managedSim=$managedSim)")
                voipEndCheckJob?.cancel()
                realSimCallActive = true
                currentCallType = "CELLULAR"
                if (currentAppName.isBlank()) currentAppName = "Phone"
                currentCallState = "ACTIVE"
                updateCallerFromNumber(context, incomingNumber)
                refreshCallNotificationIntents()
                if (answeredFromPc || CallAudioBridgeService.shouldRouteToPc) {
                    startAudioBridge(context)
                }
                dispatchCallEvent(context, "ACTIVE")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val wasRealSim = realSimCallActive
                realSimCallActive = false

                if (!wasRealSim && (DroppVoipConnectionService.isSelfManagedBridgeActiveOrTransitioning || currentCallType == "VOIP" || currentCallState == "IDLE")) {
                    Log.d(TAG, "Ignoring Telephony IDLE from self-managed VoIP bridge / idle state (type=$currentCallType, state=$currentCallState)")
                    return
                }

                if (currentCallType == "CELLULAR" && (currentCallState == "ACTIVE" || currentCallState == "RINGING")) {
                    Log.i(TAG, "Cellular Telephony state changed: $state -> ENDED (dispatching ENDED to Windows)")
                    markCallEnded(context, forceDispatch = true)
                } else if (currentCallState != "IDLE" && !hasActiveCallNotificationForPackage(activeCallPackage)) {
                    Log.i(TAG, "Telephony IDLE with no active call notifications -> marking call ENDED")
                    markCallEnded(context, forceDispatch = true)
                }
            }
        }
    }

    private fun updateCallerFromNumber(context: Context, incomingNumber: String?) {
        if (!incomingNumber.isNullOrBlank()) {
            currentPhoneNumber = incomingNumber
            val resolved = resolveContactName(context, incomingNumber)
            if (!resolved.isNullOrBlank()) {
                currentCallerName = resolved
            } else if (currentCallerName.isBlank()) {
                currentCallerName = incomingNumber
            }
        }
    }

    private fun <T> getParcelableCompat(bundle: Bundle, key: String, clazz: Class<T>): T? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bundle.getParcelable(key, clazz)
            } else {
                @Suppress("DEPRECATION")
                val obj = bundle.getParcelable<android.os.Parcelable>(key)
                if (clazz.isInstance(obj)) clazz.cast(obj) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSystemDialerPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("dialer") ||
               p.contains("telecom") ||
               p.contains("incallui") ||
               p == "com.android.phone" ||
               p == "com.vivo.phone" ||
               p == "com.samsung.android.incallui"
    }

    private fun resolveFriendlyAppName(context: Context, pkg: String): String {
        if (isSystemDialerPackage(pkg)) return "Phone"
        val p = pkg.lowercase()
        if (p == "com.whatsapp") return "WhatsApp"
        if (p == "com.whatsapp.w4b") return "WhatsApp Business"
        if (p.contains("telegram")) return "Telegram"
        if (p.contains("instagram")) return "Instagram"
        if (p.contains("orca") || p.contains("facebook.mlite")) return "Messenger"
        if (p.contains("discord")) return "Discord"
        if (p.contains("tachyon") || p.contains("apps.meetings")) return "Google Meet"
        if (p.contains("thoughtcrime.securesms")) return "Signal"

        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /**
     * Called by NotificationSyncService whenever a cellular or VoIP call notification is posted or updated.
     */
    fun onCallNotification(context: Context, sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val pkg = sbn.packageName

        // Ignore missed call notifications
        if (notif.category == Notification.CATEGORY_MISSED_CALL) return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val callTypeExtra = extras.getInt("android.callType", -1) // 1 = INCOMING, 2 = ONGOING, 3 = SCREENING

        val lowerCombined = "$rawTitle $rawText $subText".lowercase()
        if (!sbn.isOngoing && callTypeExtra <= 0 &&
            (lowerCombined.contains("missed voice call") ||
             lowerCombined.contains("missed video call") ||
             lowerCombined.contains("missed call") ||
             lowerCombined.contains("unanswered"))) {
            return
        }

        // Extract Android 12+ Notification.CallStyle PendingIntents
        var answerPi = getParcelableCompat(extras, "android.answerIntent", PendingIntent::class.java)
        var declinePi = getParcelableCompat(extras, "android.declineIntent", PendingIntent::class.java)
        var hangUpPi = getParcelableCompat(extras, "android.hangUpIntent", PendingIntent::class.java)

        // Extract caller Person from CallStyle if present
        var personName = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val callPerson = getParcelableCompat(extras, "android.callPerson", Person::class.java)
            personName = callPerson?.name?.toString()?.trim() ?: ""
        }

        // Also inspect standard notification actions for Answer / Decline / HangUp
        val actions = notif.actions
        actions?.forEach { action ->
            val t = action.title?.toString()?.trim()?.lowercase() ?: ""
            val pi = action.actionIntent ?: return@forEach
            when {
                t.contains("answer") || t.contains("accept") || t.contains("join") || t.contains("pick up") -> {
                    if (answerPi == null) answerPi = pi
                }
                t.contains("hang up") || t.contains("hangup") || t.contains("end") || t.contains("leave") || t.contains("disconnect") -> {
                    if (hangUpPi == null) hangUpPi = pi
                }
                t.contains("decline") || t.contains("reject") || t.contains("ignore") || t.contains("dismiss") -> {
                    if (declinePi == null) declinePi = pi
                }
            }
        }

        // Smart positional fallback if incoming CallStyle/CATEGORY_CALL has 2 icon/localized buttons (e.g. [Decline, Answer])
        if ((callTypeExtra == 1 || notif.category == Notification.CATEGORY_CALL) &&
            answerPi == null && hangUpPi == null && actions != null && actions.size == 2) {
            val a0 = actions[0].actionIntent
            val a1 = actions[1].actionIntent
            if (declinePi == a0 && a1 != null) {
                answerPi = a1
            } else if (declinePi == a1 && a0 != null) {
                answerPi = a0
            } else if (declinePi == null && a0 != null && a1 != null && lowerCombined.contains("incoming")) {
                declinePi = a0
                answerPi = a1
            }
        }

        // Update cached intents
        if (answerPi != null) cachedAnswerIntent = answerPi
        if (declinePi != null) cachedDeclineIntent = declinePi
        if (hangUpPi != null) cachedHangUpIntent = hangUpPi

        val isCellularPkg = isSystemDialerPackage(pkg)
        currentCallType = if (isCellularPkg) "CELLULAR" else "VOIP"
        currentAppName = resolveFriendlyAppName(context, pkg)
        activeCallPackage = pkg
        activeCallNotificationKey = sbn.key

        // Resolve Caller Name & Subtitle cleanly
        val candidateName = personName.ifBlank { rawTitle }
        if (candidateName.isNotBlank() && !candidateName.equals(currentAppName, ignoreCase = true)) {
            currentCallerName = candidateName
        } else if (currentCallerName.isBlank() && candidateName.isNotBlank()) {
            currentCallerName = candidateName
        }

        if (rawText.isNotBlank()) {
            currentPhoneNumber = rawText
        }

        val actionSummary = actions?.joinToString { "'${it.title}'" } ?: "none"
        Log.i(
            TAG,
            "onCallNotification: pkg=$pkg, app=$currentAppName, type=$currentCallType, callTypeExtra=$callTypeExtra, " +
            "caller='$currentCallerName', sub='$currentPhoneNumber', hasAnswer=${cachedAnswerIntent != null}, " +
            "hasDecline=${cachedDeclineIntent != null}, hasHangUp=${cachedHangUpIntent != null}, actions=[$actionSummary]"
        )

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isAudioInCall = am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION

        val isOngoingActiveCall =
            callTypeExtra == 2 ||
            (isCellularPkg && realSimCallActive && currentCallState == "ACTIVE") ||
            (answerPi == null && hangUpPi != null) ||
            (answerPi == null && (lowerCombined.contains("ongoing") || lowerCombined.contains("in call") || isAudioInCall))

        val isIncomingRingingCall =
            callTypeExtra == 1 ||
            callTypeExtra == 3 ||
            (isCellularPkg && realSimCallActive && currentCallState == "RINGING") ||
            answerPi != null ||
            lowerCombined.contains("incoming") ||
            lowerCombined.contains("ringing")

        if (isOngoingActiveCall) {
            val wasNotActive = currentCallState != "ACTIVE"
            currentCallState = "ACTIVE"
            if (wasNotActive && (answeredFromPc || CallAudioBridgeService.shouldRouteToPc)) {
                startAudioBridge(context)
            }
            dispatchCallEvent(context, "ACTIVE")
        } else if (isIncomingRingingCall && currentCallState != "ACTIVE") {
            if (currentCallState != "RINGING") {
                answeredFromPc = false
                CallAudioBridgeService.shouldRouteToPc = false
            }
            currentCallState = "RINGING"
            CallAudioBridgeService.connectHfp(context)
            dispatchCallEvent(context, "RINGING")
        } else if (currentCallState == "ACTIVE" || currentCallState == "RINGING") {
            // Refresh caller metadata on Windows if updated mid-call
            dispatchCallEvent(context, currentCallState)
        }
    }

    fun onCallNotificationRemoved(context: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val isTrackedCall = sbn.key == activeCallNotificationKey ||
                (pkg == activeCallPackage && activeCallPackage.isNotEmpty()) ||
                sbn.notification?.category == Notification.CATEGORY_CALL

        if (!isTrackedCall) return
        if (currentCallState != "RINGING" && currentCallState != "ACTIVE") return

        // Debounce briefly in case VoIP app swaps incoming -> ongoing notification IDs or TelephonyManager settles
        voipEndCheckJob?.cancel()
        voipEndCheckJob = CoroutineScope(Dispatchers.IO).launch {
            delay(550)
            if (currentCallState != "RINGING" && currentCallState != "ACTIVE") return@launch

            if (currentCallType == "CELLULAR" && (realSimCallActive || isInManagedSimCall(context))) {
                // Real Cellular SIM call is still active/ringing
                return@launch
            }

            if (hasActiveCallNotificationForPackage(pkg)) {
                // Another ongoing call notification from the same app is now active; refresh from it!
                NotificationSyncService.instance?.activeNotifications
                    ?.firstOrNull { it.packageName == pkg && isLikelyCallSbn(it) }
                    ?.let { onCallNotification(context, it) }
                return@launch
            }

            Log.i(TAG, "Call notification removed ($pkg) and no replacement call notification -> dispatching ENDED")
            markCallEnded(context, forceDispatch = true)
        }
    }

    private fun isLikelyCallSbn(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val ex = n.extras ?: return false
        return n.category == Notification.CATEGORY_CALL ||
               ex.getInt("android.callType", -1) > 0 ||
               ex.containsKey("android.hangUpIntent") ||
               ex.containsKey("android.answerIntent")
    }

    private fun hasActiveCallNotificationForPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            NotificationSyncService.instance?.activeNotifications?.any {
                it.packageName == pkg && isLikelyCallSbn(it)
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshCallNotificationIntents() {
        try {
            val activeList = NotificationSyncService.instance?.activeNotifications ?: return
            val callSbn = activeList.firstOrNull {
                (activeCallPackage.isNotEmpty() && it.packageName == activeCallPackage && isLikelyCallSbn(it)) ||
                isLikelyCallSbn(it)
            } ?: return

            val extras = callSbn.notification?.extras ?: return
            getParcelableCompat(extras, "android.answerIntent", PendingIntent::class.java)?.let { cachedAnswerIntent = it }
            getParcelableCompat(extras, "android.declineIntent", PendingIntent::class.java)?.let { cachedDeclineIntent = it }
            getParcelableCompat(extras, "android.hangUpIntent", PendingIntent::class.java)?.let { cachedHangUpIntent = it }

            callSbn.notification?.actions?.forEach { action ->
                val t = action.title?.toString()?.trim()?.lowercase() ?: ""
                val pi = action.actionIntent ?: return@forEach
                when {
                    t.contains("answer") || t.contains("accept") -> if (cachedAnswerIntent == null) cachedAnswerIntent = pi
                    t.contains("hang up") || t.contains("hangup") || t.contains("end") || t.contains("leave") -> cachedHangUpIntent = pi
                    t.contains("decline") || t.contains("reject") -> if (cachedDeclineIntent == null) cachedDeclineIntent = pi
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshCallNotificationIntents notice: ${e.message}")
        }
    }

    private fun markCallEnded(context: Context, forceDispatch: Boolean = false) {
        voipEndCheckJob?.cancel()
        val shouldDispatch = forceDispatch || (currentCallState != "IDLE" && currentCallState != "ENDED")

        answeredFromPc = false
        realSimCallActive = false
        stopAudioBridge()
        try {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMicrophoneMute = false
        } catch (_: Exception) {}

        if (shouldDispatch) {
            lastDispatchedSignature = ""
            dispatchCallEvent(context, "ENDED")
        }

        currentCallState = "IDLE"
        cachedAnswerIntent = null
        cachedDeclineIntent = null
        cachedHangUpIntent = null
        activeCallPackage = ""
        activeCallNotificationKey = ""
        currentCallerName = ""
        currentPhoneNumber = ""
        currentAppName = ""
        lastDispatchedSignature = ""
    }

    fun notifyAudioRouteChanged(context: Context, @Suppress("UNUSED_PARAMETER") onPc: Boolean) {
        if (currentCallState == "ACTIVE") {
            lastDispatchedSignature = ""
            dispatchCallEvent(context, "ACTIVE")
        }
    }

    private fun dispatchCallEvent(context: Context, state: String) {
        val displayCaller = currentCallerName.ifBlank { currentPhoneNumber }
        val audioOnPc = if (currentCallType == "CELLULAR" && state == "ACTIVE") {
            CallAudioBridgeService.shouldRouteToPc || isBluetoothScoCurrentlySelected(context)
        } else {
            CallAudioBridgeService.shouldRouteToPc
        }

        val signature = "$state|$currentAppName|$currentCallType|$displayCaller|$currentPhoneNumber|$audioOnPc"
        if (state != "ENDED" && lastDispatchedSignature == signature) return
        lastDispatchedSignature = signature

        try {
            val payload = JSONObject().apply {
                put("type", "CALL_EVENT")
                put("Type", "CALL_EVENT")
                put("state", state)
                put("State", state)
                put("appName", currentAppName)
                put("AppName", currentAppName)
                put("callType", currentCallType)
                put("CallType", currentCallType)
                put("phoneNumber", currentPhoneNumber)
                put("PhoneNumber", currentPhoneNumber)
                put("callerName", displayCaller)
                put("CallerName", displayCaller)
                put("audioOnPc", audioOnPc)
                put("AudioOnPc", audioOnPc)
                put("timestamp", System.currentTimeMillis())
            }

            Log.i(TAG, "Dispatching CALL_EVENT ($state, audioOnPc=$audioOnPc) to Windows: $payload")
            CommandTransferManager.sendCommand(context, payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch CALL_EVENT: ${e.message}", e)
        }
    }

    private fun sendPendingIntentWithBal(context: Context, pendingIntent: PendingIntent, label: String): Boolean {
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        } else {
            null
        }

        return try {
            if (options != null) {
                try {
                    pendingIntent.send(context, 0, null, null, null, null, options)
                } catch (e: Exception) {
                    Log.w(TAG, "$label PendingIntent.send with BAL options failed (${e.message}), retrying standard send()...")
                    pendingIntent.send()
                }
            } else {
                pendingIntent.send()
            }
            Log.i(TAG, "Successfully executed $label PendingIntent!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute $label PendingIntent: ${e.message}", e)
            false
        }
    }

    fun answerCall(context: Context): Boolean {
        Log.i(TAG, "Executing answerCall() [type=$currentCallType, app=$currentAppName]...")
        answeredFromPc = true
        CallAudioBridgeService.shouldRouteToPc = true
        refreshCallNotificationIntents()

        CallAudioBridgeService.connectHfp(context)
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
            CallAudioBridgeService.ensureCallVolumeAudible(it)
        }

        var success = false

        // 1. Cellular SIM Call ringing -> answer via TelecomManager.acceptRingingCall()
        if (currentCallType == "CELLULAR" && (realSimCallActive || isInManagedSimCall(context))) {
            startAudioBridge(context)
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomManager != null) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        @Suppress("DEPRECATION")
                        telecomManager.acceptRingingCall()
                        Log.i(TAG, "Answered cellular SIM call via TelecomManager.acceptRingingCall()")
                        success = true
                    } catch (e: Exception) {
                        Log.w(TAG, "TelecomManager.acceptRingingCall() failed: ${e.message}")
                    }
                }
            }
        }

        // 2. VoIP call (WhatsApp, Telegram, Instagram, Meet, etc.) OR fallback if TelecomManager didn't answer:
        if (!success) {
            // Answer the VoIP call FIRST while TelecomManager.isInCall() == false so the VoIP app never
            // thinks another call is blocking acceptance, then immediately bring up the Telecom VoIP HFP bridge!
            val answerPi = cachedAnswerIntent
            if (answerPi != null) {
                success = sendPendingIntentWithBal(context, answerPi, "Answer ($currentAppName)")
            } else {
                Log.w(TAG, "answerCall: No cachedAnswerIntent available for $currentAppName!")
            }

            if (currentCallType == "VOIP") {
                try { Thread.sleep(280) } catch (_: InterruptedException) {}
            }
            startAudioBridge(context)
        }

        if (success && currentCallState == "RINGING") {
            currentCallState = "ACTIVE"
            dispatchCallEvent(context, "ACTIVE")
        }

        return success
    }

    fun rejectOrEndCall(context: Context): Boolean {
        Log.i(TAG, "Executing rejectOrEndCall() [state=$currentCallState, type=$currentCallType, app=$currentAppName]...")
        refreshCallNotificationIntents()
        var success = false

        // 1. Only call TelecomManager.endCall() if an actual managed SIM cellular call is active!
        if (currentCallType == "CELLULAR" && (realSimCallActive || isInManagedSimCall(context))) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        @Suppress("DEPRECATION")
                        val ended = telecomManager.endCall()
                        Log.i(TAG, "TelecomManager.endCall() returned: $ended")
                        success = ended
                    } catch (e: Exception) {
                        Log.w(TAG, "TelecomManager.endCall() exception: ${e.message}")
                    }
                }
            }
        }

        // 2. For VoIP calls (WhatsApp, Telegram, etc.) OR if TelecomManager.endCall() returned false:
        if (!success) {
            val targetPi = if (currentCallState == "ACTIVE") {
                cachedHangUpIntent ?: cachedDeclineIntent
            } else {
                cachedDeclineIntent ?: cachedHangUpIntent
            }

            if (targetPi != null) {
                success = sendPendingIntentWithBal(context, targetPi, "Decline/HangUp ($currentAppName)")
            } else {
                Log.w(TAG, "rejectOrEndCall: No Decline or HangUp PendingIntent found for $currentAppName!")
            }
        }

        // Tear down audio bridge (and DroppVoipConnectionService) and confirm ENDED back to Windows
        markCallEnded(context, forceDispatch = true)
        return success
    }

    fun startAudioBridge(context: Context) {
        Log.i(TAG, "Triggering CallAudioBridgeService.startAudioBridge via Bluetooth HFP/SCO")
        CallAudioBridgeService.startAudioBridge(context)
    }

    fun stopAudioBridge() {
        Log.i(TAG, "Triggering CallAudioBridgeService.stopAudioBridge")
        CallAudioBridgeService.stopAudioBridge()
    }

    private fun resolveContactName(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed: ${e.message}")
            null
        }
    }
}
