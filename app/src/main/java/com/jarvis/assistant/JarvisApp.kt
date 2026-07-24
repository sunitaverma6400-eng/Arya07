package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Jarvis Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Jarvis background service ka persistent status"
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Jarvis Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Jarvis ke AI-triggered notifications (reminders, alerts)"
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(alertsChannel)
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "jarvis_service"
        const val CHANNEL_ALERTS = "jarvis_alerts"
    }
}
