package com.dropp.dropp.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dropp.dropp.core.networking.CommandTransferManager
import org.json.JSONObject

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

    private var telephonyCallback: Any? = null
    private var isListening = false
    private var lastDispatchedState: String = ""

    // Caches the Answer and Decline/End notification actions from active dialer notifications
    @Volatile
    var cachedAnswerAction: android.app.Notification.Action? = null
    @Volatile
    var cachedDeclineAction: android.app.Notification.Action? = null

    fun start(context: Context) {
        if (isListening) return
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager not available on this device.")
            return
        }

        // Initialize Bluetooth HFP proxy early so SCO routing is ready when a call arrives
        CallAudioBridgeService.initHeadsetProxy(context)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        onTelephonyStateChanged(context, state, null)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
                isListening = true
                Log.d(TAG, "Registered TelephonyCallback for call state detection.")
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onTelephonyStateChanged(context, state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = listener
                isListening = true
                Log.d(TAG, "Registered PhoneStateListener for call state detection.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register telephony listener: ${e.message}", e)
        }
    }

    fun stop(context: Context) {
        if (!isListening) return
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyCallback?.let { cb ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
                    telephonyManager?.unregisterTelephonyCallback(cb)
                } else if (cb is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    telephonyManager?.listen(cb, PhoneStateListener.LISTEN_NONE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering telephony listener: ${e.message}")
            }
        }
        telephonyCallback = null
        isListening = false
    }

    private fun onTelephonyStateChanged(context: Context, state: Int, incomingNumber: String?) {
        val newState = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "ACTIVE"
            TelephonyManager.CALL_STATE_IDLE -> if (currentCallState == "ACTIVE" || currentCallState == "RINGING") "ENDED" else "IDLE"
            else -> "IDLE"
        }

        if (newState == "IDLE") {
            currentCallState = "IDLE"
            return
        }

        Log.i(TAG, "Telephony state changed: $state -> $newState (number: $incomingNumber)")
        currentCallState = newState

        if (!incomingNumber.isNullOrBlank()) {
            currentPhoneNumber = incomingNumber
            val resolved = resolveContactName(context, incomingNumber)
            if (!resolved.isNullOrBlank()) {
                currentCallerName = resolved
            } else if (currentCallerName.isBlank()) {
                currentCallerName = incomingNumber
            }
        }

        dispatchCallEvent(context, newState)

        if (newState == "RINGING") {
            // Proactively connect Bluetooth HFP so hands-free channel is ready when answered
            CallAudioBridgeService.connectHfp(context)
        } else if (newState == "ACTIVE") {
            // Automatically launch the real-time audio bridge when the call goes active
            startAudioBridge(context)
        } else if (newState == "ENDED") {
            // Stop the audio bridge when the call terminates
            stopAudioBridge()
            cachedAnswerAction = null
            cachedDeclineAction = null
            currentCallerName = ""
            currentPhoneNumber = ""
            lastDispatchedState = ""
        }
    }

    // Called by NotificationSyncService when an incoming call notification arrives
    fun onCallNotification(context: Context, sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isNotBlank()) {
            if (currentCallerName.isBlank() || currentCallerName == currentPhoneNumber) {
                currentCallerName = title
            }
        }
        if (text.isNotBlank() && currentPhoneNumber.isBlank()) {
            currentPhoneNumber = text
        }

        // Cache actions (Answer / Decline)
        notif.actions?.forEach { action ->
            val actionTitle = action.title?.toString()?.lowercase() ?: ""
            if (actionTitle.contains("answer") || actionTitle.contains("accept")) {
                cachedAnswerAction = action
                Log.d(TAG, "Cached call Answer action: ${action.title}")
            } else if (actionTitle.contains("decline") || actionTitle.contains("reject") || actionTitle.contains("dismiss") || actionTitle.contains("hang up")) {
                cachedDeclineAction = action
                Log.d(TAG, "Cached call Decline action: ${action.title}")
            }
        }

        // When a call notification arrives, always dispatch RINGING to Windows if not already ACTIVE
        if (currentCallState != "ACTIVE") {
            currentCallState = "RINGING"
            CallAudioBridgeService.connectHfp(context)
            dispatchCallEvent(context, "RINGING")
        }
    }

    fun onCallNotificationRemoved(context: Context, sbn: StatusBarNotification) {
        val isCall = sbn.notification?.category == android.app.Notification.CATEGORY_CALL ||
                sbn.packageName.contains("dialer") ||
                sbn.packageName.contains("telecom") ||
                sbn.packageName.contains("incallui") ||
                sbn.packageName.contains("whatsapp")

        if (isCall && currentCallState == "RINGING") {
            Log.d(TAG, "Call notification removed while RINGING -> dispatching ENDED")
            currentCallState = "ENDED"
            dispatchCallEvent(context, "ENDED")
            cachedAnswerAction = null
            cachedDeclineAction = null
            currentCallerName = ""
            currentPhoneNumber = ""
            lastDispatchedState = ""
        }
    }

    private fun dispatchCallEvent(context: Context, state: String) {
        // Prevent duplicate spam of same state unless parameters change
        if (lastDispatchedState == state && state != "RINGING") return
        lastDispatchedState = state

        try {
            val payload = JSONObject().apply {
                put("type", "CALL_EVENT")
                put("Type", "CALL_EVENT")
                put("state", state)
                put("State", state)
                put("phoneNumber", currentPhoneNumber)
                put("PhoneNumber", currentPhoneNumber)
                put("callerName", if (currentCallerName.isNotBlank()) currentCallerName else currentPhoneNumber)
                put("CallerName", if (currentCallerName.isNotBlank()) currentCallerName else currentPhoneNumber)
                put("timestamp", System.currentTimeMillis())
            }

            Log.i(TAG, "Dispatching CALL_EVENT ($state) to Windows: $payload")
            CommandTransferManager.sendCommand(context, payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch CALL_EVENT: ${e.message}", e)
        }
    }

    fun answerCall(context: Context): Boolean {
        Log.i(TAG, "Executing answerCall()...")
        var success = false

        // 1. Try TelecomManager acceptRingingCall()
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomManager != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager.acceptRingingCall()
                    Log.i(TAG, "Answered call via TelecomManager.acceptRingingCall()")
                    success = true
                } catch (e: Exception) {
                    Log.w(TAG, "TelecomManager.acceptRingingCall() failed: ${e.message}")
                }
            }
        }

        // 2. Fallback to cached Answer PendingIntent from call notification
        if (!success) {
            cachedAnswerAction?.actionIntent?.let { pendingIntent ->
                try {
                    pendingIntent.send()
                    Log.i(TAG, "Answered call via cached Notification Action PendingIntent")
                    success = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send Answer PendingIntent: ${e.message}")
                }
            }
        }

        // 3. Immediately start audio bridge to Windows
        startAudioBridge(context)
        return success
    }

    fun rejectOrEndCall(context: Context): Boolean {
        Log.i(TAG, "Executing rejectOrEndCall()...")
        var success = false

        // 1. Try TelecomManager endCall()
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    @Suppress("DEPRECATION")
                    telecomManager.endCall()
                    Log.i(TAG, "Ended/Rejected call via TelecomManager.endCall()")
                    success = true
                } catch (e: Exception) {
                    Log.w(TAG, "TelecomManager.endCall() failed: ${e.message}")
                }
            }
        }

        // 2. Fallback to cached Decline PendingIntent from call notification
        if (!success) {
            cachedDeclineAction?.actionIntent?.let { pendingIntent ->
                try {
                    pendingIntent.send()
                    Log.i(TAG, "Ended/Rejected call via cached Decline PendingIntent")
                    success = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send Decline PendingIntent: ${e.message}")
                }
            }
        }

        // 3. Stop audio bridge
        stopAudioBridge()
        return success
    }

    fun startAudioBridge(context: Context) {
        Log.i(TAG, "Triggering CallAudioBridgeService.startAudioBridge via Bluetooth SCO")
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
