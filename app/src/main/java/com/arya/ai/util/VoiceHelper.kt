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
    private var visualizer: android.media.audiofx.Visualizer? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                // ElevenLabs (tried first, see trySpeakElevenLabs) is already a female voice —
                // this matters for the plain system-TTS fallback, which otherwise just uses
                // whatever the device's default voice happens to be (often male).
                selectFemaleSystemVoice()
                ttsReady = true
            }
        }
    }

    /** Android's [android.speech.tts.Voice] has no reliable cross-engine gender field, but most
     *  engines (including the common Google one) encode it in the voice's own name (e.g.
     *  "hi-in-x-hia#female_1-local") — best-effort match on that, silently keeping the engine's
     *  default voice if nothing matches rather than risk picking something worse. */
    private fun selectFemaleSystemVoice() {
        try {
            val voices = tts?.voices ?: return
            val current = tts?.language
            val match = voices.firstOrNull {
                (current == null || it.locale.language == current.language) && it.name.contains("female", ignoreCase = true)
            } ?: voices.firstOrNull { it.name.contains("female", ignoreCase = true) }
            if (match != null) tts?.voice = match
        } catch (e: Exception) {
            // Engine default is fine if voice selection isn't supported here.
        }
    }

    fun speechRecognizerIntent(promptText: String = "Boliye…"): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, promptText)
        }

    fun isRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * ElevenLabs first, Android system TTS fallback. Safe to call from the main thread.
     *
     * @param emotion one of the tags the relay/[OnlineChatHelper] resolves per reply
     *   ("neutral","happy","sad","angry","surprised","caring","playful","serious") — shapes
     *   ElevenLabs' delivery server-side. Ignored by the system-TTS fallback (no such control
     *   there).
     * @param onMouthLevel fired ~30x/sec with the ElevenLabs audio's real playback amplitude
     *   (0f-1f, RMS-normalized) while it plays — this is what [VrmAvatarView] feeds into the
     *   3D model's mouth-open blendshape for actual audio-reactive lip sync instead of a fixed
     *   open/close loop. Not called on the system-TTS fallback path (no raw PCM access there);
     *   callers should drive a simple fallback cycle themselves in that case.
     */
    suspend fun speak(text: String, emotion: String = "neutral", onMouthLevel: ((Float) -> Unit)? = null) {
        if (text.isBlank()) return
        val spokeViaElevenLabs = withContext(Dispatchers.IO) { trySpeakElevenLabs(text, emotion, onMouthLevel) }
        if (!spokeViaElevenLabs) speakSystem(text, emotion)
    }

    private fun speakSystem(text: String, emotion: String) {
        if (ttsReady) {
            val (pitch, rate) = com.arya.ai.util.AvatarEmotion.systemTtsSettings(emotion)
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arya_utterance")
        }
    }

    /** Returns true if audio actually started playing. Never throws — any failure just returns false. */
    private fun trySpeakElevenLabs(text: String, emotion: String, onMouthLevel: ((Float) -> Unit)?): Boolean {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return false
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val body = org.json.JSONObject().apply {
                put("text", text)
                put("emotion", emotion)
            }
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
            playAudioBytes(audioBytes, onMouthLevel)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun playAudioBytes(bytes: ByteArray, onMouthLevel: ((Float) -> Unit)?) {
        val file = File.createTempFile("arya_tts_", ".mp3", context.cacheDir)
        file.writeBytes(bytes)
        mediaPlayer?.release()
        releaseVisualizer()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                releaseVisualizer()
                onMouthLevel?.invoke(0f)
                it.release()
                file.delete()
            }
            setOnErrorListener { mp, _, _ ->
                releaseVisualizer()
                onMouthLevel?.invoke(0f)
                mp.release()
                file.delete()
                true
            }
            prepare()
            if (onMouthLevel != null) attachVisualizer(audioSessionId, onMouthLevel)
            start()
        }
    }

    /**
     * Taps the ACTUAL waveform of what's about to hit the speaker (not the mic — this reads
     * the app's own output audio session, no RECORD_AUDIO permission involved) so the avatar's
     * mouth opens with the real amplitude of each syllable instead of a metronome-style loop.
     */
    private fun attachVisualizer(audioSessionId: Int, onMouthLevel: (Float) -> Unit) {
        try {
            visualizer = android.media.audiofx.Visualizer(audioSessionId).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0] // smallest = cheapest/fastest, plenty for a level meter
                setDataCaptureListener(
                    object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            if (waveform == null || waveform.isEmpty()) return
                            // 8-bit unsigned PCM centered at 128 -> RMS -> 0..1, with a light
                            // curve so quiet consonants still move the mouth a bit instead of
                            // only booming vowels registering.
                            var sumSq = 0.0
                            for (b in waveform) {
                                val centered = (b.toInt() and 0xFF) - 128
                                sumSq += centered.toDouble() * centered
                            }
                            val rms = kotlin.math.sqrt(sumSq / waveform.size) / 128.0
                            val level = rms.coerceIn(0.0, 1.0).let { Math.pow(it, 0.6) }.toFloat()
                            onMouthLevel(level)
                        }

                        override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                    },
                    android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, // ~30-60Hz depending on device cap — smooth enough for a mouth, cheap enough to not matter
                    true, false
                )
                enabled = true
            }
        } catch (e: Exception) {
            // Some OEMs restrict Visualizer access — degrade silently, avatar just won't lip-sync.
            visualizer = null
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        visualizer = null
    }

    fun stopSpeaking() {
        tts?.stop()
        releaseVisualizer()
        mediaPlayer?.let { if (it.isPlaying) it.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        releaseVisualizer()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
