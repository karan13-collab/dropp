package com.dropp.dropp.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class ClipboardSyncManager(private val context: Context) {
    private val TAG = "ClipboardSync"
    private val PORT = 8766
    private var targetIp: String? = null

    private var lastSyncedText = ""
    private var lastSyncTime = 0L
    private val syncMutex = Mutex()

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun setTargetIp(ip: String) { targetIp = ip }

    private fun normalizeForCache(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.replace("\r\n", "\n").trim()
    }

    fun start() {
        scope.launch { listenForIncomingData() }
        Handler(Looper.getMainLooper()).post {
            clipboard.addPrimaryClipChangedListener { checkAndSyncCurrentClipboard() }
        }
    }

    fun checkAndSyncCurrentClipboard() {
        if (targetIp == null) return

        Handler(Looper.getMainLooper()).post {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)

                scope.launch {
                    syncMutex.withLock {
                        if (System.currentTimeMillis() - lastSyncTime < 1500) return@launch
                    }

                    // HANDLE IMAGES
                    if (item.uri != null) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(item.uri)
                            val bytes = inputStream?.readBytes()
                            if (bytes != null) {
                                syncMutex.withLock { lastSyncTime = System.currentTimeMillis() }
                                Log.d(TAG, "Image copied. Sending to Windows...")
                                sendDataToWindows("IMAGE", bytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read image URI: ${e.message}")
                        }
                    }
                    // HANDLE TEXT
                    else if (item.text != null) {
                        val rawText = item.text.toString()
                        val normText = normalizeForCache(rawText)

                        if (normText.isNotEmpty()) {
                            syncMutex.withLock {
                                if (normText == lastSyncedText) return@launch
                                lastSyncTime = System.currentTimeMillis()
                                lastSyncedText = normText
                            }
                            sendDataToWindows("TEXT", rawText.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    private fun sendDataToWindows(type: String, payload: ByteArray) {
        val activeIp = targetIp ?: context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
            .getString("last_windows_ip", null)
        if (activeIp.isNullOrBlank()) return
        targetIp = activeIp

        try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(activeIp, PORT), 4000)
            socket.use { sock ->
                val outStream = sock.getOutputStream()
                val header = "TYPE:$type|SIZE:${payload.size}\n".toByteArray(Charsets.UTF_8)

                outStream.write(header)
                outStream.write(payload)
                outStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP Send failed: ${e.message}")
        }
    }

    private suspend fun listenForIncomingData() {
        withContext(Dispatchers.IO) {
            ServerSocket(PORT).use { serverSocket ->
                while (isActive) {
                    val socket = serverSocket.accept()

                    // Auto-learn Windows PC IP from incoming connection
                    val remoteIp = socket.inetAddress?.hostAddress
                    if (!remoteIp.isNullOrBlank()) {
                        setTargetIp(remoteIp)
                        DroppBackgroundService.fileTransferManager?.setTargetIp(remoteIp)
                        com.dropp.dropp.core.networking.CommandTransferManager.targetWindowsIp = remoteIp
                        context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
                            .edit().putString("last_windows_ip", remoteIp).apply()
                    }

                    launch {
                        try {
                            val inputStream = socket.getInputStream()

                            // 1. Read Header Byte-by-Byte
                            val headerBytes = ByteArrayOutputStream()
                            var b: Int
                            while (inputStream.read().also { b = it } != -1) {
                                if (b == '\n'.code) break
                                headerBytes.write(b)
                            }

                            val header = headerBytes.toString("UTF-8").trim()
                            if (header.isEmpty()) return@launch

                            val parts = header.split("|")
                            val type = parts[0].substringAfter(":")
                            val size = parts[1].substringAfter(":").toInt()

                            // 2. Read Payload
                            val payload = ByteArray(size)
                            var totalRead = 0
                            while (totalRead < size) {
                                val read = inputStream.read(payload, totalRead, size - totalRead)
                                if (read == -1) break
                                totalRead += read
                            }

                            syncMutex.withLock { lastSyncTime = System.currentTimeMillis() }

                            if (type == "TEXT") {
                                val rawReceived = String(payload, Charsets.UTF_8)
                                val normReceived = normalizeForCache(rawReceived)

                                syncMutex.withLock { lastSyncedText = normReceived }

                                withContext(Dispatchers.Main) {
                                    val clip = ClipData.newPlainText("dropp", rawReceived)
                                    clipboard.setPrimaryClip(clip)
                                }
                            }
                            else if (type == "IMAGE") {
                                // 3. Save binary image to Cache
                                val tempFile = File(context.cacheDir, "dropp_received.png")
                                tempFile.writeBytes(payload)

                                // 4. Generate Content URI
                                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)

                                withContext(Dispatchers.Main) {
                                    // 5. Explicitly declare MIME types so Gboard and WhatsApp recognize it!
                                    val clip = ClipData(
                                        "dropp image",
                                        arrayOf("image/png", "image/jpeg", "image/*"),
                                        ClipData.Item(uri)
                                    )
                                    clipboard.setPrimaryClip(clip)
                                    Log.d(TAG, "Image successfully applied to Android clipboard!")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error receiving data: ${e.message}")
                        } finally {
                            socket.close()
                        }
                    }
                }
            }
        }
    }
}