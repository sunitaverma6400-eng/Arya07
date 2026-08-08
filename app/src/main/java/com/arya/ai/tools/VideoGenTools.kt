package com.arya.ai.tools

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text-to-video via Arya Relay's `/v1/videogen` (FIXES_LOG.md Phase 26) — Veo 3.1, called
 * through the plain Gemini API on the relay side (`GEMINI_KEYS`, same keys as everything else,
 * no separate Vertex AI/billing project needed — this was verified against Google's current
 * docs, not assumed). It genuinely can take a couple of minutes; [generate]'s read timeout is
 * set accordingly. A null return means either "relay not configured" or "Gemini free-tier
 * quota for Veo ran out" — the relay's error detail isn't surfaced here, check its logs.
 */
object VideoGenTools {

    val isAvailable: Boolean get() = com.arya.ai.BuildConfig.RELAY_URL.isNotBlank()

    /** Downloads the generated video into local cache and returns a `content://` URI (via
     *  [androidx.core.content.FileProvider]) that [com.arya.ai.ui.VideoPlayerDialog]'s ExoPlayer
     *  can play directly — same pattern as [ImageGenTools.saveToGallery] but returning a URI
     *  instead of a File since the caller needs something playable, not just a saved path.
     *  Null on any failure. */
    suspend fun generate(context: Context, prompt: String): String? = withContext(Dispatchers.IO) {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return@withContext null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        try {
            val body = org.json.JSONObject().apply { put("prompt", prompt) }
            val connection = (URL("$base/v1/videogen").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                // Veo generation + polling on the relay side can take a few minutes at peak —
                // see arya-relay/app.py's own ~4.5 minute poll budget. Give it more room than
                // that so the app doesn't give up before the relay does.
                connectTimeout = 30_000
                readTimeout = 5 * 60_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.readBytes()
            if (bytes.isEmpty()) return@withContext null

            val dir = File(context.cacheDir, "generated").apply { if (!exists()) mkdirs() }
            val file = File(dir, "arya_video_${System.currentTimeMillis()}.mp4")
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (e: Exception) {
            null
        }
    }
}
