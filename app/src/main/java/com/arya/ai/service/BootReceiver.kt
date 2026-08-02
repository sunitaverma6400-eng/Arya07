package com.arya.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.arya.ai.util.PreferencesManager

/** Re-starts [WakeWordService] after a reboot, but only if the user had left it turned on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (PreferencesManager(context).wakeWordEnabled) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }
    }
}
