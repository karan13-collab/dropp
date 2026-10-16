package com.dropp.dropp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class DroppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.dropp.dropp.core.security.SecurityManager.init(this)
        createNotificationChannels()
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
