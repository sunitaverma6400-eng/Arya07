package com.arya.ai.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * The original assistant's `tools.py` device-control functions (make_call, send_sms, vibrate, torch,
 * battery, notifications, set_alarm, get_location, ...) all shelled out to
 * `termux-*` CLI commands. There's no Termux here — every one of these is reimplemented
 * with a plain Android API call or a standard Intent instead.
 *
 * Anything that would need a dangerous runtime permission (CALL_PHONE, SEND_SMS) opens
 * the relevant system app pre-filled instead of acting silently, same safety stance as
 * [com.arya.ai.data.DeviceActions].
 */
object DeviceExtraTools {

    fun vibrate(context: Context, durationMs: Long): String = try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(50, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
        "📳 Vibrate kar diya (${durationMs}ms)"
    } catch (e: Exception) {
        "❌ Vibrate nahi ho paaya: ${e.message}"
    }

    fun getBatteryStatus(context: Context): String = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        "🔋 Battery: $level% (${if (charging) "charging" else "not charging"})"
    } catch (e: Exception) {
        "❌ Battery status nahi mil paaya: ${e.message}"
    }

    fun sendNotification(context: Context, title: String, content: String): String {
        return try {
            val channelId = "arya_tool_notifications"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Arya notifications", NotificationManager.IMPORTANCE_DEFAULT)
                manager.createNotificationChannel(channel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return "❌ Notification permission nahi di gayi — Settings me se allow karo"
            }
            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            manager.notify(System.currentTimeMillis().toInt(), notification)
            "🔔 Notification bhej di: \"$title\""
        } catch (e: Exception) {
            "❌ Notification bhejne me error: ${e.message}"
        }
    }

    /** Opens the clock app's "set alarm" screen pre-filled — no special permission needed. */
    fun setAlarm(context: Context, hour: Int, minute: Int, message: String): String = try {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        "⏰ Alarm screen khol di: $hour:$minute — \"$message\""
    } catch (e: Exception) {
        "❌ Alarm set nahi ho paaya: ${e.message}"
    }

    /** Opens the dialer pre-filled instead of calling directly — avoids needing CALL_PHONE. */
    fun makeCall(context: Context, number: String): String = try {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        "📞 Dialer $number ke saath khol diya"
    } catch (e: Exception) {
        "❌ Dialer nahi khul paaya: ${e.message}"
    }

    /** Opens the messaging app pre-filled instead of sending directly — avoids needing SEND_SMS. */
    fun sendSms(context: Context, number: String, message: String): String = try {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        "💬 Messaging app $number ke saath khol di"
    } catch (e: Exception) {
        "❌ Messages app nahi khul paayi: ${e.message}"
    }

    fun openApp(context: Context, packageName: String): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "❌ '$packageName' install nahi hai"
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            "📱 $packageName khol diya"
        } catch (e: Exception) {
            "❌ App khulne me error: ${e.message}"
        }
    }

    /**
     * Opens an installed app by its display name ("WhatsApp", "Instagram") instead of the
     * exact package name — matches against every launchable app's label. Querying the
     * MAIN/LAUNCHER intent this way doesn't need the QUERY_ALL_PACKAGES permission: Android
     * exempts apps that respond to that specific intent from its normal per-app visibility
     * restrictions, since it's how every home screen/launcher lists installed apps.
     */
    fun openAppByName(context: Context, appName: String): String {
        if (appName.isBlank()) return "❌ App ka naam batao"
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            return "❌ Installed apps nahi padh paaya: ${e.message}"
        }
        val query = appName.trim().lowercase()
        val match = installed.firstOrNull { it.loadLabel(pm).toString().lowercase() == query }
            ?: installed.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(query) }
            ?: return "❌ \"$appName\" naam ka koi installed app nahi mila"
        val label = match.loadLabel(pm).toString()
        return try {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                ?: return "❌ '$label' launch nahi ho paaya"
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launch)
            "📱 $label khol diya"
        } catch (e: Exception) {
            "❌ '$label' khulne me error: ${e.message}"
        }
    }

    // ---- Quick-settings screens (no toggle permission needed — just opens the panel) ----

    /** Opens the WiFi quick-toggle panel (Android 10+) or the full WiFi settings screen otherwise. */
    fun openWifiSettings(context: Context): String = try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
        "📶 WiFi settings khol di"
    } catch (e: Exception) {
        "❌ WiFi settings nahi khul payi: ${e.message}"
    }

    fun openBluetoothSettings(context: Context): String = try {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        "🔵 Bluetooth settings khol di"
    } catch (e: Exception) {
        "❌ Bluetooth settings nahi khul payi: ${e.message}"
    }

    fun openDndSettings(context: Context): String = try {
        val intent = Intent("android.settings.ZEN_MODE_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        "🌙 Do Not Disturb settings khol di"
    } catch (e: Exception) {
        "❌ DND settings nahi khul payi: ${e.message}"
    }

    // ---- Volume / media control (standard AudioManager APIs, no special permission) ----

    fun adjustVolume(context: Context, up: Boolean): String = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        if (up) "🔊 Volume badha diya" else "🔉 Volume kam kar diya"
    } catch (e: Exception) {
        "❌ Volume adjust nahi ho paaya: ${e.message}"
    }

    /** Sends a media key event system-wide — controls whatever app currently holds the active media session. */
    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun mediaPlayPause(context: Context): String = try {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        "⏯️ Play/Pause bhej diya"
    } catch (e: Exception) {
        "❌ Media control nahi ho paaya: ${e.message}"
    }

    fun mediaNext(context: Context): String = try {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        "⏭️ Next track pe chala gaya"
    } catch (e: Exception) {
        "❌ Media control nahi ho paaya: ${e.message}"
    }

    fun mediaPrevious(context: Context): String = try {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "⏮️ Previous track pe chala gaya"
    } catch (e: Exception) {
        "❌ Media control nahi ho paaya: ${e.message}"
    }

    /** Reads the last known location from whichever provider is enabled. Needs location permission granted at runtime. */
    fun getLocation(context: Context): String {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return "❌ Location permission nahi di gayi — Settings me se allow karo"
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            val location = providers.asSequence()
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?: return "❌ Abhi tak koi cached location nahi — GPS/network location on karke ek baar Maps kholo"
            val readable = InfoApiTools.reverseGeocode(context, location.latitude, location.longitude)
            "📍 lat ${location.latitude}, lon ${location.longitude} (accuracy ~${location.accuracy.toInt()}m)\n$readable"
        } catch (e: Exception) {
            "❌ Location nahi mil paayi: ${e.message}"
        }
    }
}
