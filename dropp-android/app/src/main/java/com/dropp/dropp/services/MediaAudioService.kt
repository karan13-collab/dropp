package com.dropp.dropp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dropp.dropp.MainActivity
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaAudioService : Service() {

    companion object {
        private const val TAG = "MediaAudioService"
        const val ACTION_START = "com.dropp.dropp.action.START_MEDIA_AUDIO"
        const val ACTION_STOP = "com.dropp.dropp.action.STOP_MEDIA_AUDIO"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_IP = "extra_target_ip"

        private const val NOTIFICATION_ID = 4040
        private const val CHANNEL_ID = "dropp_media_audio"
        private const val AUDIO_PORT = 5556
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2 // Stereo
        private const val BITS_PER_SAMPLE = 16
        // 20ms of 48kHz 16-bit stereo: 48000 * 0.020 = 960 frames * 2 channels * 2 bytes = 3840 bytes
        private const val FRAME_SIZE_BYTES = 3840

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming = _isStreaming.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent, targetIp: String? = null) {
            val intent = Intent(context, MediaAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                targetIp?.let { putExtra(EXTRA_TARGET_IP, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaAudioService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var streamingJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var socket: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP")
                stopStreaming()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val targetIp = intent.getStringExtra(EXTRA_TARGET_IP)

                if (resultCode == 0 || resultData == null) {
                    Log.e(TAG, "Invalid MediaProjection credentials received")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // 1. Start foreground service FIRST (Required before getMediaProjection on Android 14+)
                val notification = buildForegroundNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                // 2. Acquire WakeLock
                acquireWakeLock()

                // 3. Obtain MediaProjection instance
                try {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    if (projection == null) {
                        Log.e(TAG, "getMediaProjection returned null")
                        stopStreaming()
                        return START_NOT_STICKY
                    }
                    mediaProjection = projection

                    // Register projection callback
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.i(TAG, "MediaProjection session stopped by system")
                            stopStreaming()
                        }
                    }, Handler(Looper.getMainLooper()))

                    // 4. Start audio capture and TCP streaming
                    startAudioCaptureAndStreaming(projection, targetIp)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}", e)
                    stopStreaming()
                }

                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startAudioCaptureAndStreaming(projection: MediaProjection, targetIp: String?) {
        streamingJob?.cancel()
        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolvedIp = targetIp
                    ?: CommandTransferManager.targetWindowsIp
                    ?: getSharedPreferences("dropp_prefs", Context.MODE_PRIVATE).getString("last_windows_ip", null)

                if (resolvedIp.isNullOrBlank()) {
                    Log.e(TAG, "No Windows target IP discovered")
                    stopStreaming()
                    return@launch
                }

                Log.i(TAG, "Configuring AudioPlaybackCapture for $resolvedIp:$AUDIO_PORT...")

                // 1. Configure Android 10+ AudioPlaybackCapture
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Log.e(TAG, "AudioPlaybackCapture requires Android 10 (API 29) or higher")
                    stopStreaming()
                    return@launch
                }

                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBufferSize, FRAME_SIZE_BYTES * 4)

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    stopStreaming()
                    return@launch
                }
                audioRecord = record

                // 2. Connect TCP socket to Windows PC
                Log.d(TAG, "Connecting to Windows audio socket at $resolvedIp:$AUDIO_PORT...")
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.soTimeout = 0
                sock.connect(InetSocketAddress(resolvedIp, AUDIO_PORT), 5000)
                socket = sock
                Log.i(TAG, "Connected to Windows PC audio socket successfully!")

                val outputStream = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 8192))

                // 3. Send 13-Byte Handshake Header ('D', 'P', 'A', 'U')
                val handshake = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).apply {
                    put('D'.code.toByte())
                    put('P'.code.toByte())
                    put('A'.code.toByte())
                    put('U'.code.toByte())
                    putInt(SAMPLE_RATE)
                    putShort(CHANNELS.toShort())
                    putShort(BITS_PER_SAMPLE.toShort())
                    put(0.toByte()) // 0 = Media Streaming
                }.array()

                outputStream.write(handshake)
                outputStream.flush()
                Log.i(TAG, "Handshake sent: 48kHz Stereo Media Stream")

                // 4. Start recording and streaming audio frames
                record.startRecording()
                _isStreaming.value = true
                Log.i(TAG, "AudioRecord started: Streaming media audio to Windows PC...")

                val readBuffer = ByteArray(FRAME_SIZE_BYTES)
                val frameHeader = ByteArray(4)
                frameHeader[0] = 'D'.code.toByte()
                frameHeader[1] = 'P'.code.toByte()

                while (isActive && _isStreaming.value) {
                    val bytesRead = record.read(readBuffer, 0, FRAME_SIZE_BYTES)
                    if (bytesRead > 0) {
                        frameHeader[2] = ((bytesRead shr 8) and 0xFF).toByte()
                        frameHeader[3] = (bytesRead and 0xFF).toByte()

                        outputStream.write(frameHeader, 0, 4)
                        outputStream.write(readBuffer, 0, bytesRead)
                        outputStream.flush()
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming session error: ${e.message}", e)
            } finally {
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        _isStreaming.value = false

        try {
            streamingJob?.cancel()
            streamingJob = null
        } catch (e: Exception) { }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { }
        audioRecord = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) { }
        mediaProjection = null

        try {
            socket?.close()
        } catch (e: Exception) { }
        socket = null

        releaseWakeLock()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "MediaAudioService stopped and resources cleaned up.")
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dropp:MediaAudioStream").apply {
                acquire(3 * 60 * 60 * 1000L) // 3 hours max timeout
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) { }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dropp Audio Cast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used while streaming device media audio to Windows PC"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaAudioService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dropp Audio Cast")
            .setContentText("Routing phone audio to Windows PC speakers")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
