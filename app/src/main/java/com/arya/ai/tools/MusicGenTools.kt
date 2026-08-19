package com.arya.ai.tools

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text-to-music via Arya Relay's `/v1/musicgen` using Gemini's current Lyria 3 API.
 * The relay owns Gemini keys; Android receives only audio bytes. The default is the 30-second
 * Lyria 3 Clip model, while the relay can also accept the Pro model for longer songs.
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
                setRequestProperty("X-Client-Id", com.arya.ai.util.PreferencesManager(context).relayClientId)
                connectTimeout = 30_000
                readTimeout = 180_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return@withContext null
            val contentType = connection.contentType?.lowercase().orEmpty()
            if (contentType.isNotBlank() && !contentType.startsWith("audio/")) return@withContext null
            val maxBytes = 20L * 1024L * 1024L
            val declared = connection.contentLengthLong
            if (declared > maxBytes) return@withContext null
            val dir = File(context.cacheDir, "generated").apply { if (!exists()) mkdirs() }
            val file = File(dir, "arya_music_${System.currentTimeMillis()}.mp3")
            try {
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IllegalStateException("audio_too_large")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (file.length() == 0L) { file.delete(); return@withContext null }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            } catch (_: Exception) {
                file.delete()
                null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
