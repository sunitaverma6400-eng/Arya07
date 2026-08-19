package com.arya.ai.service

import ai.picovoice.porcupine.PorcupineManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.arya.ai.MainActivity
import com.arya.ai.inference.ToolCallParser
import com.arya.ai.tools.AryaToolRegistry
import com.arya.ai.util.ApiKeyManager
import com.arya.ai.util.ApiProvider
import com.arya.ai.util.PorcupineWakeWordDetector
import com.arya.ai.util.VoiceActivityDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Runs in the background (survives the app's UI being closed) and always listens for a
 * wake phrase — "hello arya", "hi arya", "hey arya" — the same idea as "OK Google".
 *
 * IMPORTANT — how this differs from "OK Google": Google's hotword uses a tiny dedicated
 * always-on DSP model baked into the phone's hardware/OS, so it costs almost no battery.
 * This service picks between two approaches depending on what the user has set up:
 *
 *  - Option A ([PorcupineWakeWordDetector], preferred when available): a dedicated
 *    Picovoice Porcupine keyword-spotting model trained specifically for "Hey Arya". Very
 *    battery efficient, closest to real hotword hardware, but needs the user to save a
 *    free Picovoice AccessKey (Settings -> API Keys) AND drop their trained
 *    hey-arya_android.ppn file into app/src/main/assets/ before building.
 *  - Option B ([VoiceActivityDetector], automatic fallback): no full [SpeechRecognizer]
 *    24/7 either — instead, while idle it runs a cheap raw-mic loudness check ("is someone
 *    even talking?"), and only spins up the real [SpeechRecognizer] for a few seconds once
 *    that fires. Needs no account or extra file, but is less efficient than Option A.
 *
 * Most Android OEMs (Xiaomi/MIUI, OnePlus/OxygenOS, etc.) still aggressively kill background
 * services unless the user manually disables battery optimization for Arya. See the README.
 *
 * Once woken, it listens for the actual command, runs it the same way Agent Skills does
 * (tool-call system prompt → free online model via the relay → [AryaToolRegistry] if it's a tool call),
 * and speaks the reply back with TextToSpeech — no need to open the app.
 */
class WakeWordService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "arya_wakeword"
        const val NOTIFICATION_ID = 4201
        // Bug fix (see chat history): VAD-triggered SpeechRecognizer has real startup latency
        // (VAD needs a couple loud chunks to fire, then the recognizer itself takes time to
        // spin up), so the leading greeting word of "Hey Arya" is very likely to get clipped
        // before capture actually starts — leaving just "arya" (or a mis-transcription of it,
        // since Android's en-IN model has no custom vocabulary hint for a made-up name) in the
        // transcript. The old regex required BOTH the greeting AND "arya" together, so clipped
        // audio almost never matched. Now the greeting is optional, and common ASR spellings of
        // "Arya" are accepted too — a false *positive* here just means Arya says "Haan, bolo"
        // once for nothing, but a false *negative* means the wake word silently never works.
        private val WAKE_PHRASE = Regex(
            """\b(?:(?:hello|hi|hii|hey|ok)\s+)?(?:arya|aria|ariya|aarya|aaria|arja|arjya)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Starts continuous (Gemini-Live-style) conversation — no repeated "Hey Arya" needed. */
        const val ACTION_START_LIVE = "com.arya.ai.action.START_LIVE"
        /** Drops back to normal wake-word-gated listening. */
        const val ACTION_STOP_LIVE = "com.arya.ai.action.STOP_LIVE"
    }

    private var recognizer: SpeechRecognizer? = null
    private var vad: VoiceActivityDetector? = null
    private var porcupineManager: PorcupineManager? = null
    private var usingPorcupine = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var elevenLabsPlayer: android.media.MediaPlayer? = null
    // Completion signal for the streamed-reply pipeline's system-TTS fallback (see
    // SentenceSpeechQueue) — completed by the UtteranceProgressListener above so a sentence's
    // playback can be properly awaited instead of guessed via a fixed delay.
    @Volatile private var systemTtsCompletion: CompletableDeferred<Unit>? = null
    // Tracks the in-flight sentence-pipeline job so barge-in can cancel it immediately.
    private var sentenceQueueJob: Job? = null
    private val vadCommandRecorder by lazy { com.arya.ai.util.VadCommandRecorder(this) }
    private var mode = Mode.WAKE_WORD
    private var running = false
    // A mistimed "Hey Arya <command>" (starts speaking half a beat before the recognizer
    // is actually listening, or a short pause the recognizer reads as silence) shouldn't
    // force the person to repeat the wake word from scratch — one retry directly in
    // COMMAND mode first, see onError below.
    private var commandRetried = false
    /** Gemini-Live-style continuous conversation — set via [ACTION_START_LIVE]/[ACTION_STOP_LIVE]. */
    private var liveMode = false
    @Volatile private var isSpeaking = false
    private var bargeInVad: VoiceActivityDetector? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private enum class Mode { WAKE_WORD, COMMAND }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        tts = TextToSpeech(this, this)
        val savedKey = ApiKeyManager(this).singleKey(ApiProvider.PICOVOICE)
        usingPorcupine = !savedKey.isNullOrBlank() && PorcupineWakeWordDetector.isAvailable(this)
        val startupText = if (usingPorcupine) {
            "Sun rahi hoon (Porcupine)... \"Hey Arya\" bolo"
        } else {
            "Sun rahi hoon... \"Hey Arya\" bolo"
        }
        startForeground(NOTIFICATION_ID, buildNotification(startupText))
        acquireWakeLock()
    }

    /**
     * Keeps the CPU awake (screen can still be off/locked) so Doze mode doesn't pause the
     * listening loop — without this, some phones stop the recognizer within a minute or two
     * of the screen locking. Released in [onDestroy]. This does NOT keep the screen on.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Arya:WakeWordService").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L /* 12h safety cap so a crash can't hold it forever */)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LIVE -> {
                liveMode = true
                com.arya.ai.util.LiveConversationState.isActive.value = true
                if (!running) running = true
                pendingJob?.cancel()
                vad?.stop()
                stopPorcupine()
                startCommandListening() // skip the wake-word wait — go straight to listening
            }
            ACTION_STOP_LIVE -> {
                liveMode = false
                com.arya.ai.util.LiveConversationState.isActive.value = false
                com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.IDLE
                pendingJob?.cancel()
                if (running) startIdleListening() // fall back to normal "Hey Arya" gating
            }
            else -> {
                if (!running) {
                    running = true
                    startIdleListening()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        liveMode = false
        com.arya.ai.util.LiveConversationState.isActive.value = false
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.IDLE
        pendingJob?.cancel()
        vad?.stop()
        bargeInVad?.stop()
        stopPorcupine()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        elevenLabsPlayer?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    // ---- TextToSpeech ----

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val language = Locale("hi", "IN").let { if (tts?.isLanguageAvailable(it) ?: -2 >= 0) it else Locale.US }
            tts?.language = language
            // ElevenLabs (tried first in tryPlayElevenLabs) is already a female voice — this
            // matters for the plain Android system-TTS fallback when the relay isn't
            // configured/reachable, since that path otherwise just uses whatever the device's
            // default voice is (often male).
            selectFemaleSystemVoice(language)
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    systemTtsCompletion?.complete(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    systemTtsCompletion?.complete(Unit)
                }
            })
        }
    }

    /** Android's [android.speech.tts.Voice] has no reliable cross-engine gender field, but most
     *  engines (including the common Google one) encode it in the voice's own name (e.g.
     *  "hi-in-x-hia#female_1-local") — best-effort match on that, silently keeping the engine's
     *  default voice if nothing matches rather than risk picking something worse. */
    private fun selectFemaleSystemVoice(language: Locale) {
        try {
            val voices = tts?.voices ?: return
            val match = voices.firstOrNull {
                it.locale.language == language.language && it.name.contains("female", ignoreCase = true)
            } ?: voices.firstOrNull { it.name.contains("female", ignoreCase = true) }
            if (match != null) tts?.voice = match
        } catch (e: Exception) {
            // Some engines throw on voices/setVoice access — the default voice is fine.
        }
    }

    /**
     * @param allowBargeIn Whether to keep the mic open (via [startBargeInListening]) while this
     *  plays, so the person can interrupt mid-sentence. Defaults true for real replies (see
     *  [runCommand]), where that's genuinely useful. Callers pass **false** for short reactive
     *  system utterances — wake acknowledgment ("Haan, bolo"), the no-match apology, and error
     *  messages — because of a real bug this was causing (see chat history): barge-in's mic
     *  uses [android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION], which Android
     *  explicitly disables echo cancellation on. On a short utterance, Arya's own voice
     *  leaking back through the mic (speaker → mic, no AEC) reads as "the person started
     *  talking", which cut her off and jumped straight back into command-listening — which then
     *  heard nothing real (it was her own echo), errored again, and repeated the apology again,
     *  looping indefinitely. Long replies aren't immune to the same echo either, but they're
     *  worth the risk since being interruptible mid-answer is the actual point of barge-in;
     *  a 3-word apology has nothing worth interrupting, so it's not worth the loop risk there.
     */
    private fun speak(
        text: String,
        emotion: String = com.arya.ai.util.AvatarEmotion.NEUTRAL,
        thenListenForCommand: Boolean = false,
        allowBargeIn: Boolean = true
    ) {
        recognizer?.stopListening()
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.SPEAKING
        val utteranceId = "arya_${System.currentTimeMillis()}"
        speakAudio(text, emotion, utteranceId)
        updateNotification(text)
        if (allowBargeIn) startBargeInListening()
        // Give TTS a moment to actually say it before we open the mic again.
        val estimatedMs = (text.length * 55L).coerceIn(600, 6000)
        pendingJob?.cancel()
        pendingJob = scope.launch {
            kotlinx.coroutines.delay(estimatedMs)
            if (allowBargeIn) stopBargeInListening()
            com.arya.ai.util.LiveConversationState.mouthLevel.value = 0f
            if (!running) return@launch
            if (thenListenForCommand) startCommandListening() else startIdleListening()
        }
    }

    /**
     * ElevenLabs (via Arya Relay) first for a natural voice, Android system [tts] as fallback
     * if the relay isn't configured or the call fails — same pattern as [com.arya.ai.util.VoiceHelper.speak]
     * (duplicated here rather than shared, since this service manages its own `tts` instance/lifecycle
     * for [onInit]/[isSpeaking] tracking, matching how each of Arya's tool files stays self-contained).
     */
    private fun speakAudio(text: String, emotion: String, utteranceId: String) {
        isSpeaking = true
        scope.launch(Dispatchers.IO) {
            val played = tryPlayElevenLabs(text, emotion)
            if (!played) {
                withContext(Dispatchers.Main) {
                    val (pitch, rate) = com.arya.ai.util.AvatarEmotion.systemTtsSettings(emotion)
                    tts?.setPitch(pitch)
                    tts?.setSpeechRate(rate)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
    }

    private fun tryPlayElevenLabs(text: String, emotion: String): Boolean {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return false
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val body = org.json.JSONObject().apply {
                put("text", text)
                put("emotion", emotion)
            }
            val connection = (java.net.URL("$base/v1/elevenlabs").openConnection() as java.net.HttpURLConnection).apply {
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
            val file = java.io.File.createTempFile("arya_wake_tts_", ".mp3", cacheDir)
            file.writeBytes(audioBytes)
            elevenLabsPlayer?.release()
            releaseAvatarVisualizer()
            elevenLabsPlayer = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    isSpeaking = false
                    releaseAvatarVisualizer()
                    com.arya.ai.util.LiveConversationState.mouthLevel.value = 0f
                    it.release()
                    file.delete()
                }
                setOnErrorListener { mp, _, _ ->
                    isSpeaking = false
                    releaseAvatarVisualizer()
                    com.arya.ai.util.LiveConversationState.mouthLevel.value = 0f
                    mp.release()
                    file.delete()
                    true
                }
                prepare()
                attachAvatarVisualizer(audioSessionId)
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private var avatarVisualizer: android.media.audiofx.Visualizer? = null

    /** Feeds [com.arya.ai.util.LiveConversationState.mouthLevel] from the real ElevenLabs
     *  playback waveform — same technique as [com.arya.ai.util.VoiceHelper.attachVisualizer] —
     *  so [com.arya.ai.ui.VrmAvatarView]'s mouth blendshape tracks actual speech, not a
     *  fixed loop, during live-mode conversation. */
    private fun attachAvatarVisualizer(audioSessionId: Int) {
        try {
            avatarVisualizer = android.media.audiofx.Visualizer(audioSessionId).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            if (waveform == null || waveform.isEmpty()) return
                            var sumSq = 0.0
                            for (b in waveform) {
                                val centered = (b.toInt() and 0xFF) - 128
                                sumSq += centered.toDouble() * centered
                            }
                            val rms = kotlin.math.sqrt(sumSq / waveform.size) / 128.0
                            val level = Math.pow(rms.coerceIn(0.0, 1.0), 0.6).toFloat()
                            com.arya.ai.util.LiveConversationState.mouthLevel.value = level
                        }

                        override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                    },
                    android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                    true, false
                )
                enabled = true
            }
        } catch (e: Exception) {
            avatarVisualizer = null
        }
    }

    private fun releaseAvatarVisualizer() {
        try {
            avatarVisualizer?.enabled = false
            avatarVisualizer?.release()
        } catch (_: Exception) {
        }
        avatarVisualizer = null
    }

    /**
     * Barge-in: while Arya is speaking, a lightweight VAD (separate instance from the idle
     * one) keeps listening in the background. If it hears the person start talking, we treat
     * that as "stop talking, I want to say something" — cut TTS off immediately and jump
     * straight into command listening, instead of making the user wait for Arya to finish.
     *
     * Note: without hardware/software echo cancellation this VAD can occasionally also
     * trigger off Arya's own voice coming through the speaker (worse on speakerphone at
     * high volume) — an acceptable trade-off for a from-scratch sample app, since a false
     * trigger just means it starts listening for a command a little early rather than
     * anything breaking.
     */
    private fun startBargeInListening() {
        if (!running) return
        bargeInVad?.stop()
        bargeInVad = VoiceActivityDetector(this) {
            if (isSpeaking) {
                // Bug fix (see chat history — "real-time/interruptible jaisa Gemini Live"):
                // this only ever stopped the system-TTS engine. ElevenLabs playback
                // (elevenLabsPlayer, used whenever the relay is configured — i.e. almost
                // always) kept right on playing over the top even after barge-in "cut in",
                // and the sentence-pipeline job kept feeding it more audio. Now both the
                // MediaPlayer and the in-flight sentence queue are stopped too.
                try { elevenLabsPlayer?.stop() } catch (_: Exception) {}
                sentenceQueueJob?.cancel()
                tts?.stop()
                isSpeaking = false
                stopBargeInListening()
                pendingJob?.cancel()
                startCommandListening()
            }
        }.also { it.start() }
    }

    private fun stopBargeInListening() {
        bargeInVad?.stop()
        bargeInVad = null
    }

    // ---- Idle listening: Porcupine (Option A) if set up, else VAD (Option B) ----

    private fun startIdleListening() {
        mode = Mode.WAKE_WORD
        if (!running) return
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.IDLE
        recognizer?.destroy()
        recognizer = null
        vad?.stop()
        stopPorcupine()
        if (usingPorcupine) {
            startPorcupineListening()
        } else {
            updateNotification("Sun rahi hoon (kam battery mode)... \"Hey Arya\" bolo")
            vad = VoiceActivityDetector(this) { onVoiceActivityDetected() }.also { it.start() }
        }
    }

    private fun startPorcupineListening() {
        val key = ApiKeyManager(this).singleKey(ApiProvider.PICOVOICE)
        if (key.isNullOrBlank()) {
            usingPorcupine = false
            startIdleListening()
            return
        }
        updateNotification("Sun rahi hoon (Porcupine wake word)... \"Hey Arya\" bolo")
        porcupineManager = PorcupineWakeWordDetector.start(
            context = this,
            accessKey = key,
            onWakeWordDetected = {
                scope.launch {
                    stopPorcupine()
                    commandRetried = false
                    speak("Haan, bolo", thenListenForCommand = true, allowBargeIn = false)
                }
            },
            onError = { msg ->
                scope.launch {
                    usingPorcupine = false
                    updateNotification("⚠️ Porcupine error ($msg) — kam battery mode mein switch")
                    startIdleListening()
                }
            }
        )
        if (porcupineManager == null) {
            // Missing/invalid asset or key, or device incompatibility — fall back quietly.
            usingPorcupine = false
            startIdleListening()
        }
    }

    private fun stopPorcupine() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (_: Exception) {
        }
        porcupineManager = null
    }

    /** VAD heard someone start talking — spin up the real recognizer briefly to check for the wake phrase. */
    private fun onVoiceActivityDetected() {
        if (!running) return
        startWakeWordSpeechCheck()
    }

    // ---- SpeechRecognizer-based listening ----

    private fun freshRecognizer(): SpeechRecognizer {
        vad?.stop()
        stopPorcupine()
        recognizer?.destroy()
        return SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
    }

    private fun startWakeWordSpeechCheck() {
        mode = Mode.WAKE_WORD
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("⚠️ Is device par speech recognition available nahi hai")
            return
        }
        recognizer = freshRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Short window: VAD already confirmed speech started, we just need to check
            // whether it was the wake phrase, then get back to the cheap idle loop.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
        }
        recognizer?.startListening(intent)
    }

    /**
     * Tries the hands-free Whisper path first (records via [vadCommandRecorder], which
     * auto-stops on trailing silence — no tap needed, unlike the Chat screen's toggle mic).
     * Falls back to [startCommandListeningViaSpeechRecognizer] if the relay isn't configured,
     * nobody spoke, or the upload/transcription failed.
     */
    private fun startCommandListening() {
        mode = Mode.COMMAND
        if (!running) return
        updateNotification("Bolo, sun rahi hoon...")
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.LISTENING
        if (vadCommandRecorder.isAvailable) {
            // Release the mic from VAD/Porcupine/recognizer first — same as freshRecognizer()
            // does — since AudioRecord needs exclusive access to it.
            vad?.stop()
            stopPorcupine()
            recognizer?.destroy()
            scope.launch {
                val text = vadCommandRecorder.recordAndTranscribe()
                if (!running) return@launch
                if (text != null) {
                    runCommand(text)
                } else {
                    updateNotification("Whisper fail hui (${com.arya.ai.util.WhisperUploader.lastError ?: "?"}) — device recognizer try kar rahi hoon")
                    startCommandListeningViaSpeechRecognizer()
                }
            }
        } else {
            startCommandListeningViaSpeechRecognizer()
        }
    }

    private fun startCommandListeningViaSpeechRecognizer() {
        mode = Mode.COMMAND
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Whisper-via-relay already failed/unavailable (that's how we got here — see
            // startCommandListening()), and this device also has no built-in speech service
            // (no Google app / speech services). Neither voice path can work here. Previously
            // this fell through to startListening() anyway, which just hung on "Bolo, sun
            // rahi hoon..." forever with no feedback — say so instead.
            updateNotification("⚠️ Awaaz samajhne ka koi tareeka available nahi hai is device par")
            com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.IDLE
            speak("Is device par awaaz samajh nahi paa rahi — text me type karke baat kar sakte ho", thenListenForCommand = false, allowBargeIn = false)
            return
        }
        recognizer = freshRecognizer()
        // Bug fix (see chat history — "Hindi me command samajh nahi rahi"): this fallback
        // only runs when the Whisper-via-relay path (multilingual, handles Hindi fine) is
        // unavailable or failed. It used to hard-code en-IN (English) here too, so Hindi
        // commands went unrecognized on this path even though the whole app/voice UI is
        // Hindi-first. hi-IN's Android speech models generally still transcribe common
        // embedded English words phonetically, and the LLM downstream reads Devanagari
        // fine either way.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }
        updateNotification("Bolo, sun rahi hoon...")
        recognizer?.startListening(intent)
    }

    // ---- RecognitionListener ----

    override fun onResults(results: Bundle) {
        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        handleTranscript(text, isFinal = true)
    }

    override fun onPartialResults(partialResults: Bundle) {
        val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (mode == Mode.WAKE_WORD) handleTranscript(text, isFinal = false)
    }

    private fun handleTranscript(text: String, isFinal: Boolean) {
        if (text.isBlank()) {
            if (isFinal) startIdleListening()
            return
        }
        when (mode) {
            Mode.WAKE_WORD -> {
                if (WAKE_PHRASE.containsMatchIn(text)) {
                    commandRetried = false
                    speak("Haan, bolo", thenListenForCommand = true, allowBargeIn = false)
                } else if (isFinal) {
                    // Wasn't the wake phrase — back to the cheap idle loop rather than
                    // keeping the full recognizer running.
                    startIdleListening()
                }
            }
            Mode.COMMAND -> if (isFinal) runCommand(text)
        }
    }

    override fun onError(error: Int) {
        // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT / ERROR_CLIENT etc. are all routine —
        // SpeechRecognizer only listens for one utterance at a time, so this just loops.
        if (!running) return
        when (mode) {
            Mode.WAKE_WORD -> startIdleListening()
            Mode.COMMAND -> {
                if (!commandRetried) {
                    commandRetried = true
                    speak("Phir se bolo", thenListenForCommand = true, allowBargeIn = false)
                } else {
                    commandRetried = false
                    speak("Kuch samajh nahi aaya, phir se \"Hey Arya\" bolo", thenListenForCommand = false, allowBargeIn = false)
                }
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---- Running the actual command (same convention Agent Skills uses) ----

    private fun runCommand(command: String) {
        commandRetried = false
        updateNotification("Soch rahi hoon: \"$command\"")
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.THINKING
        com.arya.ai.util.LiveConversationState.lastUserText.value = command
        // A recent camera/screen frame (LiveConversationScreen's vision toggles) — see
        // VisionFrameProvider. Only used if it's fresh; stale frames are ignored.
        val visionFrame = com.arya.ai.util.VisionFrameProvider.freshFrame()

        if (com.arya.ai.BuildConfig.RELAY_URL.isBlank()) {
            speak("Arya Relay configure nahi hai is build me — online jawab nahi mil sakta.", thenListenForCommand = false, allowBargeIn = false)
            openApp()
            return
        }

        // Streamed, sentence-pipelined reply for Live mode's plain-text turns — see
        // runTextCommandStreamed()'s doc comment for why (this is the actual "Gemini-Live-jaisa
        // real-time" latency fix from chat history). Vision-frame turns and single wake-word
        // commands keep the simpler old non-streamed path below — they're already short/
        // one-shot, so the added complexity isn't worth it there.
        if (liveMode && visionFrame == null) {
            runTextCommandStreamed(command)
            return
        }

        scope.launch {
            try {
                val (reply, emotion) = when {
                    // A camera/screen frame is attached — Gemini vision via the relay.
                    visionFrame != null ->
                        (com.arya.ai.tools.VisionRelay.describeImage(visionFrame, command)?.let { it.text to com.arya.ai.util.AvatarEmotion.sanitize(it.emotion) }
                            ?: generateTextOnlyReply(command))
                    else -> generateTextOnlyReply(command)
                }
                com.arya.ai.util.LiveConversationState.lastAryaText.value = reply
                com.arya.ai.util.LiveConversationState.lastEmotion.value = emotion
                speak(reply, emotion = emotion, thenListenForCommand = liveMode)
            } catch (e: Exception) {
                speak("⚠️ Error aa gaya: ${e.message}", thenListenForCommand = liveMode, allowBargeIn = false)
            }
        }
    }

    /**
     * Streamed, sentence-pipelined version of [generateTextOnlyReply] + [speak], used only for
     * Live mode's plain-text turns (see [runCommand]). Two things now happen in parallel
     * instead of strictly in sequence, which is what actually removes the multi-second dead
     * air the old design had between "you finish talking" and "Arya says anything at all":
     *
     *  1. The LLM reply streams in via [com.arya.ai.util.OnlineChatHelper.streamOnlineResponse]
     *     — each completed sentence is queued to speak as soon as it's available, instead of
     *     waiting for the ENTIRE reply to finish generating first (previously a 2-3 sentence
     *     reply could take several seconds of silence before Arya said a single word).
     *  2. [SentenceSpeechQueue] fetches a sentence's TTS audio *while the previous sentence is
     *     still playing*, instead of fetch-then-play-then-fetch-next — hiding ElevenLabs'
     *     network+generation latency behind playback time rather than stacking it on top of it.
     *
     * A tool-call JSON is never spoken as "sentences" — same [determined]/isToolCall guard
     * idea as [com.arya.ai.viewmodel.ChatViewModel]'s revealedAsText — it's parsed and
     * executed once the full response is in, exactly like the old non-streamed path did.
     *
     * Worth being upfront about what this is NOT (see chat history): this is still turn-based,
     * not full-duplex audio streaming like Gemini Live — the mic only reopens once Arya's
     * reply is fully spoken (or the person barges in, which now actually cuts ElevenLabs
     * playback too — see [startBargeInListening]). True continuous bidirectional streaming
     * would need a persistent connection and a realtime STT+LLM+TTS provider, not just
     * reordering HTTP calls — that's a separate, bigger rebuild, not this patch.
     */
    private fun runTextCommandStreamed(command: String) {
        recognizer?.stopListening()
        sentenceQueueJob = scope.launch {
            val queue = SentenceSpeechQueue()
            var bargeInStarted = false
            try {
                val personalityPrefix = com.arya.ai.tools.PersonalityStore.getPersonalityPrompt(applicationContext)
                com.arya.ai.tools.PersonalityStore.incrementInteraction(applicationContext)
                val prefs = com.arya.ai.util.PreferencesManager(applicationContext)
                val currentInfo = com.arya.ai.util.SimpleRagHelper(applicationContext).getCurrentInfoRaw()
                val personaWithInfo = "$personalityPrefix Tum Arya ho, ek voice assistant. Chhota, bolne-jaisa jawab do — ek ya do sentence. " +
                    "Jawab hamesha Hindi/Hinglish me bolo (Roman script), kabhi pure English me mat bolo." +
                    if (currentInfo.isNullOrBlank()) "" else " Current-affairs reference: $currentInfo"
                val systemPrompt = ToolCallParser.buildSystemPrompt(
                    AryaToolRegistry.relevantTools(command, maxTools = prefs.maxToolsPerRequest),
                    persona = personaWithInfo
                )

                val full = StringBuilder()
                var spokenUpTo = 0
                var determined = false
                var isToolCall = false
                var emotion = com.arya.ai.util.AvatarEmotion.NEUTRAL

                withContext(Dispatchers.IO) {
                    com.arya.ai.util.OnlineChatHelper.streamOnlineResponse(
                        prefs, command, systemPrompt,
                        onEmotion = { e -> emotion = com.arya.ai.util.AvatarEmotion.sanitize(e) },
                        // Voice/Live mode always forces Gemini (see chat history — "pic,
                        // voice, video, live baat... ke liye hamesha Gemini use ho").
                        forceGeminiOnly = true,
                        onChunk = { delta ->
                            full.append(delta)
                            if (!determined) {
                                val trimmed = full.toString().trimStart()
                                if (trimmed.isNotEmpty()) {
                                    determined = true
                                    isToolCall = trimmed.startsWith("{")
                                }
                            }
                            if (determined && !isToolCall) {
                                val (sentences, newSpokenUpTo) = extractCompleteSentences(full.toString(), spokenUpTo)
                                spokenUpTo = newSpokenUpTo
                                sentences.forEach { s ->
                                    if (!bargeInStarted) {
                                        bargeInStarted = true
                                        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.SPEAKING
                                        startBargeInListening()
                                    }
                                    updateNotification(s)
                                    queue.enqueue(s, emotion)
                                }
                            }
                        }
                    )
                }

                val finalText = full.toString()
                val replyText: String
                if (isToolCall) {
                    val toolCall = ToolCallParser.parseToolCall(finalText)
                    replyText = (if (toolCall != null) AryaToolRegistry.execute(applicationContext, toolCall) else finalText.trim())
                        .ifBlank { "Ho gaya." }
                    if (!bargeInStarted) {
                        bargeInStarted = true
                        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.SPEAKING
                        startBargeInListening()
                    }
                    updateNotification(replyText)
                    queue.enqueue(replyText, com.arya.ai.util.AvatarEmotion.NEUTRAL)
                } else {
                    val remainder = finalText.substring(spokenUpTo.coerceAtMost(finalText.length)).trim()
                    replyText = finalText.trim().ifBlank { "Samajh nahi paayi, phir se try karo." }
                    if (remainder.isNotEmpty()) {
                        if (!bargeInStarted) {
                            bargeInStarted = true
                            com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.SPEAKING
                            startBargeInListening()
                        }
                        queue.enqueue(remainder, emotion)
                    }
                }
                com.arya.ai.util.LiveConversationState.lastAryaText.value = replyText
                com.arya.ai.util.LiveConversationState.lastEmotion.value = if (isToolCall) com.arya.ai.util.AvatarEmotion.NEUTRAL else emotion

                queue.finish() // suspends until every queued sentence has finished playing
                if (bargeInStarted) stopBargeInListening()
                com.arya.ai.util.LiveConversationState.mouthLevel.value = 0f
                if (!running) return@launch
                if (liveMode) startCommandListening() else startIdleListening()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // barge-in already handled switching to command listening
            } catch (e: Exception) {
                queue.cancelAndFinishSilently()
                if (bargeInStarted) stopBargeInListening()
                speak("⚠️ Error aa gaya: ${e.message}", thenListenForCommand = liveMode, allowBargeIn = false)
            }
        }
    }

    private val SENTENCE_BOUNDARY_REGEX = Regex("""(?<=[.!?।॥])\s+""")

    /** Splits [text] into complete sentences past [from], leaving any trailing fragment (no
     *  terminal punctuation yet) unconsumed so it combines with the next streamed chunk.
     *  Returns the new sentences plus the updated "consumed up to" index. Devanagari
     *  danda/double-danda (।॥) are included as sentence enders alongside .!? since replies
     *  are Hindi-first and the LLM can produce either script. */
    private fun extractCompleteSentences(text: String, from: Int): Pair<List<String>, Int> {
        if (from >= text.length) return emptyList<String>() to from
        val unconsumed = text.substring(from)
        val matches = SENTENCE_BOUNDARY_REGEX.findAll(unconsumed).toList()
        if (matches.isEmpty()) return emptyList<String>() to from
        val sentences = mutableListOf<String>()
        var localStart = 0
        for (m in matches) {
            val piece = unconsumed.substring(localStart, m.range.first)
            if (piece.trim().length >= 2) sentences.add(piece.trim()) // skip lone-punctuation noise
            localStart = m.range.last + 1
        }
        return sentences to (from + localStart)
    }

    /** Fetches ElevenLabs TTS audio for [text] and returns it as a temp file, or null on any
     *  failure (relay not configured, network error, empty audio) — caller falls back to
     *  system TTS. Fetch-only (no playback) so [SentenceSpeechQueue] can prefetch the next
     *  sentence's audio while the current one is still playing. */
    private fun fetchElevenLabsAudioFile(text: String, emotion: String): java.io.File? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val body = org.json.JSONObject().apply {
                put("text", text)
                put("emotion", emotion)
            }
            val connection = (java.net.URL("$base/v1/elevenlabs").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            val audioBytes = connection.inputStream.readBytes()
            if (audioBytes.isEmpty()) return null
            java.io.File.createTempFile("arya_stream_tts_", ".mp3", cacheDir).apply { writeBytes(audioBytes) }
        } catch (e: Exception) {
            null
        }
    }

    /** Plays an already-fetched ElevenLabs audio file and suspends until playback actually
     *  finishes (real completion via listener, not a guessed delay — the old [speak] path's
     *  fixed `text.length * 55ms` estimate could easily be wrong in either direction). */
    private suspend fun playAudioFileBlocking(file: java.io.File) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            isSpeaking = true
            try {
                elevenLabsPlayer?.release()
                releaseAvatarVisualizer()
                elevenLabsPlayer = android.media.MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        isSpeaking = false
                        releaseAvatarVisualizer()
                        it.release()
                        file.delete()
                        done.complete(Unit)
                    }
                    setOnErrorListener { mp, _, _ ->
                        isSpeaking = false
                        releaseAvatarVisualizer()
                        mp.release()
                        file.delete()
                        done.complete(Unit)
                        true
                    }
                    prepare()
                    attachAvatarVisualizer(audioSessionId)
                    start()
                }
            } catch (e: Exception) {
                isSpeaking = false
                file.delete()
                done.complete(Unit)
            }
        }
        done.await()
    }

    /** System-TTS fallback for one sentence, suspending until real completion via
     *  [systemTtsCompletion] (completed by the UtteranceProgressListener set in [onInit]). */
    private suspend fun speakSystemTtsBlocking(text: String, emotion: String) {
        val done = CompletableDeferred<Unit>()
        systemTtsCompletion = done
        withContext(Dispatchers.Main) {
            isSpeaking = true
            val (pitch, rate) = com.arya.ai.util.AvatarEmotion.systemTtsSettings(emotion)
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            val queued = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arya_stream_${System.currentTimeMillis()}")
            if (tts == null || queued != TextToSpeech.SUCCESS) {
                isSpeaking = false
                done.complete(Unit)
            }
        }
        done.await()
        systemTtsCompletion = null
    }

    /** See [runTextCommandStreamed]. Speaks sentences strictly in the order [enqueue]d, but
     *  starts fetching the NEXT sentence's ElevenLabs audio while the CURRENT one is still
     *  playing — a simple two-deep pipeline that hides per-sentence network/generation
     *  latency behind playback time, without needing real bidirectional audio streaming. */
    private inner class SentenceSpeechQueue {
        private val channel = Channel<Pair<String, String>>(capacity = Channel.UNLIMITED)
        private var consumerJob: Job? = null

        fun enqueue(sentence: String, emotion: String) {
            if (sentence.isBlank()) return
            if (consumerJob == null) consumerJob = scope.launch { consume() }
            channel.trySend(sentence to emotion)
        }

        /** Suspends until every enqueued sentence has finished playing. Safe to call even if
         *  [enqueue] was never called (e.g. a blank reply) — just returns immediately. */
        suspend fun finish() {
            channel.close()
            consumerJob?.join()
        }

        /** Barge-in / error path: stop consuming and drop whatever's left, without waiting. */
        fun cancelAndFinishSilently() {
            channel.close()
            consumerJob?.cancel()
        }

        private suspend fun consume() {
            var current = channel.receiveCatching().getOrNull() ?: return
            var currentAudio = scope.async(Dispatchers.IO) { fetchElevenLabsAudioFile(current.first, current.second) }
            while (true) {
                val file = currentAudio.await()
                // Peek for an already-buffered next sentence and start fetching its audio
                // now, in parallel with this sentence's playback just below.
                var next = channel.tryReceive().getOrNull()
                var nextAudio = next?.let { n -> scope.async(Dispatchers.IO) { fetchElevenLabsAudioFile(n.first, n.second) } }
                if (file != null) playAudioFileBlocking(file) else speakSystemTtsBlocking(current.first, current.second)
                if (next == null) {
                    next = channel.receiveCatching().getOrNull() ?: break
                    nextAudio = scope.async(Dispatchers.IO) { fetchElevenLabsAudioFile(next.first, next.second) }
                }
                current = next
                currentAudio = nextAudio!!
            }
        }
    }

    /** The text-only (+ tool-calling) path — used whenever there's no vision frame to attach,
     *  or the vision relay call fails. Always goes through the free online relay now.
     *  Returns (replyText, emotion) — emotion comes from the relay's [emotion:xxx] tag via
     *  [com.arya.ai.util.OnlineChatHelper.OnlineChatResult.emotion]; tool-call results (which
     *  don't go back through the LLM) default to neutral since there's no reply tone to read. */
    private suspend fun generateTextOnlyReply(command: String): Pair<String, String> {
        val personalityPrefix = com.arya.ai.tools.PersonalityStore.getPersonalityPrompt(applicationContext)
        com.arya.ai.tools.PersonalityStore.incrementInteraction(applicationContext)
        // Only the tools relevant to this specific voice command go into the prompt —
        // sending all 109 tools every time makes for a much bigger, slower prompt. See
        // AryaToolRegistry.relevantTools().
        val prefs = com.arya.ai.util.PreferencesManager(applicationContext)
        val currentInfo = com.arya.ai.util.SimpleRagHelper(applicationContext).getCurrentInfoRaw()
        val personaWithInfo = "$personalityPrefix Tum Arya ho, ek voice assistant. Chhota, bolne-jaisa jawab do — ek ya do sentence. " +
            "Jawab hamesha Hindi/Hinglish me bolo (Roman script), kabhi pure English me mat bolo." +
            if (currentInfo.isNullOrBlank()) "" else " Current-affairs reference: $currentInfo"
        val systemPrompt = ToolCallParser.buildSystemPrompt(
            AryaToolRegistry.relevantTools(command, maxTools = prefs.maxToolsPerRequest),
            persona = personaWithInfo
        )
        val result = withContext(Dispatchers.IO) {
            // Voice mode always forces Gemini — same reasoning as the streamed path above.
            com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, command, systemPrompt, forceGeminiOnly = true)
        }
        val toolCall = ToolCallParser.parseToolCall(result.text)
        return if (toolCall != null) {
            AryaToolRegistry.execute(applicationContext, toolCall) to com.arya.ai.util.AvatarEmotion.NEUTRAL
        } else {
            result.text.trim().ifBlank { "Samajh nahi paayi, phir se try karo." } to
                com.arya.ai.util.AvatarEmotion.sanitize(result.emotion)
        }
    }



    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    // ---- Foreground notification (required to keep the service alive + mic on) ----

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Arya — Hey Arya active")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hey Arya wake word", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
