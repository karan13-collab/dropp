package com.dropp.dropp.services
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dropp.dropp.core.networking.NsdHelper
import android.content.pm.ServiceInfo

class DroppBackgroundService : Service() {
    private val CHANNEL_ID = "DroppForegroundServiceChannel"
    private val NOTIFICATION_ID = 1
    companion object {
        var clipboardManager: ClipboardSyncManager? = null
        var fileTransferManager: FileTransferManager? = null
    }

    private lateinit var nsdHelper: NsdHelper
    private lateinit var clipboardSyncManager: ClipboardSyncManager
    private lateinit var mediaObserver: MediaObserver
    private lateinit var fileTransferManager: FileTransferManager

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dropp_sync_2", // <--- CHANGED HERE
                "Dropp Ecosystem Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

// 2. Second, build the persistent notification using that exact channel ID
        val syncNotification = NotificationCompat.Builder(this, "dropp_sync_2") // <--- CHANGED HERE
            .setContentTitle("dropp")
            .setContentText("Listening for PC clipboard and files...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                syncNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, syncNotification)
        }

        // 1. Initialize and start the Clipboard Manager
        clipboardSyncManager = ClipboardSyncManager(this)
        clipboardManager = clipboardSyncManager
        clipboardSyncManager.start()

        // 2. Start the Media Observer to watch for screenshots/photos
        mediaObserver = MediaObserver(Handler(Looper.getMainLooper()))
        contentResolver.registerContentObserver(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
        // Initialize and start the File Transfer Manager
        fileTransferManager = FileTransferManager(this)
        Companion.fileTransferManager = this.fileTransferManager
        fileTransferManager.start()
        com.dropp.dropp.core.security.SecurityManager.init(this)
        com.dropp.dropp.core.networking.CommandReceiverService.startListening(this)
        // 3. Initialize Networking
        nsdHelper = NsdHelper(this)
        nsdHelper.startBroadcasting()

        // 4. Start discovering, and map the IP when found!
        nsdHelper.startDiscovering { ipAddress ->
            clipboardSyncManager.setTargetIp(ipAddress)
            fileTransferManager.setTargetIp(ipAddress)
            com.dropp.dropp.core.networking.CommandTransferManager.targetWindowsIp = ipAddress
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safely unregister the observer when the app stops
        contentResolver.unregisterContentObserver(mediaObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Ensures Android restarts it if it crashes
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Watches the OS for new photos and screenshots
    inner class MediaObserver(handler: Handler) : ContentObserver(handler) {
        private var lastImageId: Long = 0

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)

            try {
                // Safely query the Android MediaStore for the newest image
                val projection = arrayOf(
                    android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH
                )

                val cursor = contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                        val path = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH))?.lowercase() ?: ""

                        // Prevent double-firing for the same image
                        if (id == lastImageId) return
                        lastImageId = id

                        // Filter out downloaded web memes; only react to Screenshots and Camera photos
                        if (path.contains("screenshot") || path.contains("dcim")) {
                            Log.d("DroppService", "New photo/screenshot detected! Pushing to clipboard...")

                            val contentUri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                            // Background clipboard WRITES are still allowed in Android 10+
                            val clip = android.content.ClipData.newUri(contentResolver, "dropp auto-image", contentUri)
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(clip)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DroppService", "Error reading media: ${e.message}")
            }
        }
    }
}