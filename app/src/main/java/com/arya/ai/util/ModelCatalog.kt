package com.arya.ai.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A single OpenRouter model discovered live from its `/api/v1/models` catalog (via the
 * relay's `/v1/openrouter_models` — see arya-relay/app.py), instead of a hand-typed entry in
 * [com.arya.ai.data.OnlineModels]. [tags] is a cheap keyword classification the relay derives
 * from OpenRouter's own id/name/description text (see relay doc comment) — "coding",
 * "vision", "long_context", "reasoning", "lightweight", or "general" if nothing matched.
 */
data class DiscoveredModel(
    val id: String,
    val name: String,
    val description: String,
    val contextLength: Int?,
    val modalities: List<String>,
    val outputModalities: List<String>,
    val supportedParameters: List<String>,
    val tags: List<String>,
    val supportsTools: Boolean,
    val supportsReasoning: Boolean
)

/**
 * Live discovery of OpenRouter's currently-free model lineup, so Arya doesn't depend on
 * [com.arya.ai.data.OnlineModels.OPENROUTER] — a list that has to be re-typed by hand every
 * time OpenRouter rotates its free tier (see that file's own doc comments for how often
 * that's already happened). This calls the relay (which holds the OpenRouter key and does
 * the actual fetch/tag work — see `/v1/openrouter_models` in arya-relay/app.py), caches the
 * result on-device, and falls back to the static [com.arya.ai.data.OnlineModels.OPENROUTER]
 * list if the relay is unreachable or has never answered yet (first launch, offline, etc.).
 *
 * This intentionally does NOT try to have Arya rewrite/recompile its own Kotlin code —
 * that's not something an installed APK can safely do to itself. What it *can* do, and what
 * this does, is discover which models exist and what they're for at runtime, and pick among
 * them intelligently — the "self-implementing" part is model *selection*, not new code.
 */
object ModelCatalog {

    private const val PREFS = "arya_model_catalog"
    private const val KEY_MODELS_JSON = "models_json"
    private const val KEY_FETCHED_AT = "fetched_at"

    /** Same idea as the relay's own in-memory cache TTL — don't refetch on every app launch,
     *  free-tier lineups don't rotate hourly. A manual "refresh model list" action (or the
     *  `refresh_model_catalog` tool) can force it sooner via [refresh]'s forceRefresh param. */
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Process-memory mirror of the disk cache, kept in sync by every function below that
    // successfully reads/fetches a list. Exists so [OnlineChatHelper] — which has no stable
    // Context reference to read SharedPreferences with (see its own doc comment) — can still
    // consult the discovered catalog via [peek] without needing one, at the cost of it being
    // empty until *some* Context-holding caller (app startup warmup, the model-list tool, the
    // Online Models screen) populates it at least once per process lifetime.
    @Volatile private var memoryCache: List<DiscoveredModel> = emptyList()

    /** Cached list, read straight from disk — safe to call from anywhere (UI thread included),
     *  never touches the network. Empty if nothing has ever been fetched successfully. */
    fun cached(context: Context): List<DiscoveredModel> {
        val raw = prefs(context).getString(KEY_MODELS_JSON, null) ?: return emptyList()
        return parseModels(raw).also { if (it.isNotEmpty()) memoryCache = it }
    }

    /** No-Context peek at whatever's currently in the process-memory mirror — may be empty if
     *  nothing has warmed it yet this process. See [memoryCache] doc comment. */
    fun peek(): List<DiscoveredModel> = memoryCache

    private fun cacheAgeMs(context: Context): Long =
        System.currentTimeMillis() - prefs(context).getLong(KEY_FETCHED_AT, 0L)

    /**
     * Returns the freshest list we can: cached-and-fresh as-is, otherwise tries a live relay
     * fetch, otherwise falls back to whatever's cached (even if stale) or finally an empty
     * list. Never throws — every failure path just degrades to the next-best source.
     */
    suspend fun getFreeOpenRouterModels(context: Context, forceRefresh: Boolean = false): List<DiscoveredModel> =
        withContext(Dispatchers.IO) {
            val cachedList = cached(context)
            if (!forceRefresh && cachedList.isNotEmpty() && cacheAgeMs(context) < CACHE_TTL_MS) {
                return@withContext cachedList
            }
            val fetched = tryFetch(forceRefresh)
            if (fetched != null && fetched.isNotEmpty()) {
                prefs(context).edit()
                    .putString(KEY_MODELS_JSON, modelsToJson(fetched))
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
                memoryCache = fetched
                return@withContext fetched
            }
            // Relay fetch failed (offline, relay down, cold-start timeout) — serve whatever
            // we have cached, even if stale, rather than nothing.
            cachedList
        }

    /** Explicit re-fetch bypassing the cache entirely, e.g. for a "refresh model list" tool
     *  call or settings button — separate from [getFreeOpenRouterModels]'s TTL-based refresh
     *  so a user/Arya-initiated refresh always hits the network once. */
    suspend fun refresh(context: Context): List<DiscoveredModel> = getFreeOpenRouterModels(context, forceRefresh = true)

    /**
     * Plain-blocking equivalent of [getFreeOpenRouterModels], for [OnlineChatHelper] — that
     * file is already always called from a background thread (see ChatViewModel's
     * `withContext(Dispatchers.IO)` wrapping) and is written in a plain-blocking style
     * throughout (no coroutines internally), so this mirrors that instead of forcing a
     * `runBlocking` in the middle of it. Same cache-then-fetch-then-stale-fallback logic as
     * the suspend version above, just without the coroutine wrapper.
     */
    fun getFreeOpenRouterModelsBlocking(context: Context, forceRefresh: Boolean = false): List<DiscoveredModel> {
        val cachedList = cached(context)
        if (!forceRefresh && cachedList.isNotEmpty() && cacheAgeMs(context) < CACHE_TTL_MS) {
            return cachedList
        }
        val fetched = tryFetch(forceRefresh)
        if (fetched != null && fetched.isNotEmpty()) {
            prefs(context).edit()
                .putString(KEY_MODELS_JSON, modelsToJson(fetched))
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply()
            memoryCache = fetched
            return fetched
        }
        return cachedList
    }

    private fun tryFetch(forceRefresh: Boolean): List<DiscoveredModel>? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        val query = if (forceRefresh) "?refresh=1" else ""
        return try {
            val connection = (URL("$base/v1/openrouter_models$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val arr = json.optJSONArray("models") ?: return null
            parseModelsArray(arr)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseModels(rawJsonArray: String): List<DiscoveredModel> = try {
        parseModelsArray(JSONArray(rawJsonArray))
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseModelsArray(arr: JSONArray): List<DiscoveredModel> {
        val out = mutableListOf<DiscoveredModel>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id")
            if (id.isBlank()) continue
            val modalities = mutableListOf<String>()
            m.optJSONArray("modalities")?.let { modArr ->
                for (j in 0 until modArr.length()) modalities.add(modArr.optString(j))
            }
            val outputModalities = mutableListOf<String>()
            m.optJSONArray("output_modalities")?.let { outArr ->
                for (j in 0 until outArr.length()) outputModalities.add(outArr.optString(j))
            }
            val supportedParameters = mutableListOf<String>()
            m.optJSONArray("supported_parameters")?.let { paramArr ->
                for (j in 0 until paramArr.length()) supportedParameters.add(paramArr.optString(j))
            }
            val tags = mutableListOf<String>()
            m.optJSONArray("tags")?.let { tagArr ->
                for (j in 0 until tagArr.length()) tags.add(tagArr.optString(j))
            }
            val supportsTools = m.optBoolean("supports_tools", supportedParameters.any { it.equals("tools", true) || it.equals("tool_choice", true) })
            val supportsReasoning = m.optBoolean("supports_reasoning", supportedParameters.any { it.equals("reasoning", true) || it.equals("reasoning_effort", true) })
            out.add(
                DiscoveredModel(
                    id = id,
                    name = m.optString("name", id),
                    description = m.optString("description", ""),
                    contextLength = if (m.has("context_length") && !m.isNull("context_length")) m.optInt("context_length") else null,
                    modalities = modalities,
                    outputModalities = outputModalities,
                    supportedParameters = supportedParameters,
                    tags = tags.ifEmpty { listOf("general") },
                    supportsTools = supportsTools,
                    supportsReasoning = supportsReasoning
                )
            )
        }
        return out
    }

    private fun modelsToJson(models: List<DiscoveredModel>): String {
        val arr = JSONArray()
        for (model in models) {
            arr.put(JSONObject().apply {
                put("id", model.id)
                put("name", model.name)
                put("description", model.description)
                model.contextLength?.let { put("context_length", it) }
                put("modalities", JSONArray(model.modalities))
                put("output_modalities", JSONArray(model.outputModalities))
                put("supported_parameters", JSONArray(model.supportedParameters))
                put("tags", JSONArray(model.tags))
                put("supports_tools", model.supportsTools)
                put("supports_reasoning", model.supportsReasoning)
            })
        }
        return arr.toString()
    }

    /** First cached model carrying [tag], or null if none/no cache yet. Used by
     *  [OnlineChatHelper] to route e.g. a coding question to a live-discovered "coding"-tagged
     *  free model instead of only the hardcoded [com.arya.ai.data.OnlineModels.OPENROUTER_BEST_CODING_ID]. */
    fun firstWithTag(context: Context, tag: String): DiscoveredModel? =
        cached(context).firstOrNull { tag in it.tags }

    /** No-Context version of [firstWithTag], reading only [memoryCache] — for
     *  [OnlineChatHelper]'s hot path (see [peek]'s doc comment for why it has no Context). */
    fun firstWithTagPeek(tag: String): DiscoveredModel? = peek().firstOrNull { tag in it.tags }

    /** All cached/in-memory-mirrored models as [com.arya.ai.data.OnlineModel] entries, ready
     *  to slot into [OnlineChatHelper]'s existing provider/model fallback loop — so the live
     *  catalog doesn't need its own separate code path there, it just becomes a richer,
     *  auto-refreshing version of what [com.arya.ai.data.OnlineModels.OPENROUTER] used to be
     *  alone. Empty if [peek] hasn't been warmed yet this process. */
    fun peekAsOnlineModels(): List<com.arya.ai.data.OnlineModel> = peek().map {
        com.arya.ai.data.OnlineModel(
            id = it.id,
            displayName = "OpenRouter ${it.name}",
            note = it.description.ifBlank { it.tags.joinToString(", ") }
        )
    }

    /** Short Hinglish summary of the current cached catalog, grouped by tag — what the
     *  `list_free_models` tool shows Arya/the user, and also usable as debug text in Settings. */
    fun summaryText(context: Context): String {
        val models = cached(context)
        if (models.isEmpty()) {
            return "❌ Abhi tak koi free OpenRouter model discover nahi hui (relay configure hai ya nahi check karo, ya 'refresh_model_catalog' try karo)."
        }
        val ageMin = cacheAgeMs(context) / 60_000
        val byTag = models.flatMap { m -> m.tags.map { it to m } }.groupBy({ it.first }, { it.second })
        val sb = StringBuilder("🔎 OpenRouter free models (${models.size} total, ${ageMin} min pehle refresh hui):\n")
        val tagOrder = listOf("coding", "vision", "long_context", "reasoning", "lightweight", "general")
        for (tag in tagOrder) {
            val list = byTag[tag] ?: continue
            sb.append("\n**${tag}**: ")
            sb.append(list.distinctBy { it.id }.take(5).joinToString(", ") { it.name })
        }
        return sb.toString()
    }
}
