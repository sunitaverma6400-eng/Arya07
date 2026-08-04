package com.arya.ai.util

import android.content.Context

/**
 * Central place for every user-adjustable setting: generation params,
 * persona/system prompt, backend choice, and theme.
 */
class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("arya_prefs", Context.MODE_PRIVATE)

    var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    /** The current app user's own name — collected once on first launch (right after the
     *  runtime-permission dialogs) via [com.arya.ai.ui.NameEntryScreen]. Blank means onboarding
     *  hasn't completed yet, which [com.arya.ai.MainActivity] uses to decide the NavHost start
     *  destination. Used only to personalize the home greeting ("<name>, बोलो") — this is the
     *  person talking to Arya, unrelated to Arya's own identity (see [AryaIdentity]). */
    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    /** "owner/repo" GitHub repo to check for new Releases, e.g. "sunitaverma6400-eng/Jarvis".
     *  Blank (default) disables update checking entirely until set in Settings. */
    var updateCheckRepo: String
        get() = prefs.getString("update_check_repo", "") ?: ""
        set(value) = prefs.edit().putString("update_check_repo", value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    /** Cached result of the last successful check — null means "no update available"
     *  (or never checked). Set by [com.arya.ai.worker.UpdateCheckWorker]. */
    var availableUpdateVersion: String?
        get() = prefs.getString("available_update_version", null)
        set(value) = prefs.edit().putString("available_update_version", value).apply()

    var availableUpdateUrl: String?
        get() = prefs.getString("available_update_url", null)
        set(value) = prefs.edit().putString("available_update_url", value).apply()

    var availableUpdateNotes: String?
        get() = prefs.getString("available_update_notes", null)
        set(value) = prefs.edit().putString("available_update_notes", value).apply()

    /** true = dark theme, false = light theme */
    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) = prefs.edit().putBoolean("dark_theme", value).apply()

    /** Read typed chat replies aloud (see [com.arya.ai.MainActivity]'s `speakReply` wiring into
     *  [com.arya.ai.viewmodel.ChatViewModel]) — defaults to true so Arya's emotional voice is
     *  on everywhere (voice commands, live call, AND typed chat) without extra setup; toggle
     *  off in Settings if typed chat should stay silent. */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts_enabled", true)
        set(value) = prefs.edit().putBoolean("tts_enabled", value).apply()

    /** "Hey Arya" always-listening background wake word (see WakeWordService). */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", false)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    /** Epoch millis of the last successful background current-info sync (0 = never synced). */
    var lastCurrentInfoSync: Long
        get() = prefs.getLong("last_current_info_sync", 0L)
        set(value) = prefs.edit().putLong("last_current_info_sync", value).apply()

    /**
     * How many tools (out of ALL_TOOLS' 109) get selected per message by
     * [com.arya.ai.tools.AryaToolRegistry.relevantTools] and sent to the online model.
     * Lower = smaller prompt = faster replies on weak phones, but a higher chance a needed
     * tool doesn't make the cut. Higher = safer tool coverage, slower prefill. Clamped to
     * 3..20 so a bad stored value (or old data) can never regress back to "send everything".
     */
    var maxToolsPerRequest: Int
        get() = prefs.getInt("max_tools_per_request", 10).coerceIn(3, 20)
        set(value) = prefs.edit().putInt("max_tools_per_request", value.coerceIn(3, 20)).apply()

    // ---- Online (Groq/Gemini/OpenRouter) free-model selection ----

    /** Which free Groq model to use — defaults to the first entry in [com.arya.ai.data.OnlineModels.GROQ]. */
    var selectedGroqModel: String
        get() = prefs.getString("selected_groq_model", com.arya.ai.data.OnlineModels.GROQ.first().id)
            ?: com.arya.ai.data.OnlineModels.GROQ.first().id
        set(value) = prefs.edit().putString("selected_groq_model", value).apply()

    /** Which free Gemini model to use — defaults to the first entry in [com.arya.ai.data.OnlineModels.GEMINI]. */
    var selectedGeminiModel: String
        get() = prefs.getString("selected_gemini_model", com.arya.ai.data.OnlineModels.GEMINI.first().id)
            ?: com.arya.ai.data.OnlineModels.GEMINI.first().id
        set(value) = prefs.edit().putString("selected_gemini_model", value).apply()

    /** Which free OpenRouter model to use — defaults to the first entry in [com.arya.ai.data.OnlineModels.OPENROUTER]. */
    var selectedOpenRouterModel: String
        get() = prefs.getString("selected_openrouter_model", com.arya.ai.data.OnlineModels.OPENROUTER.first().id)
            ?: com.arya.ai.data.OnlineModels.OPENROUTER.first().id
        set(value) = prefs.edit().putString("selected_openrouter_model", value).apply()

    // ---- Community stats / Firebase sync (see FIXES_LOG.md Phase 10) ----

    /** Random anonymous ID, generated once per install — identifies this device in Firebase
     *  (presence/total-user counting, and chat sync if consented) without needing any
     *  name/email/phone number. Not tied to a Google account or any other real identity. */
    val installId: String
        get() {
            val existing = prefs.getString("install_id", null)
            if (existing != null) return existing
            val fresh = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("install_id", fresh).apply()
            return fresh
        }

    /** Whether the first-launch "chat/personal data share karke AI improve karo?" dialog has
     *  already been shown — so it only ever asks once, not on every app open. */
    var dataConsentAsked: Boolean
        get() = prefs.getBoolean("data_consent_asked", false)
        set(value) = prefs.edit().putBoolean("data_consent_asked", value).apply()

    /** true only if the user explicitly tapped "Haan" on that dialog. Gates chat-content sync
     *  to Firebase specifically — anonymous online/total-user counting is separate and always
     *  on (same as any app's basic usage analytics), see FirebaseSync's doc comment. */
    var dataConsentGiven: Boolean
        get() = prefs.getBoolean("data_consent_given", false)
        set(value) = prefs.edit().putBoolean("data_consent_given", value).apply()
}
