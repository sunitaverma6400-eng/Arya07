package com.arya.ai.util

import android.Manifest
import com.arya.ai.inference.ToolCall
import com.arya.ai.tools.AryaToolRegistry
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * True real-time voice conversation via Gemini's own Live API (BidiGenerateContent), proxied
 * through the relay's `/v1/live` endpoint (see arya-relay `app.py`, FIXES_LOG.md Phase 30) —
 * this is what actually gets close to "Gemini-Live-jaisa" from chat history, as opposed to
 * [com.arya.ai.service.WakeWordService]'s sentence-pipelined-but-still-turn-based approach
 * from the phase before it.
 *
 * Continuous mic audio streams out over the WebSocket the whole time this is running (no
 * client-side "wait for silence then upload" step — Gemini does its own server-side voice
 * activity detection), and audio replies stream back and start playing as soon as the first
 * chunk arrives. Interruption ("barge-in") is handled by Gemini itself server-side — when
 * [serverContent.interrupted] arrives, playback is flushed immediately, which is more
 * accurate than [com.arya.ai.util.VoiceActivityDetector]'s old local-mic-based guess.
 *
 * =========================== READ BEFORE RELYING ON THIS ===========================
 * This is a first pass, wired end-to-end from Kotlin -> relay -> Gemini using the current
 * (as of Aug 2026) documented Live API wire protocol, but it has NOT been run against a real
 * device — there is no way to build/run/test Android code, a live WebSocket connection, or
 * real audio hardware from this environment. Things that specifically need real-device
 * verification and likely at least one debugging round:
 *  - Whether Render's free-tier proxy setup actually keeps a WebSocket connection open
 *    correctly end-to-end (some hosts buffer/close idle WS connections early).
 *  - Exact audio buffer sizing / chunking — too-small chunks waste bandwidth on JSON+base64
 *    overhead, too-large chunks add latency; TARGET_CHUNK_MS below is a reasonable starting
 *    guess, not a tuned value.
 *  - Whether AudioTrack's MODE_STREAM playback keeps up with the incoming 24kHz stream
 *    without underrun glitches on real hardware.
 *  - Gemini Live's exact JSON field names can and do change between preview model versions
 *    (see the model-string churn noted in FIXES_LOG.md Phase 30) — if this stops working,
 *    checking Google's current Live API docs for field-name drift is the first thing to try.
 *  - Live tool-calling is wired to AryaToolRegistry below. Real-device verification is still
 *    required for individual Android permissions and provider/model wire-format drift.
 * =====================================================================================
 */
