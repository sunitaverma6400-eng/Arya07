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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        private val WAKE_PHRASE = Regex("\\b(hello|hi|hii|hey|ok)\\s+arya\\b", RegexOption.IGNORE_CASE)

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
    private val vadCommandRecorder by lazy { com.arya.ai.util.VadCommandRecorder(this) }
    private var mode = Mode.WAKE_WORD
    private var running = false
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
            tts?.language = Locale("hi", "IN").let { if (tts?.isLanguageAvailable(it) ?: -2 >= 0) it else Locale.US }
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) { isSpeaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { isSpeaking = false }
            })
        }
    }

    private fun speak(text: String, thenListenForCommand: Boolean = false) {
        recognizer?.stopListening()
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.SPEAKING
        val utteranceId = "arya_${System.currentTimeMillis()}"
        speakAudio(text, utteranceId)
        updateNotification(text)
        startBargeInListening()
        // Give TTS a moment to actually say it before we open the mic again.
        val estimatedMs = (text.length * 55L).coerceIn(600, 6000)
        pendingJob?.cancel()
        pendingJob = scope.launch {
            kotlinx.coroutines.delay(estimatedMs)
            stopBargeInListening()
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
    private fun speakAudio(text: String, utteranceId: String) {
        isSpeaking = true
        scope.launch(Dispatchers.IO) {
            val played = tryPlayElevenLabs(text)
            if (!played) {
                withContext(Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
    }

    private fun tryPlayElevenLabs(text: String): Boolean {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return false
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val body = org.json.JSONObject().apply { put("text", text) }
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
            elevenLabsPlayer = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    isSpeaking = false
                    it.release()
                    file.delete()
                }
                setOnErrorListener { mp, _, _ ->
                    isSpeaking = false
                    mp.release()
                    file.delete()
                    true
                }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
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
                    speak("Haan, bolo", thenListenForCommand = true)
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
            speak("Is device par awaaz samajh nahi paa rahi — text me type karke baat kar sakte ho", thenListenForCommand = false)
            return
        }
        recognizer = freshRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
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
                    speak("Haan, bolo", thenListenForCommand = true)
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
            Mode.COMMAND -> speak("Kuch samajh nahi aaya, phir se \"Hey Arya\" bolo", thenListenForCommand = false)
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
        updateNotification("Soch rahi hoon: \"$command\"")
        com.arya.ai.util.LiveConversationState.status.value = com.arya.ai.util.LiveStatus.THINKING
        com.arya.ai.util.LiveConversationState.lastUserText.value = command
        // A recent camera/screen frame (LiveConversationScreen's vision toggles) — see
        // VisionFrameProvider. Only used if it's fresh; stale frames are ignored.
        val visionFrame = com.arya.ai.util.VisionFrameProvider.freshFrame()

        if (com.arya.ai.BuildConfig.RELAY_URL.isBlank()) {
            speak("Arya Relay configure nahi hai is build me — online jawab nahi mil sakta.", thenListenForCommand = false)
            openApp()
            return
        }
        scope.launch {
            try {
                val reply = when {
                    // A camera/screen frame is attached — Gemini vision via the relay.
                    visionFrame != null ->
                        com.arya.ai.tools.VisionRelay.describeImage(visionFrame, command)
                            ?: generateTextOnlyReply(command)
                    else -> generateTextOnlyReply(command)
                }
                com.arya.ai.util.LiveConversationState.lastAryaText.value = reply
                speak(reply, thenListenForCommand = liveMode)
            } catch (e: Exception) {
                speak("⚠️ Error aa gaya: ${e.message}", thenListenForCommand = liveMode)
            }
        }
    }

    /** The text-only (+ tool-calling) path — used whenever there's no vision frame to attach,
     *  or the vision relay call fails. Always goes through the free online relay now. */
    private suspend fun generateTextOnlyReply(command: String): String {
        val personalityPrefix = com.arya.ai.tools.PersonalityStore.getPersonalityPrompt(applicationContext)
        com.arya.ai.tools.PersonalityStore.incrementInteraction(applicationContext)
        // Only the tools relevant to this specific voice command go into the prompt —
        // sending all 109 tools every time makes for a much bigger, slower prompt. See
        // AryaToolRegistry.relevantTools().
        val prefs = com.arya.ai.util.PreferencesManager(applicationContext)
        val currentInfo = com.arya.ai.util.SimpleRagHelper(applicationContext).getCurrentInfoRaw()
        val personaWithInfo = "$personalityPrefix Tum Arya ho, ek voice assistant. Chhota, bolne-jaisa jawab do — ek ya do sentence." +
            if (currentInfo.isNullOrBlank()) "" else " Current-affairs reference: $currentInfo"
        val systemPrompt = ToolCallParser.buildSystemPrompt(
            AryaToolRegistry.relevantTools(command, maxTools = prefs.maxToolsPerRequest),
            persona = personaWithInfo
        )
        val raw = withContext(Dispatchers.IO) {
            com.arya.ai.util.OnlineChatHelper.generateOnlineResponse(prefs, command, systemPrompt).text
        }
        val toolCall = ToolCallParser.parseToolCall(raw)
        return if (toolCall != null) {
            AryaToolRegistry.execute(applicationContext, toolCall)
        } else {
            raw.trim().ifBlank { "Samajh nahi paayi, phir se try karo." }
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
