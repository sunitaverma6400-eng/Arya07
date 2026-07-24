package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat

/**
 * "Net on karte hi Jarvis chalu ho jaaye" — yehi requirement isko poora
 * karta hai. Har connectivity-change par check karta hai ki actual
 * network available hai ya nahi, tabhi service start karta hai (idempotent
 * hai — agar already chal raha ho to android_start.py khud "already_running"
 * bol ke return kar deta hai).
 */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetworkInfo
        val isConnected = activeNetwork?.isConnectedOrConnecting == true
        if (isConnected) {
            val serviceIntent = Intent(context, JarvisService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