class GeminiLiveSession(
    private val context: Context,
    private val relayWsBaseUrl: String, // e.g. "wss://arya-relay.onrender.com"
    private val appSecret: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected() {}
        fun onDisconnected(reason: String) {}
        fun onError(message: String) {}
        /** Fired once per turn as Gemini's own transcript of what it said arrives (for
         *  showing subtitles/history in the UI — same idea as LiveConversationState). */
        fun onOutputTranscript(text: String) {}
        fun onInputTranscript(text: String) {}
    }

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var micThread: Thread? = null
    @Volatile private var setupComplete = false
    @Volatile private var toolCallPending = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout — this is a long-lived stream
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val INPUT_SAMPLE_RATE = 16_000  // Gemini requires exactly this for input
        private const val OUTPUT_SAMPLE_RATE = 24_000 // Gemini always sends output at this rate
        private const val TARGET_CHUNK_MS = 100L       // ~100ms chunks, a reasonable starting point (see class doc)
    }

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("Mic permission nahi hai")
            return
        }
        running = true
        setupComplete = false
        toolCallPending = false
        setupAudioTrack()
        connectWebSocket()
    }

    fun stop() {
        running = false
        setupComplete = false
        toolCallPending = false
        micThread?.interrupt()
        micThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        webSocket?.close(1000, "client stop")
        webSocket = null
    }

    /** Converts Arya's native tool catalogue into Gemini Live function declarations.
     * Live tool calls execute on-device; the relay never receives the tool arguments or results. */
    private fun buildLiveToolDeclarations(): org.json.JSONArray {
        val declarations = org.json.JSONArray()
        for (tool in AryaToolRegistry.ALL_TOOLS) {
            if (!com.arya.ai.inference.ToolGuard.isLiveToolAllowed(tool.name)) continue
            val fn = JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                val properties = JSONObject()
                for (param in tool.params) {
                    val schemaType = when (param.type.substringBefore(" ").lowercase()) {
                        "boolean" -> "boolean"
                        "number", "integer" -> "number"
                        else -> "string"
                    }
                    properties.put(param.name, JSONObject().apply {
                        put("type", schemaType)
                        put("description", param.description)
                    })
                }
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                })
            }
            declarations.put(fn)
        }
        return declarations
    }

    private fun connectWebSocket() {
        val url = relayWsBaseUrl.removeSuffix("/") + "/v1/live"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-App-Secret", appSecret)
            .addHeader("X-Client-Id", PreferencesManager(context).relayClientId)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
                webSocket.send(JSONObject().apply {
                    put("aryaLiveSetup", JSONObject().apply {
                        put("functionDeclarations", buildLiveToolDeclarations())
                    })
                }.toString())
                // Wait for Gemini's setupComplete before sending realtimeInput.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                running = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                running = false
                listener.onError(t.message ?: "WebSocket error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                running = false
                listener.onDisconnected(reason)
            }
        })
    }

    /** Continuously captures mic audio at 16kHz mono PCM16 and streams it out as
     *  realtimeInput messages — no "record a full utterance then upload" step; Gemini's own
     *  server-side VAD figures out when the user has started/stopped talking. */
    private fun startMicStreaming(ws: WebSocket) {
        micThread = thread(name = "gemini-live-mic") {
            val minBuf = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                listener.onError("AudioRecord buffer size invalid on this device")
                return@thread
            }
            val chunkSamples = (INPUT_SAMPLE_RATE * TARGET_CHUNK_MS / 1000).toInt()
            val chunkBytes = chunkSamples * 2 // 16-bit = 2 bytes/sample
            val bufferSize = maxOf(minBuf, chunkBytes * 2)
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                audioRecord?.startRecording()
            } catch (e: Exception) {
                listener.onError("Mic start fail: ${e.message}")
                return@thread
            }

            val buf = ByteArray(chunkBytes)
            while (running && !Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read <= 0) continue
                // Gemini 3.1 Live function calling is synchronous: while a tool call is
                // pending, realtimeInput must be gated until functionResponses are sent.
                if (!setupComplete || toolCallPending) continue
                val b64 = android.util.Base64.encodeToString(buf, 0, read, android.util.Base64.NO_WRAP)
                val message = JSONObject().apply {
                    put("realtimeInput", JSONObject().apply {
                        put("audio", JSONObject().apply {
                            put("data", b64)
                            put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                        })
                    })
                }
                try {
                    ws.send(message.toString())
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun setupAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    /** Parses one server-side JSON event. See Gemini Live's BidiGenerateContent wire format —
     *  top-level keys of interest: setupComplete, serverContent (with modelTurn.parts[] audio/
     *  text and an `interrupted` flag), and toolCall (function calls executed locally). */
    private fun handleServerMessage(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        if (json.has("error")) {
            listener.onError(json.optString("error"))
            return
        }

        // Gemini Live sends setupComplete as an object (e.g. {"setupComplete": {}}),
        // not a boolean. optBoolean() therefore returns false and would leave the mic
        // permanently gated. Accept the documented object form and the boolean form for
        // compatibility with older/alternate relay implementations.
        if (json.has("setupComplete")) {
            val setupValue = json.opt("setupComplete")
            if (setupValue is JSONObject || setupValue is Boolean) {
                setupComplete = if (setupValue is Boolean) setupValue else true
                if (setupComplete && running && micThread?.isAlive != true) {
                    webSocket?.let { startMicStreaming(it) }
                }
                return
            }
        }

        json.optJSONObject("toolCall")?.let { toolCall ->
            handleToolCall(toolCall)
        }

        val serverContent = json.optJSONObject("serverContent") ?: return

        if (serverContent.optBoolean("interrupted", false)) {
            // The user started talking over Arya — Gemini says so itself, so flush whatever
            // we were about to play instead of waiting for a local VAD guess to catch up.
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        }

        serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) listener.onInputTranscript(it)
        }
        serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) listener.onOutputTranscript(it)
        }

        val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
        val parts = modelTurn.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inlineData = part.optJSONObject("inlineData") ?: continue
            val mime = inlineData.optString("mimeType", "")
            if (!mime.startsWith("audio/")) continue
            val b64 = inlineData.optString("data", "")
            if (b64.isEmpty()) continue
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            audioTrack?.write(bytes, 0, bytes.size)
        }
    }

    /** Executes Gemini Live function calls locally and sends the required functionResponses
     *  back through the same WebSocket. Tool results never go through the relay. */
    private fun handleToolCall(toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        if (calls.length() == 0) return
        // Gemini 3.1 Live function calling is synchronous. Stop sending realtimeInput
        // while the tool is executing; otherwise the server can reject input during the
        // pending tool call.
        toolCallPending = true
        thread(name = "gemini-live-tools") {
            val responses = org.json.JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val name = call.optString("name", "")
                if (name.isBlank()) continue
                val argsObj = call.optJSONObject("args") ?: JSONObject()
                val args = mutableMapOf<String, String>()
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = argsObj.opt(key)
                    args[key] = when (value) {
                        null, JSONObject.NULL -> ""
                        is JSONObject, is org.json.JSONArray -> value.toString()
                        else -> value.toString()
                    }
                }
                val callId = call.optString("id", "")
                val result = if (!com.arya.ai.inference.ToolGuard.isLiveToolAllowed(name)) {
                    "❌ Live mode me '$name' tool allowed nahi hai."
                } else try {
                    kotlinx.coroutines.runBlocking {
                        AryaToolRegistry.execute(context, ToolCall(name, args))
                    }
                } catch (e: Exception) {
                    "❌ Tool '$name' fail hua: ${e.message ?: "unknown error"}"
                }
                responses.put(JSONObject().apply {
                    if (callId.isNotBlank()) put("id", callId)
                    put("name", name)
                    put("response", JSONObject().apply { put("result", result) })
                })
            }
            if (responses.length() > 0 && running) {
                webSocket?.send(JSONObject().apply {
                    put("toolResponse", JSONObject().apply {
                        put("functionResponses", responses)
                    })
                }.toString())
            }
            toolCallPending = false
        }
    }

}
