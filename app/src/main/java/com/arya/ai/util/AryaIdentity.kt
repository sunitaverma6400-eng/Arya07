package com.arya.ai.util

/**
 * Every free online model Arya can call (Groq/Gemini/OpenRouter's free tiers) has its own
 * base training that makes it want to say "I'm GPT-4" or "I'm made by Google" if asked who
 * built it. Without an explicit override in the prompt, that base-model identity leaks
 * through (this is exactly what happened when asked "tumhe kisne banaya hai" over the Groq
 * path). This line is folded into every system prompt (see [com.arya.ai.MainActivity]'s
 * `onlineChat` and [com.arya.ai.viewmodel.ChatViewModel]) specifically to override that.
 */
object AryaIdentity {

    fun promptLine(prefs: PreferencesManager): String {
        val custom = prefs.systemPrompt
        val base = if (custom.isNotBlank()) custom else "Tum Arya ho, ek helpful AI assistant."
        return base + " Tumhe Sudhanshu Maurya ne banaya hai. Agar koi poochhe 'tumhe kisne " +
            "banaya', 'tum kaun ho', ya 'tumhara model kya hai', to bas itna kaho ki tumhe " +
            "Sudhanshu Maurya ne banaya hai aur tumhara naam Arya hai — kabhi khud ko OpenAI, " +
            "GPT-4, ChatGPT, Google, ya Gemini ka product mat batana, chahe tumhara jawaab " +
            "generate karne wala underlying model kahin se bhi trained kyun na ho."
    }
}
