package com.dropp.dropp.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class FileTransferManager(private val context: Context) {
    private val TAG = "FileTransfer"
    private val FILE_PORT = 5555
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        scope.launch { listenForIncomingFiles() }
        Log.d(TAG, "File Transfer Server listening on port $FILE_PORT")
    }

    private var targetIp: String? = null

    fun setTargetIp(ip: String) {
        if (ip.isBlank()) return
        this.targetIp = ip
        Log.d(TAG, "Target IP set to $ip")
    }

    fun sendFile(fileUri: Uri, isPreview: Boolean = false) {
        val activeIp = targetIp
            ?: com.dropp.dropp.core.networking.CommandTransferManager.targetWindowsIp
            ?: context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE).getString("last_windows_ip", null)
            ?: com.dropp.dropp.core.networking.CommandTransferManager.getArpCandidates().firstOrNull()

        if (activeIp.isNullOrBlank()) {
            Log.e(TAG, "Target IP is null and no candidate IP found. Cannot send file.")
            return
        }
        targetIp = activeIp

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // 1. Query Android's ContentResolver or File system for Name and Size
                    var fileName = "unknown_file"
                    var fileSize = 0L

                    if (fileUri.scheme == "content") {
                        try {
                            context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                    if (nameIndex != -1) cursor.getString(nameIndex)?.let { fileName = it }
                                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "ContentResolver query failed: ${e.message}")
                        }
                    }

                    if (fileSize == 0L) {
                        val path = fileUri.path
                        if (!path.isNullOrBlank()) {
                            val directFile = java.io.File(path)
                            if (directFile.exists() && directFile.isFile) {
                                fileName = directFile.name
                                fileSize = directFile.length()
                            }
                        }
                    }

                    if (fileSize == 0L) {
                        Log.e(TAG, "Could not determine file size for $fileUri. Aborting.")
                        return@withContext
                    }

                    Log.d(TAG, "Preparing to send: $fileName ($fileSize bytes) to $activeIp (isPreview=$isPreview)")

                    // 2. Connect to Windows with a 4-second timeout and send Header
                    val socket = Socket()
                    socket.connect(InetSocketAddress(activeIp, FILE_PORT), 4000)
                    socket.use { sock ->
                        val outStream = sock.getOutputStream()
                        val header = "FILENAME:$fileName|SIZE:$fileSize|PREVIEW:${if (isPreview) "TRUE" else "FALSE"}\n".toByteArray(Charsets.UTF_8)
                        outStream.write(header)
                        outStream.flush()

                        // 3. Stream the raw bytes in 64KB chunks
                        val inputStream = if (fileUri.scheme == "content") {
                            context.contentResolver.openInputStream(fileUri)
                        } else {
                            val p = fileUri.path ?: ""
                            val f = java.io.File(p)
                            if (f.exists()) java.io.FileInputStream(f) else context.contentResolver.openInputStream(fileUri)
                        }

                        inputStream?.use { inStream ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalSent = 0L

                            while (inStream.read(buffer).also { bytesRead = it } != -1) {
                                outStream.write(buffer, 0, bytesRead)
                                totalSent += bytesRead
                            }

                            outStream.flush()
                            Log.d(TAG, "Successfully sent 100% of $fileName to Windows ($activeIp)!")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send file: ${e.message}")
                }
            }
        }
    }

    private suspend fun listenForIncomingFiles() {
        withContext(Dispatchers.IO) {
            try {
                ServerSocket(FILE_PORT).use { serverSocket ->
                    while (isActive) {
                        val socket = serverSocket.accept()

                        // Auto-learn Windows PC IP from incoming connection
                        val remoteIp = socket.inetAddress?.hostAddress
                        if (!remoteIp.isNullOrBlank()) {
                            Log.d(TAG, "Auto-learned Windows IP from incoming transfer: $remoteIp")
                            setTargetIp(remoteIp)
                            DroppBackgroundService.clipboardManager?.setTargetIp(remoteIp)
                            com.dropp.dropp.core.networking.CommandTransferManager.targetWindowsIp = remoteIp
                            context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
                                .edit().putString("last_windows_ip", remoteIp).apply()
                        }

                        launch {
                            try {
                                val inputStream = socket.getInputStream()

                                // 1. Read Header Byte-by-Byte (to avoid consuming file bytes)
                                val headerBytes = ByteArrayOutputStream()
                                var b: Int
                                while (inputStream.read().also { b = it } != -1) {
                                    if (b == '\n'.code) break
                                    headerBytes.write(b)
                                }

                                val header = headerBytes.toString("UTF-8").trim()
                                if (header.isEmpty()) return@launch

                                // 2. Parse Header (e.g., FILENAME:video.mp4|SIZE:524288000|PREVIEW:TRUE)
                                val parts = header.split("|")
                                val fileName = parts[0].substringAfter(":")
                                val fileSize = parts[1].substringAfter(":").toLong()
                                var isPreview = false

                                for (part in parts) {
                                    if (part.startsWith("PREVIEW:", ignoreCase = true)) {
                                        isPreview = part.substringAfter(":").equals("TRUE", ignoreCase = true)
                                    }
                                }

                                Log.d(TAG, "Incoming File Detected: $fileName ($fileSize bytes, isPreview=$isPreview)")

                                if (isPreview) {
                                    // Ephemeral preview: Save into app cache directory and auto-view
                                    val previewDir = java.io.File(context.cacheDir, "dropp_preview").apply { mkdirs() }
                                    val tempFile = java.io.File(previewDir, fileName)

                                    tempFile.outputStream().use { outputStream ->
                                        val buffer = ByteArray(64 * 1024)
                                        var totalRead: Long = 0

                                        while (totalRead < fileSize) {
                                            val bytesToRead = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
                                            val read = inputStream.read(buffer, 0, bytesToRead)

                                            if (read == -1) break

                                            outputStream.write(buffer, 0, read)
                                            totalRead += read
                                        }

                                        outputStream.flush()
                                        Log.d(TAG, "Successfully received preview file: ${tempFile.absolutePath}")
                                    }

                                    openPreviewFile(context, tempFile)
                                } else {
                                    // Persistent download: Ask Android OS for a secure file path in "Downloads/dropp"
                                    val contentValues = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/dropp")
                                    }

                                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                                    if (uri != null) {
                                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                            val buffer = ByteArray(64 * 1024)
                                            var totalRead: Long = 0

                                            while (totalRead < fileSize) {
                                                val bytesToRead = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
                                                val read = inputStream.read(buffer, 0, bytesToRead)

                                                if (read == -1) break

                                                outputStream.write(buffer, 0, read)
                                                totalRead += read
                                            }

                                            outputStream.flush()
                                            Log.d(TAG, "Successfully received 100% of $fileName to Downloads!")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error receiving file: ${e.message}")
                            } finally {
                                socket.close()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "File Server socket error: ${e.message}")
            }
        }
    }

    private fun openPreviewFile(context: Context, file: java.io.File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            val extension = file.extension.lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Launched preview viewer for ${file.name} ($mimeType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch preview viewer: ${e.message}", e)
        }
    }
}