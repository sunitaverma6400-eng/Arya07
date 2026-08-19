package com.arya.ai.util

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
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
                // Bug fix (see chat history — user reported Arya speaking Hindi/Hinglish text
                // in an English accent): this used to be `tts?.language = Locale.getDefault()`,
                // which on a phone whose SYSTEM language is English resolves to en-US — the
                // engine then read Hindi words using English phonetics. Arya's whole app is
                // Hindi/Hinglish, so Hindi should be the TTS default regardless of the device's
                // system language, not tied to it. Falls back to the device default only if
                // this phone genuinely has no Hindi voice data installed (LANG_MISSING_DATA/
                // LANG_NOT_SUPPORTED) — better to speak in *some* accent than go silent.
                val hindiResult = tts?.setLanguage(Locale("hi", "IN"))
                if (hindiResult == TextToSpeech.LANG_MISSING_DATA || hindiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
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
                setRequestProperty("X-Client-Id", PreferencesManager(context).relayClientId)
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return false
            val contentType = connection.contentType?.lowercase() ?: ""
            val contentLength = connection.contentLengthLong
            if (contentLength > 20L * 1024L * 1024L) return false
            if (!contentType.startsWith("audio/")) return false

            val audioBytes = connection.inputStream.readBytes()
            if (audioBytes.isEmpty() || audioBytes.size > 20 * 1024 * 1024) return false
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

    // ============================================================================
    // Memory Continuity — speaking in a cloned family-member voice (Fish Audio, via
    // arya-relay's /v1/fishaudio/tts — see that file's doc comment for why Fish Audio
    // and not ElevenLabs here). Deliberately separate from [speak] above rather than
    // folding a voiceId param into it: this path has NO system-TTS fallback (there's no
    // "family member's voice" to approximate with the device's generic TTS engine), so
    // callers need to know explicitly whether it succeeded.
    // ============================================================================

    /** True if audio actually played in the cloned voice. False on any failure — caller
     *  (FamilyMemoriesScreen) should fall back to [speak] (Arya's own voice) in that case. */
    suspend fun speakClonedVoice(text: String, voiceId: String, onMouthLevel: ((Float) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (text.isBlank() || voiceId.isBlank()) return@withContext false
            val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
            if (relayUrl.isBlank()) return@withContext false
            val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
            try {
                val body = org.json.JSONObject().apply {
                    put("text", text)
                    put("voice_id", voiceId)
                }
                val connection = (URL("$base/v1/fishaudio/tts").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    doOutput = true
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                if (connection.responseCode !in 200..299) return@withContext false
                val contentType = connection.contentType?.lowercase() ?: ""
                val contentLength = connection.contentLengthLong
                if (contentLength > 20L * 1024L * 1024L || !contentType.startsWith("audio/")) return@withContext false
                val audioBytes = connection.inputStream.readBytes()
                if (audioBytes.isEmpty() || audioBytes.size > 20 * 1024 * 1024) return@withContext false
                withContext(Dispatchers.Main) { playAudioBytes(audioBytes, onMouthLevel) }
                true
            } catch (e: Exception) {
                false
            }
        }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        releaseVisualizer()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

/**
 * Records a voice sample (family member's, with their consent) and uploads it to
 * arya-relay's `/v1/fishaudio/clone` to get back a reusable voice_id — same
 * record-to-file-then-multipart-upload shape as [WhisperRecorder]/[WhisperUploader], just a
 * different relay endpoint and purpose (create a voice, not transcribe one).
 */
class FamilyVoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isAvailable: Boolean get() = com.arya.ai.BuildConfig.RELAY_URL.isNotBlank()

    @Volatile
    var lastError: String? = null
        private set

    /** Caller must already hold RECORD_AUDIO permission. */
    fun startRecording() {
        val file = File.createTempFile("arya_voice_sample_", ".m4a", context.cacheDir)
        outputFile = file
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    /** Stops recording and uploads to the relay. Returns the new voice_id, or null on any
     *  failure (see [lastError]) — caller should let the person retry. */
    suspend fun stopAndClone(personName: String): String? = withContext(Dispatchers.IO) {
        lastError = null
        val file = outputFile
        try {
            recorder?.apply { stop(); release() }
        } catch (e: Exception) {
            // stop() throws if the recording was too short/silent
        }
        recorder = null
        outputFile = null
        if (file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            lastError = "recording_too_short"
            return@withContext null
        }
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) {
            file.delete()
            lastError = "relay_not_configured"
            return@withContext null
        }
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        val boundary = "AryaBoundary${System.currentTimeMillis()}"
        try {
            val connection = (URL("$base/v1/fishaudio/clone").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 30_000
                readTimeout = 30_000
                doOutput = true
            }
            connection.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"name\"\r\n\r\n".toByteArray())
                out.write("$personName\r\n".toByteArray())
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: audio/mp4\r\n\r\n".toByteArray())
                out.write(file.readBytes())
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                lastError = "http_$code${if (!errBody.isNullOrBlank()) ": $errBody" else ""}"
                return@withContext null
            }
            val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
            val voiceId = json.optString("voice_id")
            if (voiceId.isBlank()) {
                lastError = "no_voice_id_returned"
                null
            } else voiceId
        } catch (e: java.net.SocketTimeoutException) {
            lastError = "timeout: ${e.message}"
            null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            file.delete()
        }
    }

    /** Discards an in-progress recording without uploading (e.g. user cancels). */
    fun cancelRecording() {
        try {
            recorder?.apply { stop(); release() }
        } catch (e: Exception) {
            // ignore — discarding anyway
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
