package com.arya.ai.util

import com.arya.ai.BuildConfig
import com.arya.ai.data.OnlineModels
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OnlineChatResult(
    val text: String,
    val providerUsed: String,
    val modelUsed: String,
    // One of AvatarEmotion's tags ("neutral","happy","sad","angry","surprised","caring",
    // "playful","serious") — the relay asks the LLM to prefix every reply with
    // "[emotion:xxx]" and strips it back out server-side, returning it as its own field.
    // Defaults to "neutral" for any relay build that predates this (missing field/older
    // deployed relay), so this never breaks against an out-of-date server.
    val emotion: String = "neutral"
)

/**
 * Talks to Arya Relay — a small backend Sudhanshu runs (see arya-relay/), which is the
 * ONLY place Groq / Gemini / OpenRouter API keys ever live. This app never stores or sees
 * those provider keys; it just calls the relay, which calls the real provider server-side
 * and hands back the text. That's what makes online chat (Groq/Gemini/OpenRouter) work
 * out of the box for every install, with zero per-user API key setup — and why removing
 * this relay call (or pointing RELAY_URL elsewhere) is the only way to change that.
 *
 * Used for every reply Arya gives — she has no on-device model, so this is the only
 * generation path, for both plain chat and current/real-world info questions.
 *
 * Fallback chain, all "andar hi andar" (internal, no user action needed):
 *  1. For each provider in order (Groq -> Gemini -> OpenRouter):
 *     2. Try the user's *selected* free model for that provider first via the relay.
 *     3. If that call fails, fall through the rest of that provider's free-model list
 *        (see [OnlineModels]) before moving to the next provider.
 *
 * Only stops and throws once every provider/model combination has been exhausted.
 */
object OnlineChatHelper {

    private const val TIMEOUT_MS = 30_000

    // Bug fix (see chat history — user reported the chat bubble getting permanently stuck on
    // "..." with no reply and no error, for several minutes): the provider/model fallback loop
    // below had a per-attempt timeout (TIMEOUT_MS) but NO cap on the *total* time across all
    // ~18 provider/model combinations. If the relay is unreachable or every attempt genuinely
    // times out (e.g. Render free-tier cold start colliding with a flaky attempt, or the relay
    // being down), worst case was 18 attempts x 30s = up to ~9 minutes of silence before any
    // error ever surfaced — indistinguishable from a permanent hang to the user. Now the whole
    // fallback chain is capped at MAX_TOTAL_BUDGET_MS: once the budget is spent, remaining
    // attempts are skipped and whatever error was last seen is thrown immediately, so
    // ChatViewModel's catch block always shows a real error within a bounded, reasonable time
    // instead of leaving the "…" bubble and the stop button up indefinitely.
    private const val MAX_TOTAL_BUDGET_MS = 45_000

    fun generateOnlineResponse(
        prefs: PreferencesManager,
        prompt: String,
        systemPrompt: String
    ): OnlineChatResult {
        val relayUrl = BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) {
            throw IllegalStateException(
                "Arya Relay configure nahi hai is build me (RELAY_URL khaali hai). " +
                    "ARYA_RELAY_URL / ARYA_RELAY_APP_SECRET set karke app rebuild karo."
            )
        }

        // Randomized (not fixed Groq-first) so usage — and free-tier quota consumption —
        // spreads roughly evenly across all three providers instead of Groq alone
        // absorbing every request while Gemini/OpenRouter keys sit unused.
        val providerOrder = listOf(ApiProvider.GROQ, ApiProvider.GEMINI, ApiProvider.OPENROUTER).shuffled()

        val startedAt = System.currentTimeMillis()
        var lastError: Exception? = null
        var timedOutEarly = false

        outer@ for (provider in providerOrder) {
            val allModels = OnlineModels.forProvider(provider)
            if (allModels.isEmpty()) continue

            val selectedId = when (provider) {
                ApiProvider.GROQ -> prefs.selectedGroqModel
                ApiProvider.GEMINI -> prefs.selectedGeminiModel
                ApiProvider.OPENROUTER -> prefs.selectedOpenRouterModel
                else -> continue
            }
            // If the saved selection doesn't match any current catalog entry (e.g. a model
            // rotated out since it was picked), this just falls back to trying the whole
            // catalog in its normal order instead of a stale/unknown id.
            val orderedModels = allModels.filter { it.id == selectedId } + allModels.filter { it.id != selectedId }

            for (model in orderedModels) {
                if (System.currentTimeMillis() - startedAt >= MAX_TOTAL_BUDGET_MS) {
                    timedOutEarly = true
                    break@outer
                }
                try {
                    val (text, emotion) = callRelay(relayUrl, provider, model.id, prompt, systemPrompt)
                    return OnlineChatResult(text, provider.label, model.id, emotion)
                } catch (e: Exception) {
                    lastError = e // try next model, then next provider
                }
            }
        }

