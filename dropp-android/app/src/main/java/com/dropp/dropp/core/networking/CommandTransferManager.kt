package com.dropp.dropp.core.networking

import android.content.Context
import android.util.Log
import com.dropp.dropp.core.security.EncryptionHelper
import com.dropp.dropp.core.security.SecurityManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object CommandTransferManager {
    private val _targetWindowsIp = MutableStateFlow<String?>(null)
    val targetWindowsIpFlow = _targetWindowsIp.asStateFlow()

    var targetWindowsIp: String?
        get() = _targetWindowsIp.value
        set(value) {
            _targetWindowsIp.value = value
        }

    fun init(context: Context) {
        if (_targetWindowsIp.value.isNullOrBlank()) {
            val savedIp = context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
                .getString("last_windows_ip", null)
            if (!savedIp.isNullOrBlank()) {
                _targetWindowsIp.value = savedIp
            }
        }
    }

    private const val COMMAND_PORT = 8767
    private const val CONNECT_TIMEOUT_MS = 5000

    fun sendCommand(context: Context, jsonPayload: String) {
        val ip = targetWindowsIp ?: context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
            .getString("last_windows_ip", null)

        if (ip.isNullOrBlank()) {
            Log.e("dropp", "Cannot send command: Windows IP not discovered yet!")
            return
        }

        val masterKey = SecurityManager.getKey(context)
        if (masterKey.isEmpty()) {
            Log.e("dropp", "Cannot send command: MasterKey is not set! Please pair via QR code first.")
            return
        }

        val handler = CoroutineExceptionHandler { _, exception ->
            Log.e("dropp", "FATAL Coroutine Crash in CommandTransferManager: ${exception.message}", exception)
        }

        CoroutineScope(Dispatchers.IO + handler).launch {
            try {
                Log.d("dropp", "Connecting to Windows at $ip:$COMMAND_PORT...")
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, COMMAND_PORT), CONNECT_TIMEOUT_MS)
                    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                    val encryptedPayload = EncryptionHelper.encrypt(jsonPayload, masterKey)
                    writer.println(encryptedPayload)
                    Log.d("dropp", "Encrypted command successfully sent to Windows ($ip:$COMMAND_PORT)!")
                }
            } catch (e: Exception) {
                Log.e("dropp", "Network Failed to send command to $ip:$COMMAND_PORT: ${e.message}", e)
            }
        }
    }
}