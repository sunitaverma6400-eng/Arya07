package com.arya.ai.tools

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text-to-music via Arya Relay's `/v1/musicgen` (FIXES_LOG.md Phase 26) — Lyria 3 Clip, same
 * `GEMINI_KEYS` as everything else, no extra setup. Unlike [VideoGenTools] this is a single
 * synchronous call (no polling) and always a 30-second clip. Null return means either "relay
 * not configured" or "Gemini free-tier quota ran out" — check relay logs for which.
 */
object MusicGenTools {

    val isAvailable: Boolean get() = com.arya.ai.BuildConfig.RELAY_URL.isNotBlank()

    /** Downloads the generated clip into local cache and returns a `content://` URI (via
     *  [androidx.core.content.FileProvider]) — [com.arya.ai.player.StreamPlayerManager]'s
     *  ExoPlayer plays `content://` URIs the same as any stream URL, so this plugs straight
     *  into the existing [com.arya.ai.tools.StreamTools.playStream] path used for radio. */
    suspend fun generate(context: Context, prompt: String): String? = withContext(Dispatchers.IO) {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return@withContext null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        try {
            val body = org.json.JSONObject().apply { put("prompt", prompt) }
            val connection = (URL("$base/v1/musicgen").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 30_000
                readTimeout = 90_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.readBytes()
            if (bytes.isEmpty()) return@withContext null

            val dir = File(context.cacheDir, "generated").apply { if (!exists()) mkdirs() }
            val file = File(dir, "arya_music_${System.currentTimeMillis()}.mp3")
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (e: Exception) {
            null
        }
    }
}
