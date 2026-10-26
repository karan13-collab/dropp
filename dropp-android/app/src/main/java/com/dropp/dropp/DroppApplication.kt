package com.dropp.dropp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class DroppApplication : Application() {
    companion object {
        @Volatile
        var appContext: Application? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        com.dropp.dropp.core.security.SecurityManager.init(this)
        createNotificationChannels()
        try {
            val intent = android.content.Intent(this, com.dropp.dropp.services.DroppBackgroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w("DroppApplication", "Could not start DroppBackgroundService: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dropp_service_channel",
                "Dropp Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for Dropp background service"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d("DroppApplication", "Notification channel created")
        }
    }
}
