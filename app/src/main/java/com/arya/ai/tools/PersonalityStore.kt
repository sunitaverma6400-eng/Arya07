package com.arya.ai.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Mood/closeness/proactive-initiative system, ported from the original assistant's
 * `personality.py`. Sits alongside [PersonaStore] (which handles named roleplay
 * personas/characters) — this file is Arya's *own* baseline personality state, not a
 * character she's playing.
 *
 *  - "closeness" grows slowly with interaction count (mirrors `increment_interaction` +
 *    `_closeness_label`).
 *  - "mood" is a lightweight heuristic from recent interaction recency/frequency
 *    (mirrors `_compute_mood`) — not a claim about any real emotional state, just a tone knob
 *    for `get_personality_prompt`.
 *  - "moments" are open-ended reminders Arya set for herself to bring something up later
 *    (mirrors `remember_moment`/`resolve_moment`/`get_pending_moments`).
 */
object PersonalityStore {

    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_personality")

    private fun clamp(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))

    // ---- interaction / closeness ----

    fun incrementInteraction(context: Context): String {
        val p = prefs(context)
        val count = p.getInt("interaction_count", 0) + 1
        p.edit()
            .putInt("interaction_count", count)
            .putLong("last_interaction_millis", System.currentTimeMillis())
            .apply()
        return "🤝 Interaction #$count logged — closeness: ${closenessLabel(count)}"
    }

    private fun closenessLabel(count: Int): String = when {
        count < 5 -> "naye dost"
        count < 25 -> "jaan-pehchaan wale"
        count < 100 -> "achhe dost"
        else -> "purane, bharosemand dost"
    }

    fun getCurrentMood(context: Context): String {
        val p = prefs(context)
        val lastMillis = p.getLong("last_interaction_millis", 0L)
        val hoursSince = if (lastMillis == 0L) Double.MAX_VALUE else (System.currentTimeMillis() - lastMillis) / 3_600_000.0
        val count = p.getInt("interaction_count", 0)
        val mood = when {
            hoursSince > 72 -> "🥺 thodi der ho gayi baat kiye"
            hoursSince < 0.5 && count > 10 -> "😄 khushi se energetic"
            count > 100 -> "😌 comfortable aur relaxed"
            else -> "🙂 normal, ready to help"
        }
        return "Mood: $mood (closeness: ${closenessLabel(count)}, $count interactions)"
    }

    fun getPersonalityStatusText(context: Context): String {
        val p = prefs(context)
        val count = p.getInt("interaction_count", 0)
        val feedback = p.getInt("positive_feedback", 0) - p.getInt("negative_feedback", 0)
        val surprise = if (p.getBoolean("surprise_mode", false)) "ON" else "OFF"
        return "📊 Personality status:\n• Interactions: $count\n• Closeness: ${closenessLabel(count)}\n• Net feedback score: $feedback\n• Surprise mode: $surprise"
    }

    fun resetPersonality(context: Context): String {
        prefs(context).edit().clear().apply()
        return "🔄 Personality state reset kar di"
    }

    fun recordFeedback(context: Context, positive: Boolean): String {
        val p = prefs(context)
        val key = if (positive) "positive_feedback" else "negative_feedback"
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        return if (positive) "👍 Feedback noted, shukriya!" else "👎 Feedback noted, sudhaarne ki koshish karungi"
    }

    // ---- moments (self-set reminders to bring something up later) ----

    private fun readMoments(context: Context): JSONArray = JSONArray(prefs(context).getString("moments", "[]") ?: "[]")
    private fun writeMoments(context: Context, arr: JSONArray) = prefs(context).edit().putString("moments", arr.toString()).apply()

    fun rememberMoment(context: Context, note: String): String {
        val arr = readMoments(context)
        arr.put(JSONObject().apply {
            put("note", note)
            put("created", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            put("resolved", false)
        })
        writeMoments(context, arr)
        return "🔖 Yaad rakh liya baad me poochne ke liye: \"$note\""
    }

    fun getPendingMoments(context: Context): String {
        val arr = readMoments(context)
        val pending = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("resolved") }
        if (pending.isEmpty()) return "🔖 Koi pending moment nahi hai"
        return "🔖 Pending moments:\n" + pending.joinToString("\n") { "• ${it.optString("note")} (${it.optString("created")})" }
    }

    fun resolveMoment(context: Context, note: String): String {
        val arr = readMoments(context)
        var found = false
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            if (m.optString("note") == note && !m.optBoolean("resolved")) {
                m.put("resolved", true)
                found = true
                break
            }
        }
        writeMoments(context, arr)
        return if (found) "✅ Moment resolve kar diya: \"$note\"" else "❌ Ye moment nahi mila"
    }

    // ---- surprise mode (occasional proactive check-in notifications) ----

    fun setSurpriseMode(context: Context, on: Boolean): String {
        prefs(context).edit().putBoolean("surprise_mode", on).apply()
        return if (on) "✨ Surprise mode ON — kabhi kabhi khud se check-in karungi" else "✨ Surprise mode OFF"
    }

    /** Called by a periodic worker; logs + fires a notification only if surprise mode is on. */
    fun runSurpriseCheckIn(context: Context) {
        if (!prefs(context).getBoolean("surprise_mode", false)) return
        val messages = listOf(
            "Hey! Bas yaad aayi tumhari 😊", "Kaisa chal raha hai sab?", "Koi naya kaam ho to batana!",
            "Thoda break le lo, paani pi lo 💧"
        )
        val msg = messages.random()
        logInitiative(context, msg)
        DeviceExtraTools.sendNotification(context, "Arya", msg)
    }

    private fun logInitiative(context: Context, message: String) {
        val arr = JSONArray(prefs(context).getString("initiatives", "[]") ?: "[]")
        arr.put(JSONObject().apply {
            put("message", message)
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        })
        prefs(context).edit().putString("initiatives", arr.toString()).apply()
    }

    /** Public entry point for [com.arya.ai.worker.ReflectionWorker] — logs a capability-gap
     *  question the same way [runSurpriseCheckIn]'s check-ins log themselves, so both show up
     *  together in [getRecentInitiatives]. */
    fun logReflection(context: Context, message: String) = logInitiative(context, message)

    fun getRecentInitiatives(context: Context, limit: Int = 5): String {
        val arr = JSONArray(prefs(context).getString("initiatives", "[]") ?: "[]")
        if (arr.length() == 0) return "✨ Koi proactive message bheja nahi hai abhi tak"
        val recent = (max(0, arr.length() - limit) until arr.length()).map { arr.getJSONObject(it) }
        return "✨ Recent initiatives:\n" + recent.joinToString("\n") { "• ${it.optString("time")}: ${it.optString("message")}" }
    }

    /** Folded into the tool system prompt alongside [PersonaStore.activeSystemPromptPrefix]. */
    fun getPersonalityPrompt(context: Context): String {
        val count = prefs(context).getInt("interaction_count", 0)
        return "Tumhara/Aapka relationship is user ke saath: ${closenessLabel(count)} ($count interactions ab tak)."
    }
}
