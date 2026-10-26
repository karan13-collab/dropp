package com.dropp.dropp.services

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dropp.dropp.core.networking.CommandTransferManager
import com.dropp.dropp.core.security.SecurityManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class NotificationSyncService : NotificationListenerService() {

    companion object {
        // Thread-safe cache of active notifications keyed by StatusBarNotification.key
        val cachedNotifications = ConcurrentHashMap<String, StatusBarNotification>()
        // Legacy map for backward compatibility with older command schemas
        val activeActions = ConcurrentHashMap<String, Notification.Action>()

        @Volatile
        var instance: NotificationSyncService? = null
            private set

        fun executeReply(
            context: Context,
            notificationKey: String,
            actionIndex: Int,
            resultKey: String?,
            replyText: String
        ): Boolean {
            var sbn = cachedNotifications[notificationKey]
            if (sbn == null) {
                // 1. Try key without newlines/carriage returns or whitespace
                val cleanKey = notificationKey.replace("\r", "").replace("\n", "").trim()
                sbn = cachedNotifications.entries.firstOrNull {
                    it.key.replace("\r", "").replace("\n", "").trim() == cleanKey
                }?.value
            }

            if (sbn == null && notificationKey.contains("|")) {
                // 2. Try matching by package name from the notification key (e.g. 0|com.whatsapp|...)
                val parts = notificationKey.split("|")
                val pkgName = parts.getOrNull(1)
                if (!pkgName.isNullOrBlank()) {
                    sbn = cachedNotifications.values.firstOrNull {
                        it.packageName == pkgName && it.notification.actions?.any { act -> !act.remoteInputs.isNullOrEmpty() } == true
                    }
                }
            }

            if (sbn == null) {
                // 3. Fallback: try active StatusBarNotifications directly from listener instance if bound
                instance?.activeNotifications?.let { activeList ->
                    val cleanKey = notificationKey.replace("\r", "").replace("\n", "").trim()
                    sbn = activeList.firstOrNull {
                        it.key.replace("\r", "").replace("\n", "").trim() == cleanKey
                    }
                    if (sbn == null && notificationKey.contains("|")) {
                        val pkgName = notificationKey.split("|").getOrNull(1)
                        sbn = activeList.firstOrNull {
                            it.packageName == pkgName && it.notification.actions?.any { act -> !act.remoteInputs.isNullOrEmpty() } == true
                        }
                    }
                }
            }

            if (sbn == null) {
                Log.e("dropp", "executeReply: No cached notification found for key: $notificationKey (cached count: ${cachedNotifications.size})")
                return false
            }

            val actions = sbn.notification.actions
            if (actions.isNullOrEmpty()) {
                Log.e("dropp", "executeReply: No actions on notification: ${sbn.key}")
                return false
            }

            val action = if (actionIndex in actions.indices && !actions[actionIndex].remoteInputs.isNullOrEmpty()) {
                actions[actionIndex]
            } else {
                actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            }

            if (action == null) {
                Log.e("dropp", "executeReply: No reply action found for notification: ${sbn.key}")
                return false
            }

            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                Log.e("dropp", "executeReply: Action has no remoteInputs")
                return false
            }

            val targetRemoteInput = if (!resultKey.isNullOrBlank()) {
                remoteInputs.firstOrNull { it.resultKey == resultKey } ?: remoteInputs[0]
            } else {
                remoteInputs[0]
            }

            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val bundle = Bundle()
            bundle.putCharSequence(targetRemoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

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
                        action.actionIntent.send(context, 0, intent, null, null, null, options)
                    } catch (e: Exception) {
                        Log.w("dropp", "actionIntent.send with options failed (${e.message}), trying without options...")
                        action.actionIntent.send(context, 0, intent)
                    }
                } else {
                    action.actionIntent.send(context, 0, intent)
                }
                Log.d("dropp", "Successfully executed reply to ${sbn.key}: $replyText")
                true
            } catch (e: Exception) {
                Log.e("dropp", "Failed to send reply PendingIntent: ${e.message}", e)
                false
            }
        }

        fun executeAction(
            context: Context,
            notificationKey: String,
            actionIndex: Int
        ): Boolean {
            var sbn = cachedNotifications[notificationKey]
            if (sbn == null) {
                val cleanKey = notificationKey.replace("\r", "").replace("\n", "").trim()
                sbn = cachedNotifications.entries.firstOrNull {
                    it.key.replace("\r", "").replace("\n", "").trim() == cleanKey
                }?.value
            }
            if (sbn == null && notificationKey.contains("|")) {
                val pkgName = notificationKey.split("|").getOrNull(1)
                if (!pkgName.isNullOrBlank()) {
                    sbn = cachedNotifications.values.firstOrNull { it.packageName == pkgName }
                }
            }
            if (sbn == null) {
                Log.e("dropp", "executeAction: No cached notification for key: $notificationKey")
                return false
            }
            val actions = sbn.notification.actions
            if (actions.isNullOrEmpty() || actionIndex !in actions.indices) {
                Log.e("dropp", "executeAction: Invalid actionIndex $actionIndex for key: ${sbn.key}")
                return false
            }

            val action = actions[actionIndex]
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
                        action.actionIntent.send(context, 0, null, null, null, null, options)
                    } catch (e: Exception) {
                        Log.w("dropp", "actionIntent.send with options failed (${e.message}), trying without options...")
                        action.actionIntent.send(context, 0, null)
                    }
                } else {
                    action.actionIntent.send(context, 0, null)
                }
                Log.d("dropp", "Successfully executed action $actionIndex for ${sbn.key}")
                true
            } catch (e: Exception) {
                Log.e("dropp", "Failed to send action PendingIntent: ${e.message}", e)
                false
            }
        }

        fun dismissNotification(notificationKey: String): Boolean {
            cachedNotifications.remove(notificationKey)
            val cleanKey = notificationKey.replace("\r", "").replace("\n", "").trim()
            val inst = instance
            return if (inst != null) {
                try {
                    inst.cancelNotification(notificationKey)
                    Log.d("dropp", "Dismissed notification $notificationKey on Android")
                    true
                } catch (e: Exception) {
                    try {
                        val matchingSbn = inst.activeNotifications.firstOrNull {
                            it.key.replace("\r", "").replace("\n", "").trim() == cleanKey
                        }
                        if (matchingSbn != null) {
                            inst.cancelNotification(matchingSbn.key)
                            Log.d("dropp", "Dismissed notification ${matchingSbn.key} via clean match")
                            return true
                        }
                    } catch (_: Exception) {}
                    Log.e("dropp", "Failed to cancel notification $notificationKey: ${e.message}", e)
                    false
                }
            } else {
                Log.w("dropp", "NotificationSyncService instance is null; cannot dismiss $notificationKey")
                false
            }
        }

        fun executeReplyLegacy(context: Context, actionId: String, text: String): Boolean {
            val action = activeActions[actionId] ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            if (remoteInputs.isEmpty()) return false
            val remoteInput = remoteInputs[0]

            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, text)
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

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
                action.actionIntent.send(context, 0, intent, null, null, null, options)
                Log.d("dropp", "Executed legacy reply for $actionId: $text")
                true
            } catch (e: Exception) {
                Log.e("dropp", "Error executing legacy reply: ${e.message}", e)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SecurityManager.init(this)
        try {
            val serviceIntent = Intent(this, DroppBackgroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } catch (_: Exception) {}
        Log.d("dropp", "NotificationSyncService created and bound.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d("dropp", "NotificationSyncService destroyed.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        Log.d("dropp", "onNotificationPosted: pkg=$packageName, key=${sbn.key}, ongoing=${sbn.isOngoing}")

        // Filter out dropp itself to prevent loops
        if (packageName == this.packageName || packageName == "com.dropp.dropp") {
            return
        }

        // Detect incoming phone call notifications and pass to CallPassthroughManager
        val hasCallAction = sbn.notification.actions?.any { act ->
            val t = act.title?.toString()?.lowercase() ?: ""
            t.contains("answer") || t.contains("accept") || t.contains("decline") || t.contains("hang up")
        } == true

        val isCallNotification = sbn.notification.category == Notification.CATEGORY_CALL ||
                hasCallAction ||
                packageName.contains("dialer") ||
                packageName.contains("telecom") ||
                packageName.contains("incallui") ||
                packageName.contains("phone")

        if (isCallNotification) {
            CallPassthroughManager.onCallNotification(this, sbn)
        }

        // Filter out ongoing notifications (media players, persistent foreground services) and group summaries
        if (sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
            ?: appName
        var text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?: ""

        // Handle MessagingStyle (e.g. WhatsApp, Messages, or ADB messaging notification)
        if (text.isBlank()) {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!messages.isNullOrEmpty()) {
                val lastMsg = messages.lastOrNull()
                if (lastMsg is Bundle) {
                    text = lastMsg.getCharSequence("text")?.toString()?.trim() ?: ""
                }
            }
        }

        val timestamp = sbn.postTime

        if (title.isBlank() && text.isBlank() && sbn.notification.actions.isNullOrEmpty()) {
            return
        }

        // Cache the notification in memory for handling interactive reply/actions
        cachedNotifications[sbn.key] = sbn

        val actionsList = JSONArray()
        sbn.notification.actions?.forEachIndexed { index, action ->
            val actionTitle = action.title?.toString()?.trim() ?: ""
            val actionId = "${sbn.key}_$actionTitle"
            activeActions[actionId] = action

            val actionJson = JSONObject().apply {
                put("actionIndex", index)
                put("ActionIndex", index)
                put("title", actionTitle)
                put("Title", actionTitle)
                put("id", actionId)
                put("Id", actionId)

                val remoteInputs = action.remoteInputs
                if (!remoteInputs.isNullOrEmpty()) {
                    val resultKey = remoteInputs.firstOrNull()?.resultKey ?: "key_text_reply"
                    put("actionType", "REPLY")
                    put("ActionType", "REPLY")
                    put("isReply", true)
                    put("IsReply", true)
                    put("resultKey", resultKey)
                    put("ResultKey", resultKey)
                } else {
                    put("actionType", "BUTTON")
                    put("ActionType", "BUTTON")
                    put("isReply", false)
                    put("IsReply", false)
                }
            }
            actionsList.put(actionJson)
        }

        try {
            val payload = JSONObject().apply {
                put("type", "NOTIFICATION")
                put("Type", "NOTIFICATION")
                put("notificationKey", sbn.key)
                put("NotificationKey", sbn.key)
                put("packageName", packageName)
                put("Package", packageName)
                put("appName", appName)
                put("AppName", appName)
                put("title", title)
                put("Title", title)
                put("text", text)
                put("Text", text)
                put("timestamp", timestamp)
                put("Timestamp", timestamp)
                put("actions", actionsList)
                put("Actions", actionsList)
            }

            val jsonString = payload.toString()
            Log.d("dropp", "Mirroring notification to Windows: $appName ($title): $text")
            CommandTransferManager.sendCommand(this, jsonString)
        } catch (e: Exception) {
            Log.e("dropp", "Error building/sending notification JSON: ${e.message}", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        cachedNotifications.remove(sbn.key)
        activeActions.keys.removeIf { it.startsWith(sbn.key) }
        CallPassthroughManager.onCallNotificationRemoved(this, sbn)

        try {
            val payload = JSONObject().apply {
                put("type", "NOTIFICATION_DISMISS")
                put("Type", "NOTIFICATION_DISMISS")
                put("notificationKey", sbn.key)
                put("NotificationKey", sbn.key)
            }
            CommandTransferManager.sendCommand(this, payload.toString())
            Log.d("dropp", "Sent NOTIFICATION_DISMISS to Windows for key: ${sbn.key}")
        } catch (e: Exception) {
            Log.e("dropp", "Error sending dismiss notification: ${e.message}", e)
        }
    }
}