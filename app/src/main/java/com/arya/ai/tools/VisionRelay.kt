package com.arya.ai.tools

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Arya's vision (camera/screen/attached-photo understanding) — used by both
 * [com.arya.ai.service.WakeWordService]'s live-conversation loop and [ChatScreen][com.arya.ai.ui.ChatScreen]'s
 * image-attach button. Calls Arya Relay's `/v1/relay` with an attached image (Gemini's vision —
 * see arya-relay/app.py's `_call_gemini`). Returns null on any failure/no-relay; caller should
 * fall back to a plain text-only answer in that case.
 */
object VisionRelay {

    private fun relayBase(): String? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        return relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
    }

    val isAvailable: Boolean get() = relayBase() != null

    fun describeImage(bitmap: Bitmap, question: String): String? {
        val base = relayBase() ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val imageBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            val body = org.json.JSONObject().apply {
                put("provider", "gemini")
                put("model", "gemini-2.0-flash")
                put("prompt", question)
                put("image_base64", imageBase64)
                put("image_mime", "image/jpeg")
                put("systemPrompt", "Tum Arya ho, ek voice assistant jo camera/screen dekh ke bata rahi hai. Chhota, bolne-jaisa jawab do.")
            }
            val connection = (URL("$base/v1/relay").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 25_000
                readTimeout = 25_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
            json.optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
