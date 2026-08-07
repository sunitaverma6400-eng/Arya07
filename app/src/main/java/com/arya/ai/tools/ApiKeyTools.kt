package com.arya.ai.tools

import android.content.Context
import com.arya.ai.util.ApiKeyManager
import com.arya.ai.util.ApiProvider

/**
 * Tool-callable wrappers around [ApiKeyManager], ported from `tools.py`'s `list_api_keys` /
 * `delete_api_key`. The API Keys screen already lets the user manage keys by hand — these
 * expose the same actions to the model itself (e.g. "kaunse API keys configured hain?",
 * "meri purani Groq key hata do"). Key *values* are never echoed back in full — only
 * providers + a masked suffix — same "don't leak secrets into chat transcripts" instinct as
 * the rest of Arya's tool replies.
 */
object ApiKeyTools {

    private fun mask(key: String) = if (key.length <= 4) "••••" else "••••${key.takeLast(4)}"

    fun listApiKeys(context: Context): String {
        val manager = ApiKeyManager(context)
        val lines = ApiProvider.values().mapNotNull { provider ->
            val keys = manager.getKeys(provider)
            if (keys.isEmpty()) null else "• ${provider.label}: ${keys.joinToString(", ") { mask(it) }}"
        }
        return if (lines.isEmpty()) "🔑 Koi API key configure nahi hai" else "🔑 Configured API keys:\n" + lines.joinToString("\n")
    }

    fun deleteApiKey(context: Context, providerName: String, keySuffix: String): String {
        val provider = ApiProvider.values().firstOrNull {
            it.name.equals(providerName, ignoreCase = true) || it.label.equals(providerName, ignoreCase = true)
        } ?: return "❌ Provider '$providerName' pehchana nahi gaya"
        val manager = ApiKeyManager(context)
        val match = manager.getKeys(provider).firstOrNull { it.takeLast(4) == keySuffix || it == keySuffix }
            ?: return "❌ '${provider.label}' me koi key '$keySuffix' se match nahi hui"
        manager.removeKey(provider, match)
        return "🗑️ ${provider.label} key (••••${match.takeLast(4)}) delete kar di"
    }

    /**
     * Phase 6 (see chat history) — a one-shot health check that ties together everything the
     * "advanced tools" upgrade built: relay reachability (`/healthz`), which API keys are
     * configured, and whatever [CuriosityStore] has logged as a repeat failure. One command
     * ("system check karo") instead of separately asking about the relay, keys, and gaps.
     */
    fun systemCheck(context: Context): String {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        val relayStatus = if (relayUrl.isBlank()) {
            "❌ configured nahi hai"
        } else {
            val healthUrl = relayUrl.removeSuffix("/v1/relay").removeSuffix("/") + "/healthz"
            val ok = com.arya.ai.tools.NetTools.getText(
                healthUrl, headers = mapOf("X-App-Secret" to com.arya.ai.BuildConfig.RELAY_APP_SECRET)
            ).isNotBlank()
            if (ok) "✅ reachable" else "⚠️ configured hai but reachable nahi (Render free tier cold-start ho sakta hai)"
        }
        val keys = listApiKeys(context)
        val gaps = CuriosityStore.listGaps(context)
        return "🩺 System check:\n• Relay: $relayStatus\n\n$keys\n\n$gaps"
    }
}
