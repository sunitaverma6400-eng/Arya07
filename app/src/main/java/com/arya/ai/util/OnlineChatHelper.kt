package com.arya.ai.util

import com.arya.ai.inference.ToolDefinition

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
 * Picks which provider(s) to try based on what the message/context actually needs (see chat
 * history — "pic/voice/video/live/image/music ke liye hamesha Gemini, coding ke liye
 * OpenRouter ki best model"). Two independent decisions live here:
 *
 * 1. **Multimodal-adjacent contexts always force Gemini-only, no fallback to Groq/OpenRouter
 *    at all.** [com.arya.ai.service.WakeWordService] (voice commands, Live mode) and
 *    [com.arya.ai.tools.VisionRelay] (camera/screen/photo), `/v1/imagegen`, `/v1/videogen`
 *    (Veo), `/v1/musicgen` (Lyria) — all of these now go through Gemini's own key pool only.
 *    Said plainly: this is a real reliability trade, not a free lunch — Groq/OpenRouter
 *    exist as this app's ENTIRE safety net for when Gemini's daily free-tier quota runs out
 *    or a request fails; forcing Gemini-only for these contexts means if Gemini's quota is
 *    exhausted, voice/Live/image/video/music genuinely stop working for the rest of the day
 *    instead of degrading to a text-only fallback. That's what was explicitly asked for, so
 *    that's what this does — but it's worth knowing before being surprised by it.
 * 2. **Coding questions go straight to OpenRouter's dedicated coding model
 *    ([OnlineModels.OPENROUTER_BEST_CODING_ID]) first**, ahead of even Gemini — not just
 *    "OpenRouter before Groq" like the general heuristic below, but that ONE specific model
 *    tried before anything else, since the whole point was Arya recognizing a coding question
 *    and answering from that model directly rather than Gemini's general-purpose one.
 *
 * The plain-text (non-forced, non-coding) case keeps Phase 31/32's behavior: Gemini first,
 * Groq/OpenRouter randomized as fallback for QUICK/GENERAL messages.
 *
 * All of this is still a keyword heuristic, not a real classifier call — seeing "kotlin" or
 * "bug" in the message, not asking a model to categorize it first (that would cost a whole
 * extra network round-trip just to decide who to ask).
 */
/**
 * OpenRouter's model list, but live: [ModelCatalog] discovers OpenRouter's actual current
 * free-tier catalog at runtime (via the relay's `/v1/openrouter_models` — see arya-relay's
 * app.py) instead of only trusting the hand-typed [com.arya.ai.data.OnlineModels.OPENROUTER]
 * list, which — per that file's own doc comments — has already gone stale multiple times as
 * OpenRouter rotates free models in/out. [ModelCatalog.peek] never touches the network (see
 * its doc comment on why OnlineChatHelper can't hold a Context), so this is instant and safe
 * to call on every message; it's just empty until something else (app startup warmup, the
 * `list_free_models`/`refresh_model_catalog` tools, the Online Models screen) has populated
 * it at least once this process. Discovered models are put FIRST (freshest signal wins), the
 * static list appended after and de-duplicated by id — so even a never-refreshed process
 * degrades exactly to the old hardcoded-list behavior, never to nothing.
 */
private fun liveOpenRouterModels(staticModels: List<com.arya.ai.data.OnlineModel>): List<com.arya.ai.data.OnlineModel> {
    val discovered = ModelCatalog.peekAsOnlineModels()
    if (discovered.isEmpty()) return staticModels
    val discoveredIds = discovered.map { it.id }.toSet()
    return discovered + staticModels.filter { it.id !in discoveredIds }
}

private fun orderedModelsFor(
    provider: ApiProvider, intent: QueryIntent, allModels: List<com.arya.ai.data.OnlineModel>, selectedId: String
): List<com.arya.ai.data.OnlineModel> = ModelRouter.orderModels(provider, intent, allModels, selectedId)


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
    private const val MAX_TOTAL_BUDGET_MS = 75_000

    private class RelayCallException(message: String, val statusCode: Int = -1, val retryableProvider: Boolean = false) : Exception(message)

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

    fun generateOnlineResponse(
        prefs: PreferencesManager,
        prompt: String,
        systemPrompt: String,
        forceGeminiOnly: Boolean = false,
        // Bug fix (see chat history — user reported every model "forgetting" the conversation
        // after one or two exchanges, most noticeable when the fallback chain switched
        // providers/models, but actually present on EVERY call before this fix, even with
        // the same model answering twice in a row): prior turns, oldest first, each a
        // (role, content) pair where role is "user" or "assistant". Defaults to empty so every
        // existing single-shot caller (Life Simulator's one-off analysis, Tiny Garden, Prompt
        // Lab, etc.) keeps behaving exactly as before — only ChatViewModel's normal chat flow
        // passes real history now.
        history: List<Pair<String, String>> = emptyList(),
        tools: List<ToolDefinition> = emptyList()
    ): OnlineChatResult {
        val relayUrl = BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) {
            throw IllegalStateException(
                "Arya Relay configure nahi hai is build me (RELAY_URL khaali hai). " +
                    "ARYA_RELAY_URL / ARYA_RELAY_APP_SECRET set karke app rebuild karo."
            )
        }

        // See providerOrderFor()'s doc comment — Gemini-only when forceGeminiOnly (voice/Live
        // mode), OpenRouter's coding model first for coding questions, otherwise Phase 32's
        // Gemini-first/Groq-first heuristic.
        val intent = ModelRouter.classify(prompt)
        val providerOrder = ModelRouter.providerOrder(prompt, forceGeminiOnly)

        val startedAt = System.currentTimeMillis()
        var lastError: Exception? = null
        var timedOutEarly = false

        outer@ for (provider in providerOrder) {
            val allModels = OnlineModels.forProvider(provider).let { if (provider == ApiProvider.OPENROUTER) liveOpenRouterModels(it) else it }
            if (allModels.isEmpty()) continue

            val selectedId = when (provider) {
                ApiProvider.GROQ -> prefs.selectedGroqModel
                ApiProvider.GEMINI -> prefs.selectedGeminiModel
                ApiProvider.OPENROUTER -> prefs.selectedOpenRouterModel
                else -> continue
            }
            // If the saved selection doesn't match any current catalog entry (e.g. a model
            // rotated out since it was picked), this just falls back to trying the whole
            // catalog in its normal order instead of a stale/unknown id. For a CODING message
            // against OpenRouter, the dedicated coding model wins regardless of that saved
            // preference — see orderedModelsFor()'s doc comment.
            val orderedModels = orderedModelsFor(provider, intent, allModels, selectedId)

            for (model in orderedModels) {
                if (System.currentTimeMillis() - startedAt >= MAX_TOTAL_BUDGET_MS) {
                    timedOutEarly = true
                    break@outer
                }
                try {
                    val (text, emotion) = callRelay(relayUrl, prefs, provider, model.id, prompt, systemPrompt, history, tools)
                    return OnlineChatResult(text, provider.label, model.id, emotion)
                } catch (e: Exception) {
                    lastError = e
                    if (e is RelayCallException && e.retryableProvider) {
                        continue@outer
                    }
                }
            }
        }

        val reason = if (timedOutEarly)
            "75 second ke andar koi provider jawaab nahi de saka — provider/relay temporary unavailable ho sakta hai."
        else if (forceGeminiOnly)
            "Gemini se jawaab nahi mila (is context ke liye sirf Gemini try hoti hai, koi fallback nahi)."
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
        forceGeminiOnly: Boolean = false,
        history: List<Pair<String, String>> = emptyList(),  // see generateOnlineResponse's doc comment
        tools: List<ToolDefinition> = emptyList(),
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

        // Same reasoning as generateOnlineResponse above (see providerOrderFor's doc comment).
        val intent = ModelRouter.classify(prompt)
        val providerOrder = ModelRouter.providerOrder(prompt, forceGeminiOnly)

        val startedAt = System.currentTimeMillis()
        var lastError: Exception? = null
        var timedOutEarly = false

        outer@ for (provider in providerOrder) {
            val allModels = OnlineModels.forProvider(provider).let { if (provider == ApiProvider.OPENROUTER) liveOpenRouterModels(it) else it }
            if (allModels.isEmpty()) continue

            val selectedId = when (provider) {
                ApiProvider.GROQ -> prefs.selectedGroqModel
                ApiProvider.GEMINI -> prefs.selectedGeminiModel
                ApiProvider.OPENROUTER -> prefs.selectedOpenRouterModel
                else -> continue
            }
            val orderedModels = orderedModelsFor(provider, intent, allModels, selectedId)

            for (model in orderedModels) {
                if (System.currentTimeMillis() - startedAt >= MAX_TOTAL_BUDGET_MS) {
                    timedOutEarly = true
                    break@outer
                }
                try {
                    val (text, emotion) = callRelayStream(streamUrl, prefs, provider, model.id, prompt, systemPrompt, onEmotion, history, tools, onChunk)
                    return OnlineChatResult(text, provider.label, model.id, emotion)
                } catch (e: Exception) {
                    lastError = e
                    if (e is RelayCallException && e.retryableProvider) {
                        continue@outer
                    }
                }
            }
        }

        val reason = if (timedOutEarly)
            "75 second ke andar koi provider jawaab nahi de saka — provider/relay temporary unavailable ho sakta hai."
        else
            "Arya Relay se koi bhi provider stream response nahi de saka."
        throw IllegalStateException(reason + " " + (lastError?.message ?: ""))
    }

    /** Builds the `"history"` JSON array both [callRelay] and [callRelayStream] send —
     *  factored out so the two stay in sync instead of copy-pasted. */
    private fun historyToJson(history: List<Pair<String, String>>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        history.forEach { (role, content) ->
            if (content.isNotBlank()) {
                arr.put(JSONObject().apply {
                    put("role", if (role == "assistant") "assistant" else "user")
                    put("content", content)
                })
            }
        }
        return arr
    }

    private fun toolsToJson(tools: List<ToolDefinition>): org.json.JSONArray {
        val array = org.json.JSONArray()
        tools.take(20).forEach { tool ->
            val obj = org.json.JSONObject()
                .put("name", tool.name)
                .put("description", tool.description.take(1000))
            val params = org.json.JSONArray()
            tool.params.take(12).forEach { p ->
                params.put(org.json.JSONObject()
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("description", p.description.take(500)))
            }
            obj.put("params", params)
            array.put(obj)
        }
        return array
    }

    private fun callRelayStream(
        streamUrl: String,
        prefs: PreferencesManager,
        provider: ApiProvider,
        model: String,
        prompt: String,
        systemPrompt: String,
        onEmotion: (String) -> Unit,
        history: List<Pair<String, String>>,
        tools: List<ToolDefinition>,
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
            if (history.isNotEmpty()) put("history", historyToJson(history))
            if (tools.isNotEmpty()) put("tools", toolsToJson(tools))
        }

        val connection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-App-Secret", BuildConfig.RELAY_APP_SECRET)
            setRequestProperty("X-Client-Id", prefs.relayClientId)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode !in 200..299) {
            val status = connection.responseCode
            val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
            val providerUnavailable = status == 429 || status == 502 || status == 503 || status == 504
            throw RelayCallException(
                "Relay stream HTTP $status ($provider/$model): $err",
                statusCode = status,
                retryableProvider = providerUnavailable
            )
        }

        // NDJSON: {"emotion":...} once at the start, then {"delta"|"done"|"error": ...}.
        // Step 14: a Groq/OpenAI-compatible native tool call is normalized by the relay into
        // ONE complete delta containing {"tool":"...","args":{...}}. It is never streamed
        // piece-by-piece, so ToolCallParser can consume it without exposing raw partial JSON.
        // "emotion" always arrives before the first "delta".
        val full = StringBuilder()
        var sawDone = false
        var emotion = "neutral"
        connection.inputStream.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val json = try { JSONObject(line) } catch (e: Exception) { return@forEachLine }
            if (json.has("error")) {
                val upstreamStatus = json.optInt("upstream_status", -1)
                val retryable = json.optBoolean("retryable", false)
                throw RelayCallException(
                    "Relay stream error ($provider/$model): ${json.optString("error")}",
                    statusCode = upstreamStatus,
                    retryableProvider = retryable
                )
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
        prefs: PreferencesManager,
        provider: ApiProvider,
        model: String,
        prompt: String,
        systemPrompt: String,
        history: List<Pair<String, String>> = emptyList(),
        tools: List<ToolDefinition> = emptyList()
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
            if (history.isNotEmpty()) put("history", historyToJson(history))
            if (tools.isNotEmpty()) put("tools", toolsToJson(tools))
        }

        val connection = (URL(relayUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-App-Secret", BuildConfig.RELAY_APP_SECRET)
            setRequestProperty("X-Client-Id", prefs.relayClientId)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode !in 200..299) {
            val status = connection.responseCode
            val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
            val providerUnavailable = status == 429 || status == 502 || status == 503 || status == 504
            throw RelayCallException(
                "Relay HTTP $status ($provider/$model): $err",
                statusCode = status,
                retryableProvider = providerUnavailable
            )
        }

        val responseText = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)
        return json.getString("text") to json.optString("emotion", "neutral")
    }
}
