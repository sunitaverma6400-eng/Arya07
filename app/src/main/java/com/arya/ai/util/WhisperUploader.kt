package com.arya.ai.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared "upload an audio file to Arya Relay's `/v1/whisper`, get back a transcript" logic —
 * used by both [WhisperRecorder] (tap-to-stop recording on the Chat screen, `.m4a`) and
 * [VadCommandRecorder] (hands-free wake-word command listening, `.wav`), so the actual
 * HTTP/relay part isn't duplicated between the two recording styles.
 */
object WhisperUploader {

    val isAvailable: Boolean get() = com.arya.ai.BuildConfig.RELAY_URL.isNotBlank()

    /** Uploads [file] to the relay and returns the transcript, or null on any failure/no-key/no-relay. */
    suspend fun transcribe(file: File, mimeType: String): String? = withContext(Dispatchers.IO) {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return@withContext null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        val boundary = "AryaBoundary${System.currentTimeMillis()}"

        try {
            val connection = (URL("$base/v1/whisper").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 25_000
                readTimeout = 25_000
                doOutput = true
            }
            connection.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                out.write(file.readBytes())
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
            json.optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
