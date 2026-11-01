package com.dropp.dropp.core.networking

import android.content.Context
import android.util.Log
import com.dropp.dropp.core.security.EncryptionHelper
import com.dropp.dropp.core.security.SecurityManager
import android.net.Uri
import com.dropp.dropp.services.DroppBackgroundService
import com.dropp.dropp.services.FileTransferManager
import com.dropp.dropp.services.NotificationSyncService
import com.dropp.dropp.services.SearchManager
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

                "CALL_COMMAND" -> {
                    val action = (json.optString("action").ifEmpty { json.optString("Action") }).uppercase()
                    Log.i("dropp", "Received CALL_COMMAND: action=$action")
                    when (action) {
                        "CALL_ANSWER", "ANSWER" -> {
                            com.dropp.dropp.services.CallPassthroughManager.answerCall(context)
                        }
                        "CALL_REJECT", "REJECT", "CALL_END", "END" -> {
                            com.dropp.dropp.services.CallPassthroughManager.rejectOrEndCall(context)
                        }
                        "CALL_AUDIO_START", "AUDIO_START" -> {
                            com.dropp.dropp.services.CallPassthroughManager.startAudioBridge(context)
                        }
                        "CALL_AUDIO_STOP", "AUDIO_STOP" -> {
                            com.dropp.dropp.services.CallPassthroughManager.stopAudioBridge()
                        }
                        "CALL_MIC_MUTE", "MIC_MUTE" -> {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            audioManager.isMicrophoneMute = true
                            Log.i("dropp", "Microphone muted via CALL_MIC_MUTE command")
                        }
                        "CALL_MIC_UNMUTE", "MIC_UNMUTE" -> {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            audioManager.isMicrophoneMute = false
                            Log.i("dropp", "Microphone unmuted via CALL_MIC_UNMUTE command")
                        }
                        else -> {
                            Log.w("dropp", "Unknown CALL_COMMAND action: $action")
                        }
                    }
                }

                "AUDIO_ROUTING_STATUS" -> {
                    val enabled = if (json.has("enabled")) json.optBoolean("enabled") else json.optBoolean("Enabled", false)
                    Log.i("dropp", "Received AUDIO_ROUTING_STATUS from Windows: enabled=$enabled")
                    com.dropp.dropp.services.AudioRoutingManager.setRoutingState(enabled)
                    if (enabled) {
                        com.dropp.dropp.services.AudioRoutingManager.connectA2dp(context)
                    }
                }

                "SEARCH_REQ" -> {
                    SearchManager.handleSearchRequest(context, json)
                }

                "SEARCH_RES" -> {
                    SearchManager.handleSearchResponse(json)
                }

                "BROWSE_REQ" -> {
                    com.dropp.dropp.services.FileBrowserManager.handleBrowseRequest(context, json)
                }

                "BROWSE_RES" -> {
                    com.dropp.dropp.services.FileBrowserManager.handleBrowseResponse(json)
                }

                "GALLERY_REQ" -> {
                    com.dropp.dropp.services.GalleryManager.handleGalleryRequest(context, json)
                }

                "GALLERY_ALBUMS_REQ" -> {
                    com.dropp.dropp.services.GalleryManager.handleAlbumsRequest(context, json)
                }

                "STREAM_REQ" -> {
                    val fileId = json.optString("id").ifEmpty { json.optString("Id") }
                    val isPreview = if (json.has("isPreview")) json.optBoolean("isPreview") else json.optBoolean("IsPreview", false)
                    Log.i("dropp", "Received STREAM_REQ for id=$fileId, isPreview=$isPreview")
                    if (fileId.isNotEmpty()) {
                        val uri = Uri.parse(fileId)
                        val ftm = DroppBackgroundService.fileTransferManager ?: FileTransferManager(context)
                        ftm.sendFile(uri, isPreview)
                    }
                }

                "DISCOVERY_PING" -> {
                    val pong = JSONObject().apply {
                        put("type", "DISCOVERY_PONG")
                        put("Type", "DISCOVERY_PONG")
                    }
                    CommandTransferManager.sendCommand(context, pong.toString())
                }

                "DISCOVERY_PONG" -> {
                    Log.i("dropp", "Received DISCOVERY_PONG from Windows. Active link confirmed.")
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