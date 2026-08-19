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

    /**
     * Starts a Veo job, polls the relay job endpoint, then downloads the MP4. The relay
     * returns immediately from POST /v1/videogen, so Android is no longer tied to a
     * 4-6 minute HTTP request and can survive a provider/worker timeout.
     */
    suspend fun generate(context: Context, prompt: String): String? = withContext(Dispatchers.IO) {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return@withContext null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        val clientId = com.arya.ai.util.PreferencesManager(context).relayClientId
        try {
            val startBody = org.json.JSONObject().apply { put("prompt", prompt) }
            val start = openJsonConnection(
                url = "$base/v1/videogen",
                method = "POST",
                clientId = clientId,
                connectTimeoutMs = 30_000,
                readTimeoutMs = 45_000
            )
            start.outputStream.use { it.write(startBody.toString().toByteArray(Charsets.UTF_8)) }
            if (start.responseCode !in 200..299) return@withContext null
            val startJson = org.json.JSONObject(start.inputStream.bufferedReader().use { it.readText() })
            val jobId = startJson.optString("job_id").takeIf { it.isNotBlank() } ?: return@withContext null

            // Veo is genuinely long-running. Poll for up to 12 minutes, while each individual
            // HTTP request stays short so a dead relay cannot block the coroutine indefinitely.
            repeat(144) {
                kotlinx.coroutines.delay(5_000)
                val status = openJsonConnection(
                    url = "$base/v1/videogen/jobs/$jobId",
                    method = "GET",
                    clientId = clientId,
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 30_000
                )
                if (status.responseCode !in 200..299) {
                    // A transient relay/provider error should not kill a still-running Veo job.
                    // Keep polling for the bounded 12-minute window; authentication/other
                    // permanent errors still stop immediately.
                    if (status.responseCode == 408 || status.responseCode == 429 ||
                        status.responseCode == 502 || status.responseCode == 503 || status.responseCode == 504) {
                        status.disconnect()
                        return@repeat
                    }
                    return@withContext null
                }
                val json = org.json.JSONObject(status.inputStream.bufferedReader().use { it.readText() })
                when (json.optString("status")) {
                    "failed" -> return@withContext null
                    "ready" -> {
                        val download = json.optString("download_url")
                        if (download.isBlank()) return@withContext null
                        return@withContext downloadVideo(context, base + download, clientId)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun openJsonConnection(
        url: String,
        method: String,
        clientId: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
        setRequestProperty("X-Client-Id", clientId)
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        doOutput = method == "POST"
    }

    private fun downloadVideo(context: Context, url: String, clientId: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
            setRequestProperty("X-Client-Id", clientId)
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        if (connection.responseCode !in 200..299) return null
        val maxBytes = 200L * 1024L * 1024L
        val declared = connection.contentLengthLong
        if (declared > maxBytes) return null
        val dir = File(context.cacheDir, "generated").apply { if (!exists()) mkdirs() }
        val file = File(dir, "arya_video_${System.currentTimeMillis()}.mp4")
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IllegalStateException("video_too_large")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (file.length() == 0L) { file.delete(); return null }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (_: Exception) {
            file.delete()
            return null
        } finally {
            connection.disconnect()
        }
    }
}