        val reason = if (timedOutEarly)
            "45 second ke andar koi provider jawaab nahi de saka (Render sleeping ho sakta hai, ~30-50s lagte hain jaagne me — dobara try karo)."
        else
            "Arya Relay se koi bhi provider response nahi de saka."
        throw IllegalStateException(reason + " " + (lastError?.message ?: ""))
    }

    /**
     * Same provider/model fallback chain as [generateOnlineResponse], but calls the relay's
     * `/v1/relay/stream` endpoint and invokes [onChunk] with each text delta as it arrives —
     * used by [com.arya.ai.viewmodel.ChatViewModel] to show the reply word-by-word instead of
     * all at once. If a provider/model fails to even start streaming (bad key, model down,
     * etc.) nothing has been shown to the user yet, so it falls through to the next one exactly
     * like the non-streaming path — [onChunk] is only ever called for the model that actually
     * ends up answering.
     */
    fun streamOnlineResponse(
        prefs: PreferencesManager,
        prompt: String,
        systemPrompt: String,
        // Fired as soon as the relay resolves the [emotion:xxx] tag — before any text delta —
        // so the caller (ChatViewModel) can set the avatar's expression and pick the
        // ElevenLabs emotion the moment speech starts, not after the whole reply lands.
        onEmotion: (String) -> Unit = {},
        onChunk: (String) -> Unit
    ): OnlineChatResult {
        val relayUrl = BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) {
            throw IllegalStateException(
                "Arya Relay configure nahi hai is build me (RELAY_URL khaali hai). " +
                    "ARYA_RELAY_URL / ARYA_RELAY_APP_SECRET set karke app rebuild karo."
            )
        }
        val streamUrl = relayUrl.removeSuffix("/v1/relay").removeSuffix("/") + "/v1/relay/stream"

        val providerOrder = listOf(ApiProvider.GROQ, ApiProvider.GEMINI, ApiProvider.OPENROUTER).shuffled()

        val startedAt = System.currentTimeMillis()
        var lastError: Exception? = null
        var timedOutEarly = false

        outer@ for (provider in providerOrder) {
            val allModels = OnlineModels.forProvider(provider)
            if (allModels.isEmpty()) continue

            val selectedId = when (provider) {
                ApiProvider.GROQ -> prefs.selectedGroqModel
                ApiProvider.GEMINI -> prefs.selectedGeminiModel
                ApiProvider.OPENROUTER -> prefs.selectedOpenRouterModel
                else -> continue
            }
            val orderedModels = allModels.filter { it.id == selectedId } + allModels.filter { it.id != selectedId }

            for (model in orderedModels) {
                if (System.currentTimeMillis() - startedAt >= MAX_TOTAL_BUDGET_MS) {
                    timedOutEarly = true
                    break@outer
                }
                try {
                    val (text, emotion) = callRelayStream(streamUrl, provider, model.id, prompt, systemPrompt, onEmotion, onChunk)
                    return OnlineChatResult(text, provider.label, model.id, emotion)
                } catch (e: Exception) {
                    lastError = e // try next model, then next provider
                }
            }
        }

        val reason = if (timedOutEarly)
            "45 second ke andar koi provider jawaab nahi de saka (Render sleeping ho sakta hai, ~30-50s lagte hain jaagne me — dobara try karo)."
        else
            "Arya Relay se koi bhi provider stream response nahi de saka."
        throw IllegalStateException(reason + " " + (lastError?.message ?: ""))
    }

    private fun callRelayStream(
        streamUrl: String,
        provider: ApiProvider,
        model: String,
        prompt: String,
        systemPrompt: String,
        onEmotion: (String) -> Unit,
        onChunk: (String) -> Unit
    ): Pair<String, String> {
        val providerParam = when (provider) {
            ApiProvider.GROQ -> "groq"
            ApiProvider.GEMINI -> "gemini"
            ApiProvider.OPENROUTER -> "openrouter"
            else -> throw IllegalStateException("${provider.label} isn't a relay chat provider")
        }

        val body = JSONObject().apply {
            put("provider", providerParam)
            put("model", model)
            put("prompt", prompt)
            put("systemPrompt", systemPrompt)
        }

        val connection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-App-Secret", BuildConfig.RELAY_APP_SECRET)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode !in 200..299) {
            val err = connection.errorStream?.bufferedReader()?.readText()
            throw IllegalStateException("Relay stream HTTP ${connection.responseCode} ($provider/$model): $err")
        }

        // NDJSON: {"emotion":...} once at the start, then one {"delta"|"done"|"error": ...}
        // object per line — see the relay's /v1/relay/stream doc comment for the exact
        // contract. "emotion" always arrives before the first "delta".
        val full = StringBuilder()
        var sawDone = false
        var emotion = "neutral"
        connection.inputStream.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val json = try { JSONObject(line) } catch (e: Exception) { return@forEachLine }
            if (json.has("error")) {
                throw IllegalStateException("Relay stream error ($provider/$model): ${json.optString("error")}")
            }
            if (json.has("emotion")) {
                emotion = json.optString("emotion", "neutral")
                onEmotion(emotion)
            }
            val delta = json.optString("delta", "")
            if (delta.isNotEmpty()) {
                full.append(delta)
                onChunk(delta)
            }
            if (json.optBoolean("done", false)) sawDone = true
        }

        if (!sawDone || full.isEmpty()) {
            throw IllegalStateException("Relay stream ended without a result ($provider/$model)")
        }
        return full.toString() to emotion
    }

    private fun callRelay(
        relayUrl: String,
        provider: ApiProvider,
        model: String,
        prompt: String,
        systemPrompt: String
    ): Pair<String, String> {
        val providerParam = when (provider) {
            ApiProvider.GROQ -> "groq"
            ApiProvider.GEMINI -> "gemini"
            ApiProvider.OPENROUTER -> "openrouter"
            else -> throw IllegalStateException("${provider.label} isn't a relay chat provider")
        }

        val body = JSONObject().apply {
            put("provider", providerParam)
            put("model", model)
            put("prompt", prompt)
            put("systemPrompt", systemPrompt)
        }

        val connection = (URL(relayUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-App-Secret", BuildConfig.RELAY_APP_SECRET)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode !in 200..299) {
            val err = connection.errorStream?.bufferedReader()?.readText()
            throw IllegalStateException("Relay HTTP ${connection.responseCode} ($provider/$model): $err")
        }

        val responseText = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)
        return json.getString("text") to json.optString("emotion", "neutral")
    }
}
