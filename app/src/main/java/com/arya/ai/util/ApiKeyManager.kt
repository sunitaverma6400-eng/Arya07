package com.arya.ai.util

import android.content.Context

enum class ApiProvider(val label: String, val maxKeys: Int) {
    GROQ("Groq", 7),
    GEMINI("Gemini", 5),
    OPENROUTER("OpenRouter", 8),

    // Optional — used by AryaToolRegistry's tools. Both work without a key
    // (NASA falls back to the public DEMO_KEY; Wolfram Alpha just declines that one tool).
    NASA("NASA (optional)", 1),
    WOLFRAM("Wolfram Alpha (optional)", 1),

    // Optional — Picovoice Porcupine AccessKey for the "Hey Arya" wake word (Option A,
    // battery-efficient custom keyword spotter). Get a free one at console.picovoice.ai.
    // Not required: WakeWordService falls back to the built-in VAD + SpeechRecognizer
    // approach (Option B) when no key is saved here.
    PICOVOICE("Picovoice AccessKey (optional)", 1)
}

/**
 * Stores API keys the user enters through the app's own UI (never hardcoded
 * in source). Keys are kept in a private SharedPreferences file scoped to
 * this app only. Supports several keys per provider so requests can rotate
 * across them — useful for spreading load across free-tier rate limits.
 */
class ApiKeyManager(context: Context) {

    // Encrypted at rest (AES256-GCM values, Keystore-backed master key) — API keys are
    // secrets, so this file should never have been plaintext to begin with.
    private val prefs = SecurePrefs.get(context, "arya_api_keys")

    private fun keyPref(provider: ApiProvider) = "keys_${provider.name}"
    private fun indexPref(provider: ApiProvider) = "rr_index_${provider.name}"

    fun getKeys(provider: ApiProvider): List<String> =
        prefs.getString(keyPref(provider), "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun addKey(provider: ApiProvider, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val current = getKeys(provider)
        if (current.size >= provider.maxKeys || current.contains(trimmed)) return false
        val updated = current + trimmed
        prefs.edit().putString(keyPref(provider), updated.joinToString("\n")).apply()
        return true
    }

    fun removeKey(provider: ApiProvider, key: String) {
        val updated = getKeys(provider).filterNot { it == key }
        prefs.edit().putString(keyPref(provider), updated.joinToString("\n")).apply()
    }

    fun hasAnyKey(provider: ApiProvider): Boolean = getKeys(provider).isNotEmpty()

    fun hasAnyProviderConfigured(): Boolean = ApiProvider.values().any { hasAnyKey(it) }

    /** Convenience for single-key providers like Picovoice — returns the saved key, if any. */
    fun singleKey(provider: ApiProvider): String? = getKeys(provider).firstOrNull()

    /** Round-robins through the provider's stored keys, one call at a time. */
    fun nextKey(provider: ApiProvider): String? {
        val keys = getKeys(provider)
        if (keys.isEmpty()) return null
        val idx = prefs.getInt(indexPref(provider), 0) % keys.size
        prefs.edit().putInt(indexPref(provider), (idx + 1) % keys.size).apply()
        return keys[idx]
    }
}
