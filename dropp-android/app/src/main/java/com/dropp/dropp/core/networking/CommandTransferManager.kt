package com.dropp.dropp.core.networking

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.net.Socket

object CommandTransferManager {
    // This will be set by your mDNS discovery when it finds the Windows PC
    var targetWindowsIp: String? = null

    // We use a different port (8081) for commands so it doesn't interfere with files
    private const val COMMAND_PORT = 8081

    fun sendCommand(jsonPayload: String) {
        val ip = targetWindowsIp
        if (ip == null) {
            Log.e("dropp", "Cannot send command: Windows IP not discovered yet!")
            return
        }

        // --- NEW: A strict error handler to catch silent coroutine deaths ---
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, exception ->
            Log.e("dropp", "FATAL Coroutine Crash: ${exception.message}", exception)
        }

        // Add the handler to the coroutine launch
        CoroutineScope(Dispatchers.IO + handler).launch {
            try {
                Log.d("dropp", "Connecting to Windows at $ip:$COMMAND_PORT...")
                Socket(ip, COMMAND_PORT).use { socket ->
                    val out = PrintWriter(socket.getOutputStream(), true)

                    // --- SECURITY: Encrypt the payload before sending! ---
                    val encryptedPayload = com.dropp.dropp.core.security.EncryptionHelper.encrypt(
                        jsonPayload,
                        com.dropp.dropp.core.security.SecurityManager.MASTER_KEY
                    )

                    out.println(encryptedPayload) // Send the scrambled text over the network
                    Log.d("dropp", "Encrypted command successfully sent to Windows!")
                }
            } catch (e: Exception) {
                Log.e("dropp", "Network Failed to send command: ${e.message}", e)
            }
        }
    }
}