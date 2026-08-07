package com.arya.ai.tools

import android.content.Context
import org.json.JSONObject

/**
 * Ports the original assistant's persona/roleplay system (activate/deactivate/switch/list). The active
 * persona's description gets folded into the tool-calling system prompt built by
 * [com.arya.ai.inference.ToolCallParser.buildSystemPrompt] wherever a screen chooses to
 * call [activeSystemPromptPrefix].
 */
object PersonaStore {

    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_personas")

    private fun readSaved(context: Context): JSONObject = JSONObject(prefs(context).getString("saved", "{}") ?: "{}")
    private fun writeSaved(context: Context, obj: JSONObject) = prefs(context).edit().putString("saved", obj.toString()).apply()

    fun activatePersona(context: Context, name: String, description: String, speakingStyle: String): String {
        val saved = readSaved(context)
        val entry = JSONObject().apply {
            put("description", description)
            put("speaking_style", speakingStyle)
        }
        saved.put(name, entry)
        writeSaved(context, saved)
        prefs(context).edit().putString("active", name).apply()
        return "🎭 Persona activate kar di: $name"
    }

    fun deactivatePersona(context: Context): String {
        prefs(context).edit().remove("active").apply()
        return "🎭 Persona deactivate kar di — Arya wapas normal mode me"
    }

    fun getCurrentPersona(context: Context): String {
        val active = prefs(context).getString("active", null) ?: return "🎭 Abhi koi persona active nahi hai"
        val entry = readSaved(context).optJSONObject(active) ?: return "🎭 Active: $active"
        return "🎭 Active persona: $active — ${entry.optString("description")}"
    }

    fun listSavedPersonas(context: Context): String {
        val saved = readSaved(context)
        if (saved.length() == 0) return "🎭 Koi saved persona nahi hai"
        return "🎭 Saved personas:\n" + saved.keys().asSequence().joinToString("\n") { "• $it" }
    }

    fun switchToSavedPersona(context: Context, name: String): String {
        val saved = readSaved(context)
        if (!saved.has(name)) return "❌ Persona '$name' saved nahi hai"
        prefs(context).edit().putString("active", name).apply()
        return "🎭 Switch kar diya: $name"
    }

    /** Removes a saved persona entirely (and deactivates it first if it was the active one). */
    fun deletePersona(context: Context, name: String): String {
        val saved = readSaved(context)
        if (!saved.has(name)) return "❌ Persona '$name' saved nahi hai"
        saved.remove(name)
        writeSaved(context, saved)
        if (prefs(context).getString("active", null) == name) {
            prefs(context).edit().remove("active").apply()
        }
        return "🗑️ Persona delete kar di: $name"
    }

    // --- Structured accessors for the Compose Persona screen (the functions above return
    // pre-formatted Hinglish strings meant for chat tool output; these return raw data) ---

    data class PersonaInfo(val name: String, val description: String, val speakingStyle: String)

    fun listPersonasStructured(context: Context): List<PersonaInfo> {
        val saved = readSaved(context)
        return saved.keys().asSequence().map { name ->
            val entry = saved.optJSONObject(name) ?: JSONObject()
            PersonaInfo(name, entry.optString("description"), entry.optString("speaking_style"))
        }.toList()
    }

    fun activePersonaName(context: Context): String? = prefs(context).getString("active", null)

    /** Empty string if no persona is active. Meant to be prepended to a tool system prompt. */
    fun activeSystemPromptPrefix(context: Context): String {
        val active = prefs(context).getString("active", null) ?: return ""
        val entry = readSaved(context).optJSONObject(active) ?: return ""
        val style = entry.optString("speaking_style")
        return "Tum abhi '$active' persona me ho: ${entry.optString("description")}." +
            if (style.isNotBlank()) " Speaking style: $style." else ""
    }
}
