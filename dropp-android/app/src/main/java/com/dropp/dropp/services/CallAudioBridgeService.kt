package com.dropp.dropp.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Routes active cellular call audio through Bluetooth SCO (HFP) to the paired
 * Windows PC, so the PC speakers play the remote caller's voice and the PC mic
 * feeds the user's voice back into the call.
 *
 * This replaces the old TCP-based audio bridge: Bluetooth HFP natively carries
 * full-duplex voice audio without any custom streaming code.
 */
object CallAudioBridgeService {
    private const val TAG = "CallAudioBridge"

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    @Volatile
    private var previousSpeakerphoneOn: Boolean = false
    @Volatile
    private var previousBluetoothScoOn: Boolean = false

    private var headsetProxy: BluetoothHeadset? = null
    @Volatile
    private var isProxyReady = false

    private var scoReceiver: BroadcastReceiver? = null

    /**
     * Initialise the BluetoothHeadset (HFP) profile proxy so we can query connection state.
     */
    fun initHeadsetProxy(context: Context) {
        if (headsetProxy != null || isProxyReady) return

        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return

            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = proxy as? BluetoothHeadset
                        isProxyReady = true
                        Log.i(TAG, "BluetoothHeadset (HFP) proxy connected.")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = null
                        isProxyReady = false
                        Log.i(TAG, "BluetoothHeadset (HFP) proxy disconnected.")
                    }
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HFP proxy: ${e.message}", e)
        }
    }

    /**
     * Connect the BluetoothHeadset (HFP) profile to the paired Windows PC.
     */
    @SuppressLint("MissingPermission")
    fun connectHfp(context: Context): Boolean {
        initHeadsetProxy(context)
        val device = AudioRoutingManager.findWindowsDevice(context)
        if (device == null) {
            Log.w(TAG, "Cannot connect HFP: No paired Windows PC found!")
            return false
        }

        Log.i(TAG, "Attempting to connect BluetoothHeadset (HFP) to Windows: ${device.name} (${device.address})...")

        val proxy = headsetProxy
        if (proxy != null) {
            try {
                val state = proxy.getConnectionState(device)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "BluetoothHeadset already connected to ${device.name}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking HFP state: ${e.message}")
            }

            // Ensure connection policy is allowed
            try {
                val policyMethod = BluetoothHeadset::class.java.getDeclaredMethod(
                    "setConnectionPolicy",
                    android.bluetooth.BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                )
                policyMethod.isAccessible = true
                policyMethod.invoke(proxy, device, 100) // CONNECTION_POLICY_ALLOWED
                Log.d(TAG, "Set HFP connection policy to ALLOWED (100)")
            } catch (_: Exception) {
                try {
                    val priorityMethod = BluetoothHeadset::class.java.getDeclaredMethod(
                        "setPriority",
                        android.bluetooth.BluetoothDevice::class.java,
                        Int::class.javaPrimitiveType
                    )
                    priorityMethod.isAccessible = true
                    priorityMethod.invoke(proxy, device, 100) // PRIORITY_AUTO_CONNECT
                } catch (_: Exception) {}
            }

            // Invoke connect(device) on BluetoothHeadset
            try {
                val connectMethod = BluetoothHeadset::class.java.getDeclaredMethod(
                    "connect",
                    android.bluetooth.BluetoothDevice::class.java
                )
                connectMethod.isAccessible = true
                val res = connectMethod.invoke(proxy, device)
                Log.i(TAG, "BluetoothHeadset.connect() invoked on proxy, result: $res")
                if (res == true) return true
            } catch (e: Exception) {
                Log.e(TAG, "BluetoothHeadset.connect error: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "headsetProxy is null, deferring connection to onServiceConnected.")
        }

        // Method 2: BluetoothDevice.connect() (Android 14+ / 15 / 16)
        try {
            val devConnect = android.bluetooth.BluetoothDevice::class.java.getDeclaredMethod("connect")
            devConnect.isAccessible = true
            val res = devConnect.invoke(device)
            Log.i(TAG, "BluetoothDevice.connect() result: $res")
            if (res == true || (res is Int && res == 0)) return true
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothDevice.connect() fallback: ${e.message}")
        }

        return false
    }

    /**
     * Start routing call audio to the Bluetooth SCO channel (Windows PC).
     * Call this after the cellular call transitions to ACTIVE (or immediately when user
     * clicks "Answer on PC" from Windows).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startAudioBridge(context: Context, @Suppress("UNUSED_PARAMETER") targetIp: String = "") {
        if (isRunning) {
            Log.d(TAG, "Audio bridge already running.")
            return
        }

        isRunning = true
        Log.i(TAG, "Starting Call Audio Bridge via Bluetooth SCO (HFP)...")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Save current audio state so we can restore later
        previousAudioMode = audioManager.mode
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        @Suppress("DEPRECATION")
        previousBluetoothScoOn = audioManager.isBluetoothScoOn

        // Register a receiver to track SCO connection state
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (state) {
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO audio CONNECTED — call audio now routed to PC.")
                    }
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO audio DISCONNECTED.")
                    }
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        Log.d(TAG, "Bluetooth SCO audio CONNECTING...")
                    }
                    @Suppress("DEPRECATION")
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.e(TAG, "Bluetooth SCO audio ERROR.")
                    }
                }
            }
        }
        scoReceiver = receiver

        @Suppress("DEPRECATION")
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.applicationContext.registerReceiver(receiver, filter)
        }

        // Switch AudioManager to call mode and route through Bluetooth SCO / HFP
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure HFP profile is actively connected
                connectHfp(context)

                // 1. Maintain or set proper mode for call
                if (audioManager.mode != AudioManager.MODE_IN_CALL) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }

                // 2. Disable speakerphone
                audioManager.isSpeakerphoneOn = false

                // 3. Modern Android 12+ (API 31+) route via setCommunicationDevice with retry
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    var targetDevice: AudioDeviceInfo? = null

                    for (attempt in 1..4) {
                        val commDevices = audioManager.availableCommunicationDevices
                        targetDevice = commDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_HEARING_AID
                        } ?: commDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }

                        if (targetDevice != null) break
                        Log.d(TAG, "Attempt $attempt: Waiting for Bluetooth communication device...")
                        connectHfp(context)
                        delay(600)
                    }

                    if (targetDevice != null) {
                        val success = audioManager.setCommunicationDevice(targetDevice)
                        Log.i(TAG, "setCommunicationDevice(${targetDevice.productName}, type=${targetDevice.type}) result: $success")
                    } else {
                        Log.w(TAG, "No Bluetooth communication device found in availableCommunicationDevices. Falling back to startBluetoothSco().")
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }

                Log.i(TAG, "Bluetooth call routing started. Call audio should route to paired PC.")

                // 4. Verify routing after a short delay
                delay(1500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val activeDevice = audioManager.communicationDevice
                    Log.i(TAG, "Active communication device: ${activeDevice?.productName} (type=${activeDevice?.type})")
                } else {
                    @Suppress("DEPRECATION")
                    if (audioManager.isBluetoothScoOn) {
                        Log.i(TAG, "Bluetooth SCO confirmed active after 1.5s.")
                    } else {
                        Log.w(TAG, "Bluetooth SCO does not appear active after 1.5s. Retrying...")
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Bluetooth SCO: ${e.message}", e)
            }
        }
    }

    /**
     * Stop routing call audio via Bluetooth SCO and restore previous audio state.
     */
    @Synchronized
    fun stopAudioBridge() {
        if (!isRunning) return
        Log.i(TAG, "Stopping Call Audio Bridge (Bluetooth SCO)...")
        isRunning = false

        try {
            // Unregister SCO state receiver
            scoReceiver?.let { receiver ->
                try {
                    // Use the application context since that's where we registered
                    val appContext = com.dropp.dropp.DroppApplication.appContext
                    appContext?.unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
            scoReceiver = null
        } catch (_: Exception) {}

        // Restore audio state using a fresh AudioManager reference
        try {
            val appContext = com.dropp.dropp.DroppApplication.appContext ?: return
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()

            audioManager.mode = previousAudioMode
            audioManager.isSpeakerphoneOn = previousSpeakerphoneOn

            Log.i(TAG, "Bluetooth call routing stopped. Audio state restored.")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring audio state: ${e.message}", e)
        }
    }

    /**
     * Check whether Bluetooth HFP (Headset profile) is currently connected to any device.
     */
    @SuppressLint("MissingPermission")
    fun isHfpConnected(): Boolean {
        val proxy = headsetProxy ?: return false
        return try {
            proxy.connectedDevices?.isNotEmpty() == true
        } catch (e: Exception) {
            Log.w(TAG, "Error checking HFP connected devices: ${e.message}")
            false
        }
    }
}
