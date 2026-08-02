package com.arya.ai.util

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Wraps voice input/output for Arya.
 *
 * Speech-to-text: Android's built-in [SpeechRecognizer] (voice -> text), no network model
 * needed — kept as-is, used directly by callers via [speechRecognizerIntent].
 *
 * Text-to-speech: tries ElevenLabs (via Arya Relay — key lives server-side, see
 * arya-relay/app.py's `/v1/elevenlabs`) first for a natural voice, and falls back to
 * Android's built-in [TextToSpeech] if the relay isn't configured or the call fails — same
 * "try the nicer online thing, fall back to the free offline thing" pattern as
 * [com.arya.ai.tools.WebTools.webSearch].
 */
class VoiceHelper(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }

    fun speechRecognizerIntent(promptText: String = "Boliye…"): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, promptText)
        }

    fun isRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /** ElevenLabs first, Android system TTS fallback. Safe to call from the main thread. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val spokeViaElevenLabs = withContext(Dispatchers.IO) { trySpeakElevenLabs(text) }
        if (!spokeViaElevenLabs) speakSystem(text)
    }

    private fun speakSystem(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arya_utterance")
        }
    }

    /** Returns true if audio actually started playing. Never throws — any failure just returns false. */
    private fun trySpeakElevenLabs(text: String): Boolean {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return false
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val body = org.json.JSONObject().apply { put("text", text) }
            val connection = (URL("$base/v1/elevenlabs").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return false

            val audioBytes = connection.inputStream.readBytes()
            if (audioBytes.isEmpty()) return false
            playAudioBytes(audioBytes)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun playAudioBytes(bytes: ByteArray) {
        val file = File.createTempFile("arya_tts_", ".mp3", context.cacheDir)
        file.writeBytes(bytes)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                file.delete()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                file.delete()
                true
            }
            prepare()
            start()
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        mediaPlayer?.let { if (it.isPlaying) it.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
