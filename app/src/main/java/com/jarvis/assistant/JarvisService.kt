package com.jarvis.assistant

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Jarvis ka core: Chaquopy Python runtime start karta hai, jo andar hi
 * andar `android_start.start()` call karke Flask server ko
 * 127.0.0.1:5000 par background thread mein chalata hai — Render ya
 * Termux ki koi zaroorat nahi. Foreground service isliye taaki Android
 * background me isko na maare aur boot/network trigger se turant chalu
 * ho jaaye.
 */
class JarvisService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        startPython()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: system dwara kill hone par Android khud dobara start karega
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }
            val py = Python.getInstance()
            val module = py.getModule("android_start")
            module.callAttr("start", applicationContext)
        } catch (e: PyException) {
            // Python-side exception — service crash nahi hona chahiye,
            // sirf log karo. User ko WebView me connection error dikhega,
            // jisse pata chal jayega kuch galat hua.
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 101
    }
}
