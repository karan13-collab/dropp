package com.dropp.dropp.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dropp.dropp.core.networking.NsdHelper

class DroppBackgroundService : Service() {
    private lateinit var nsdHelper: NsdHelper

    override fun onCreate() {
        super.onCreate()

        // Android 8+ requires a Notification Channel for Foreground Services
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dropp_sync",
                "Dropp Ecosystem Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Create the persistent notification
        val notification = NotificationCompat.Builder(this, "dropp_sync")
            .setContentTitle("dropp")
            .setContentText("Looking for nearby devices...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        // Start the service in the foreground so the OS doesn't kill it
        startForeground(1, notification)

        // Initialize and start our mDNS networking
        nsdHelper = NsdHelper(this)
        nsdHelper.startBroadcasting()
        nsdHelper.startDiscovering()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Restart automatically if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null
}