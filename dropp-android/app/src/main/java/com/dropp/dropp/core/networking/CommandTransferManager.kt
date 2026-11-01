package com.dropp.dropp.core.networking

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.dropp.dropp.core.security.EncryptionHelper
import com.dropp.dropp.core.security.SecurityManager
import com.dropp.dropp.services.DroppBackgroundService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object CommandTransferManager {
    private const val TAG = "CommandTransferManager"
    private const val COMMAND_PORT = 8767
    private const val CONNECT_TIMEOUT_MS = 2500

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

    suspend fun sendCommandSync(context: Context, jsonPayload: String): Boolean = withContext(Dispatchers.IO) {
        val masterKey = SecurityManager.getKey(context)
        if (masterKey.isEmpty()) {
            Log.e(TAG, "Cannot send command: MasterKey is not set! Please pair via QR code first.")
            return@withContext false
        }

        // Build candidate IP list in order of priority
        val candidates = mutableListOf<String>()
        _targetWindowsIp.value?.let { if (it.isNotBlank()) candidates.add(it) }

        val savedIp = context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
            .getString("last_windows_ip", null)
        if (!savedIp.isNullOrBlank()) {
            candidates.add(savedIp)
        }

        // Hotspot clients from ARP table
        val arpClients = getArpCandidates()
        candidates.addAll(arpClients)

        // Gateway IP (if Wi-Fi client mode)
        getDefaultGateway(context)?.let { candidates.add(it) }

        val uniqueCandidates = candidates.distinct().filter { it.isNotBlank() }
        if (uniqueCandidates.isEmpty()) {
            Log.e(TAG, "Cannot send command: No candidate Windows IP addresses discovered!")
            return@withContext false
        }

        for (candidate in uniqueCandidates) {
            try {
                Log.d(TAG, "Attempting connection to Windows candidate at $candidate:$COMMAND_PORT...")
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(candidate, COMMAND_PORT), CONNECT_TIMEOUT_MS)
                    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                    val encryptedPayload = EncryptionHelper.encrypt(jsonPayload, masterKey)
                    writer.println(encryptedPayload)
                    writer.flush()
                }

                Log.d(TAG, "Encrypted command successfully sent to Windows at $candidate:$COMMAND_PORT!")

                // Auto-learn and persist the successful IP
                if (_targetWindowsIp.value != candidate) {
                    _targetWindowsIp.value = candidate
                    context.getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE)
                        .edit().putString("last_windows_ip", candidate).apply()
                    DroppBackgroundService.fileTransferManager?.setTargetIp(candidate)
                    DroppBackgroundService.clipboardManager?.setTargetIp(candidate)
                    Log.i(TAG, "Learned and persisted active Windows IP: $candidate")
                }

                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to candidate $candidate:$COMMAND_PORT: ${e.message}")
            }
        }

        Log.e(TAG, "All Windows candidate IPs failed: $uniqueCandidates")
        return@withContext false
    }

    fun sendCommand(context: Context, jsonPayload: String) {
        val handler = CoroutineExceptionHandler { _, exception ->
            Log.e(TAG, "FATAL Coroutine Crash in CommandTransferManager: ${exception.message}", exception)
        }

        CoroutineScope(Dispatchers.IO + handler).launch {
            sendCommandSync(context, jsonPayload)
        }
    }

    fun getArpCandidates(): List<String> {
        val list = mutableListOf<String>()
        try {
            val arp = File("/proc/net/arp")
            if (arp.exists() && arp.canRead()) {
                arp.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[0] != "IP" && parts[0] != "IP address") {
                        val ip = parts[0]
                        val flags = parts[2]
                        val mac = parts[3]
                        if ((flags == "0x2" || flags == "2") && mac != "00:00:00:00:00:00") {
                            list.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing /proc/net/arp: ${e.message}")
        }
        return list
    }

    fun getDefaultGateway(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo ?: return null
            val gatewayInt = dhcp.gateway
            if (gatewayInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    gatewayInt and 0xff,
                    gatewayInt shr 8 and 0xff,
                    gatewayInt shr 16 and 0xff,
                    gatewayInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}
        return null
    }
}