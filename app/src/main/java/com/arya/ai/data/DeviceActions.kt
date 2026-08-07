package com.arya.ai.data

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.MediaStore

/**
 * Mobile Actions intentionally sticks to standard, permission-light Android APIs —
 * launching Intents and toggling the torch via the public Camera2 API. It does NOT
 * use an Accessibility Service or any other mechanism that could control other apps'
 * UI, since that's a much bigger trust/safety surface than a from-scratch sample app
 * should take on silently.
 */
object DeviceActions {

    fun openCamera(context: Context): String {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return launch(context, intent, "📷 Camera khol diya")
    }

    fun openDialer(context: Context, number: String?): String {
        val uri = if (number.isNullOrBlank()) Uri.parse("tel:") else Uri.parse("tel:$number")
        val intent = Intent(Intent.ACTION_DIAL, uri).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return launch(context, intent, "📞 Dialer khol diya")
    }

    fun openBrowser(context: Context, url: String?): String {
        val target = url?.ifBlank { null } ?: "https://www.google.com"
        val fixed = if (!target.startsWith("http")) "https://$target" else target
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return launch(context, intent, "🌐 Browser me $fixed khola")
    }

    fun openMaps(context: Context, query: String?): String {
        val q = query?.ifBlank { null } ?: "current location"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(context, intent, "🗺️ Maps me \"$q\" search kiya")
    }

    fun toggleFlashlight(context: Context, on: Boolean): String {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull() ?: return "❌ Flashlight-capable camera nahi mila"
            manager.setTorchMode(cameraId, on)
            if (on) "🔦 Flashlight ON kar di" else "🔦 Flashlight OFF kar di"
        } catch (e: Exception) {
            "❌ Flashlight toggle nahi ho paayi: ${e.message}"
        }
    }

    private fun launch(context: Context, intent: Intent, successMessage: String): String {
        return try {
            context.startActivity(intent)
            successMessage
        } catch (e: Exception) {
            "❌ Action fail hua: ${e.message}"
        }
    }
}
