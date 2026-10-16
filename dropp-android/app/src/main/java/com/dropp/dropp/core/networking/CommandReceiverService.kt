package com.dropp.dropp.core.networking

import android.content.Context
import android.util.Log
import com.dropp.dropp.core.security.EncryptionHelper
import com.dropp.dropp.core.security.SecurityManager
import com.dropp.dropp.services.DroppBackgroundService
import com.dropp.dropp.services.NotificationSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

object CommandReceiverService {
    private const val COMMAND_PORT = 8767
    @Volatile
    private var isListening = false
    private var serverSocket: ServerSocket? = null

    fun startListening(context: Context) {
        if (isListening) return
        isListening = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ss = ServerSocket(COMMAND_PORT).apply {
                    reuseAddress = true
                }
                serverSocket = ss
                Log.d("dropp", "Android Command Receiver listening on port $COMMAND_PORT...")

                while (isListening && !ss.isClosed) {
                    try {
                        val socket = ss.accept()

                        // Auto-learn Windows PC IP from incoming connection
                        val remoteIp = socket.inetAddress?.hostAddress
                        if (!remoteIp.isNullOrBlank()) {
                            val cleanIp = if (remoteIp.startsWith("/")) remoteIp.substring(1) else remoteIp
                            CommandTransferManager.targetWindowsIp = cleanIp
                            DroppBackgroundService.fileTransferManager?.setTargetIp(cleanIp)
                            DroppBackgroundService.clipboardManager?.setTargetIp(cleanIp)
                            context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
                                .edit().putString("last_windows_ip", cleanIp).apply()
                        }

                        // Handle incoming command asynchronously so accept() loop is never blocked
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                socket.use { s ->
                                    s.soTimeout = 10000
                                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                                    val encryptedLine = reader.readLine()
                                    if (!encryptedLine.isNullOrBlank()) {
                                        val masterKey = SecurityManager.getKey(context)
                                        if (masterKey.isNotEmpty()) {
                                            val jsonString = EncryptionHelper.decrypt(encryptedLine, masterKey)
                                            Log.d("dropp", "Decrypted command from Windows: $jsonString")
                                            processCommand(context, jsonString)
                                        } else {
                                            Log.e("dropp", "Cannot decrypt command: MasterKey is empty!")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("dropp", "Error reading command payload: ${e.message}", e)
                            }
                        }
                    } catch (e: Exception) {
                        if (isListening) {
                            Log.e("dropp", "Error accepting client on port $COMMAND_PORT: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("dropp", "Command Receiver ServerSocket fatal error: ${e.message}", e)
            } finally {
                isListening = false
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stopListening() {
        isListening = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    @Volatile
    private var lastReplyFingerprint: String = ""
    @Volatile
    private var lastReplyTime: Long = 0L

    private fun processCommand(context: Context, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val type = (json.optString("type").ifEmpty { json.optString("Type") }).uppercase()

            when (type) {
                "NOTIFICATION_REPLY", "REPLY" -> {
                    val rawKey = json.optString("notificationKey").ifEmpty { json.optString("NotificationKey") }
                    val notificationKey = if (rawKey.contains("%")) {
                        try { java.net.URLDecoder.decode(rawKey, "UTF-8") } catch (_: Exception) { rawKey }
                    } else rawKey
                    val actionIndex = if (json.has("actionIndex")) json.optInt("actionIndex") else json.optInt("ActionIndex", 0)
                    val resultKey = json.optString("resultKey").ifEmpty { json.optString("ResultKey") }
                    val replyText = json.optString("replyText").ifEmpty { json.optString("ReplyText") }.ifEmpty { json.optString("Text") }

                    val fingerprint = "$notificationKey:$replyText"
                    val now = System.currentTimeMillis()
                    if (now - lastReplyTime < 4000 && lastReplyFingerprint == fingerprint) {
                        Log.w("dropp", "Duplicate NOTIFICATION_REPLY received within 4s, skipping: $fingerprint")
                        return
                    }
                    lastReplyTime = now
                    lastReplyFingerprint = fingerprint

                    if (notificationKey.isNotEmpty()) {
                        NotificationSyncService.executeReply(context, notificationKey, actionIndex, resultKey, replyText)
                    } else {
                        val actionId = json.optString("ActionId").ifEmpty { json.optString("actionId") }
                        if (actionId.isNotEmpty()) {
                            NotificationSyncService.executeReplyLegacy(context, actionId, replyText)
                        } else {
                            Log.w("dropp", "NOTIFICATION_REPLY received with no notificationKey or actionId")
                        }
                    }
                }

                "NOTIFICATION_ACTION", "ACTION" -> {
                    val rawKey = json.optString("notificationKey").ifEmpty { json.optString("NotificationKey") }
                    val notificationKey = if (rawKey.contains("%")) {
                        try { java.net.URLDecoder.decode(rawKey, "UTF-8") } catch (_: Exception) { rawKey }
                    } else rawKey
                    val actionIndex = if (json.has("actionIndex")) json.optInt("actionIndex") else json.optInt("ActionIndex", 0)

                    if (notificationKey.isNotEmpty()) {
                        NotificationSyncService.executeAction(context, notificationKey, actionIndex)
                    } else {
                        Log.w("dropp", "NOTIFICATION_ACTION received with no notificationKey")
                    }
                }

                "NOTIFICATION_DISMISS", "DISMISS" -> {
                    val rawKey = json.optString("notificationKey").ifEmpty { json.optString("NotificationKey") }
                    val notificationKey = if (rawKey.contains("%")) {
                        try { java.net.URLDecoder.decode(rawKey, "UTF-8") } catch (_: Exception) { rawKey }
                    } else rawKey
                    if (notificationKey.isNotEmpty()) {
                        NotificationSyncService.dismissNotification(notificationKey)
                    }
                }

                else -> {
                    Log.w("dropp", "Unknown command type received: $type")
                }
            }
        } catch (e: Exception) {
            Log.e("dropp", "Error processing command JSON: ${e.message}", e)
        }
    }
}