package com.arya.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text-to-image via Arya Relay's `/v1/imagegen` (Gemini's image-generation model — see
 * arya-relay/app.py). No offline/keyless fallback exists for this one, unlike Arya's other
 * tools — there's no free local image-generation model bundled with the app, so this is
 * cloud-only; callers should show a clear error if [generate] returns null.
 */
object ImageGenTools {

    val isAvailable: Boolean get() = com.arya.ai.BuildConfig.RELAY_URL.isNotBlank()

    /** Generates an image for [prompt]. Returns null on any failure/no-relay-configured. */
    suspend fun generate(prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return@withContext null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        try {
            val body = org.json.JSONObject().apply { put("prompt", prompt) }
            val connection = (URL("$base/v1/imagegen").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 40_000
                readTimeout = 40_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.readBytes()
            if (bytes.isEmpty()) return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves [bitmap] into the app's Pictures folder (via [Context.getExternalFilesDir], no
     * extra storage permission needed on API 26+) — used by the voice `generate_image` tool
     * since [com.arya.ai.service.WakeWordService] can only speak a confirmation back, not show
     * the image itself.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): File? {
        return try {
            val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Arya")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "arya_${System.currentTimeMillis()}.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file
        } catch (e: Exception) {
            null
        }
    }
}
