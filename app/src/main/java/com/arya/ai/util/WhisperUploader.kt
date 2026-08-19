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

    /** Set right before [transcribe] returns null, so callers (ChatScreen's Toast,
     *  WakeWordService's spoken fallback) can surface *why* it failed instead of a generic
     *  "kuch samajh nahi aaya" every time — e.g. distinguishing a slow/timed-out connection
     *  from the server rejecting the request from Groq returning an empty transcript. */
    @Volatile
    var lastError: String? = null
        private set

    /** Lets other early-failure paths that never reach [transcribe] (e.g.
     *  [VadCommandRecorder]'s mic-setup/no-speech-detected checks) record a reason too, so
     *  callers always get *something* to show instead of a stale/blank value. */
    fun setLastError(reason: String) {
        lastError = reason
    }

    /** Uploads [file] to the relay and returns the transcript, or null on any failure/no-key/no-relay. */
    suspend fun transcribe(context: android.content.Context, file: File, mimeType: String, language: String? = null): String? = withContext(Dispatchers.IO) {
        lastError = null
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) {
            lastError = "relay_not_configured"
            return@withContext null
        }
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        val boundary = "AryaBoundary${System.currentTimeMillis()}"

        try {
            val connection = (URL("$base/v1/whisper").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                setRequestProperty("X-Client-Id", PreferencesManager(context.applicationContext).relayClientId)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 25_000
                readTimeout = 25_000
                doOutput = true
            }
            connection.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                if (file.length() > 25L * 1024L * 1024L) {
                    lastError = "audio_too_large"
                    return@withContext null
                }
                out.write(file.readBytes())
                if (!language.isNullOrBlank()) {
                    out.write("\r\n--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n".toByteArray())
                    out.write(language.lowercase().take(2).toByteArray())
                }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = try {
                    connection.errorStream?.bufferedReader()?.readText()
                } catch (e: Exception) { null }
                lastError = "http_$code${if (!body.isNullOrBlank()) ": $body" else ""}"
                return@withContext null
            }
            val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
            val text = json.optString("text")
            if (text.isBlank()) {
                lastError = "empty_transcript"
                null
            } else text
        } catch (e: java.net.SocketTimeoutException) {
            lastError = "timeout: ${e.message}"
            null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }
}
