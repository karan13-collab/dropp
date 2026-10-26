package com.dropp.dropp.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object AudioRoutingManager {
    private const val TAG = "AudioRoutingManager"

    private val _isRoutingToPc = MutableStateFlow(false)
    val isRoutingToPc = _isRoutingToPc.asStateFlow()

    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null
    @Volatile
    private var isProxyBinding = false

    fun init(context: Context) {
        initA2dpProxy(context.applicationContext)
    }

    private fun initA2dpProxy(context: Context) {
        if (a2dpProxy != null || isProxyBinding) return
        isProxyBinding = true

        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return

            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = proxy as? BluetoothA2dp
                        isProxyBinding = false
                        Log.i(TAG, "BluetoothA2dp proxy connected successfully.")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = null
                        isProxyBinding = false
                        Log.i(TAG, "BluetoothA2dp proxy disconnected.")
                    }
                }
            }, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            isProxyBinding = false
            Log.e(TAG, "Error initializing A2DP proxy: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun findWindowsDevice(context: Context): BluetoothDevice? {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return null
            val bonded = adapter.bondedDevices ?: return null

            Log.i(TAG, "Scanning ${bonded.size} bonded devices for Windows PC...")
            for (device in bonded) {
                val name = device.name ?: ""
                val address = device.address ?: ""
                val devClass = device.bluetoothClass?.deviceClass ?: 0
                Log.d(TAG, "Bonded device: name='$name', address='$address', devClass=0x${Integer.toHexString(devClass)}")

                if (address.equals("80:B6:55:DA:46:47", ignoreCase = true) ||
                    name.contains("SHASHIKANT", ignoreCase = true) ||
                    name.contains("Windows", ignoreCase = true) ||
                    devClass == BluetoothClass.Device.COMPUTER_DESKTOP ||
                    devClass == BluetoothClass.Device.COMPUTER_LAPTOP
                ) {
                    Log.i(TAG, "Matched Windows PC Bluetooth device: $name ($address)")
                    return device
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning bonded devices: ${e.message}", e)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun connectA2dp(context: Context): Boolean {
        init(context)
        val device = findWindowsDevice(context)
        if (device == null) {
            Log.w(TAG, "Cannot connect A2DP: No paired Windows PC found!")
            return false
        }

        // Method 1: BluetoothA2dp.connect(device) using reflection on BluetoothA2dp class
        val proxy = a2dpProxy
        if (proxy != null) {
            try {
                val method = BluetoothA2dp::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device)
                Log.i(TAG, "BluetoothA2dp.connect() result: $res")
                return res as? Boolean ?: true
            } catch (e: Exception) {
                Log.d(TAG, "BluetoothA2dp.connect() reflection: ${e.message}")
            }
        }

        // Method 2: BluetoothDevice.connect() (Android 14+ / 15 / 16)
        try {
            val connectMethod = BluetoothDevice::class.java.getDeclaredMethod("connect")
            connectMethod.isAccessible = true
            val res = connectMethod.invoke(device)
            Log.i(TAG, "BluetoothDevice.connect() result: $res")
            if (res == true || (res is Int && res == 0)) {
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothDevice.connect() reflection: ${e.message}")
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun disconnectA2dp(context: Context): Boolean {
        val device = findWindowsDevice(context)
        if (device == null) {
            Log.w(TAG, "Cannot disconnect A2DP: No paired Windows PC found!")
            return false
        }

        val proxy = a2dpProxy
        if (proxy != null) {
            try {
                val method = BluetoothA2dp::class.java.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device)
                Log.i(TAG, "BluetoothA2dp.disconnect() result: $res")
                return res as? Boolean ?: true
            } catch (e: Exception) {
                Log.d(TAG, "BluetoothA2dp.disconnect() reflection: ${e.message}")
            }
        }

        try {
            val disconnectMethod = BluetoothDevice::class.java.getDeclaredMethod("disconnect")
            disconnectMethod.isAccessible = true
            val res = disconnectMethod.invoke(device)
            Log.i(TAG, "BluetoothDevice.disconnect() result: $res")
            if (res == true || (res is Int && res == 0)) {
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothDevice.disconnect() reflection: ${e.message}")
        }

        return false
    }

    fun setRoutingState(enabled: Boolean) {
        _isRoutingToPc.value = enabled
    }

    fun enableAudioRouting(context: Context) {
        init(context)
        CoroutineScope(Dispatchers.IO).launch {
            // First notify Windows to open / arm the audio sink
            val payload = JSONObject().apply {
                put("type", "AUDIO_ROUTING")
                put("action", "ENABLE")
            }.toString()
            CommandTransferManager.sendCommand(context, payload)

            // Connect Bluetooth A2DP directly from Android to Windows PC
            connectA2dp(context)
        }
    }

    fun disableAudioRouting(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            // Notify Windows to close the audio sink (restores audio to phone speakers)
            val payload = JSONObject().apply {
                put("type", "AUDIO_ROUTING")
                put("action", "DISABLE")
            }.toString()
            CommandTransferManager.sendCommand(context, payload)

            _isRoutingToPc.value = false
        }
    }

    fun toggle(context: Context) {
        if (_isRoutingToPc.value) {
            disableAudioRouting(context)
        } else {
            enableAudioRouting(context)
        }
    }

    fun queryStatus(context: Context) {
        init(context)
        val payload = JSONObject().apply {
            put("type", "AUDIO_ROUTING")
            put("action", "QUERY")
        }.toString()
        CommandTransferManager.sendCommand(context, payload)
    }
}
