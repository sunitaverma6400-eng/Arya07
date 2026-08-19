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

    /** [text]: the reply. [emotion]: the relay's `[emotion:xxx]` tag for this reply (same
     *  pipeline as [com.arya.ai.util.OnlineChatHelper]'s text-only path — vision calls go
     *  through the same `/v1/relay` endpoint, so they get an emotion tag too; this used to be
     *  silently discarded here, which is why vision replies always spoke in a flat "neutral"
     *  voice/expression regardless of what was actually in the photo or reply. */
    data class VisionResult(val text: String, val emotion: String)

    private fun relayBase(): String? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        return relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
    }

    val isAvailable: Boolean get() = relayBase() != null

    private const val DEFAULT_SYSTEM_PROMPT =
        "Tum Arya ho, ek voice assistant jo camera/screen dekh ke bata rahi hai. Chhota, bolne-jaisa jawab do."

    /** Phase 6 (see chat history): retries once on transient failure, same reasoning as
     *  [ImageGenTools.generate]'s retry — a live camera/screen question shouldn't fail outright
     *  just because the relay's first response happened to time out.
     *
     *  @param systemPrompt Defaults to the short, spoken-style instruction
     *  [com.arya.ai.service.WakeWordService]'s live-conversation loop needs — pass something
     *  more thorough (see [com.arya.ai.viewmodel.ChatViewModel]'s call site) for a typed-chat
     *  photo attachment, where a one-line answer isn't what the person wants. */
    fun describeImage(bitmap: Bitmap, question: String, systemPrompt: String = DEFAULT_SYSTEM_PROMPT): VisionResult? {
        val base = relayBase() ?: return null
        repeat(2) { attempt ->
            val result = tryDescribeImage(base, bitmap, question, systemPrompt)
            if (result != null) return result
            if (attempt == 0) Thread.sleep(800)
        }
        return null
    }

    private fun tryDescribeImage(base: String, bitmap: Bitmap, question: String, systemPrompt: String): VisionResult? {
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val imageBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            val body = org.json.JSONObject().apply {
                put("provider", "gemini")
                // Keep vision on Gemini, but follow the app's current Gemini model catalog
                // instead of the retired 2.0 model hard-code. The first catalog entry is the
                // current Flash-Lite alias and can be changed centrally in OnlineModels.kt.
                put("model", com.arya.ai.data.OnlineModels.GEMINI.firstOrNull()?.id ?: "gemini-flash-latest")
                put("prompt", question)
                put("image_base64", imageBase64)
                put("image_mime", "image/jpeg")
                put("systemPrompt", systemPrompt)
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
            val text = json.optString("text").takeIf { it.isNotBlank() } ?: return null
            VisionResult(text, json.optString("emotion", "neutral"))
        } catch (e: Exception) {
            null
        }
    }
}
