package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Phone boot hote hi Jarvis service khud chalu ho jaata hai — koi manual
 * app-open ki zaroorat nahi.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, JarvisService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
