package com.dropp.dropp.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

class FileTransferManager(private val context: Context) {
    private val TAG = "FileTransfer"
    private val FILE_PORT = 8767
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        scope.launch { listenForIncomingFiles() }
        Log.d(TAG, "File Transfer Server listening on port $FILE_PORT")
    }
    private var targetIp: String? = null

    fun setTargetIp(ip: String) {
        this.targetIp = ip
        Log.d(TAG, "Target IP set to $ip")
    }

    fun sendFile(fileUri: Uri) {
        if (targetIp == null) {
            Log.e(TAG, "Target IP is null. Cannot send file.")
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // 1. Query Android's ContentResolver to get the real File Name and Size
                    var fileName = "unknown_file"
                    var fileSize = 0L

                    context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }

                    if (fileSize == 0L) {
                        Log.e(TAG, "Could not determine file size. Aborting.")
                        return@withContext
                    }

                    Log.d(TAG, "Preparing to send: $fileName ($fileSize bytes)")

                    // 2. Connect to Windows and send the Header
                    java.net.Socket(targetIp, FILE_PORT).use { socket ->
                        val outStream = socket.getOutputStream()
                        val header = "FILENAME:$fileName|SIZE:$fileSize\n".toByteArray(Charsets.UTF_8)
                        outStream.write(header)

                        // 3. Stream the raw bytes in 64KB chunks
                        context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalSent = 0L

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outStream.write(buffer, 0, bytesRead)
                                totalSent += bytesRead
                            }

                            outStream.flush()
                            Log.d(TAG, "Successfully sent 100% of $fileName to Windows!")
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

                                val header = headerBytes.toString("UTF-8")
                                if (header.isEmpty()) return@launch

                                // 2. Parse Header (e.g., FILENAME:video.mp4|SIZE:524288000)
                                val parts = header.split("|")
                                val fileName = parts[0].split(":")[1]
                                val fileSize = parts[1].split(":")[1].toLong()

                                Log.d(TAG, "Incoming File Detected: $fileName ($fileSize bytes)")

                                // 3. Ask Android OS for a secure file path in the "Downloads/dropp" folder
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/dropp")
                                }

                                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                                if (uri != null) {
                                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                        // 4. Stream the file in 64KB chunks to keep memory usage at ~0%
                                        val buffer = ByteArray(64 * 1024)
                                        var totalRead: Long = 0

                                        while (totalRead < fileSize) {
                                            // Only read exactly what is left so we don't block the socket
                                            val bytesToRead = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
                                            val read = inputStream.read(buffer, 0, bytesToRead)

                                            if (read == -1) break

                                            outputStream.write(buffer, 0, read)
                                            totalRead += read

                                            // (Later: Update UI progress bar here using totalRead / fileSize)
                                        }

                                        outputStream.flush()
                                        Log.d(TAG, "Successfully received 100% of $fileName!")
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
}