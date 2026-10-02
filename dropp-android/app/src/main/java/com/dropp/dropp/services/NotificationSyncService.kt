package com.dropp.dropp.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.RemoteInput

class NotificationSyncService : NotificationListenerService() {

    // This stores the executable Android intents so Windows can trigger them later
    companion object {
        val activeActions = mutableMapOf<String, Notification.Action>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val packageName = sbn.packageName

        // We don't want to mirror ongoing system background tasks (like Spotify playing)
        if ((sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        Log.d("dropp", "Caught Notification: $title - $text from $packageName")

        val actionsList = mutableListOf<Map<String, Any>>()

        // Extract the interactive buttons (Reply, Mark as Read, etc.)
        notification.actions?.forEach { action ->
            val actionTitle = action.title.toString()
            val actionId = "${sbn.key}_$actionTitle"

            activeActions[actionId] = action // Save the executable intent in memory

            // Check if this specific button allows the user to type text (Inline Reply)
            val isReply = action.remoteInputs?.any { it.allowFreeFormInput } == true

            actionsList.add(
                mapOf(
                    "Id" to actionId,
                    "Title" to actionTitle,
                    "IsReply" to isReply
                )
            )
        }

        try {
            val payload = org.json.JSONObject().apply {
                put("Type", "NOTIFICATION")
                put("Package", packageName)
                put("Title", title)
                put("Text", text)
                put("NotificationKey", sbn.key)

                val actionsArray = org.json.JSONArray()
                actionsList.forEach { actionMap ->
                    val actionJson = org.json.JSONObject().apply {
                        put("Id", actionMap["Id"])
                        put("Title", actionMap["Title"])
                        put("IsReply", actionMap["IsReply"])
                    }
                    actionsArray.put(actionJson)
                }
                put("Actions", actionsArray)
            }

            val jsonString = payload.toString()
            Log.d("dropp", "Ready to send to Windows: $jsonString")

            // We need to send this over our TCP socket!
            com.dropp.dropp.core.networking.CommandTransferManager.sendCommand(jsonString)

        } catch (e: Exception) {
            Log.e("dropp", "Error building JSON: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Clean up memory when the user swipes away the notification on their phone
        activeActions.keys.removeAll { it.startsWith(sbn.key) }
    }
}