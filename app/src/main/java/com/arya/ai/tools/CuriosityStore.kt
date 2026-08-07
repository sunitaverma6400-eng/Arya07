package com.arya.ai.tools

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Phase 5 of the "advanced tools" upgrade (see chat history) — tracks capability gaps Arya
 * hits during normal tool use: a relay that isn't configured, a permission that wasn't
 * granted, a missing API key, and so on (see [AryaToolRegistry.logCapabilityGap] for what
 * gets logged here and why). [ReflectionWorker] reads this during idle time and, if
 * something keeps failing the same way, turns it into one concrete, specific question for
 * Sudhanshu instead of Arya silently hitting the same wall on every message.
 *
 * Worth being honest about scope: this is bookkeeping over the tool registry's own
 * "❌ ... nahi hua" replies, surfaced later as a notification — not a claim that Arya has any
 * actual desire, motivation, or awareness beyond exactly what's implemented here.
 */
object CuriosityStore {

    private fun prefs(context: Context) = context.getSharedPreferences("arya_curiosity", Context.MODE_PRIVATE)

    private fun readGaps(context: Context): JSONObject = JSONObject(prefs(context).getString("gaps", "{}") ?: "{}")
    private fun writeGaps(context: Context, obj: JSONObject) = prefs(context).edit().putString("gaps", obj.toString()).apply()

    /** Logs that [toolName] hit a gap for [reason] (e.g. "relay_not_configured"). Repeat hits on
     *  the same tool+reason bump a counter instead of duplicating entries, so a tool that fails
     *  on every message doesn't flood the store with near-identical rows. */
    fun logGap(context: Context, toolName: String, reason: String) {
        val gaps = readGaps(context)
        val key = "$toolName:$reason"
        val existing = gaps.optJSONObject(key)
        gaps.put(key, JSONObject().apply {
            put("tool", toolName)
            put("reason", reason)
            put("count", (existing?.optInt("count", 0) ?: 0) + 1)
            put("last_seen", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            put("asked", existing?.optBoolean("asked", false) ?: false)
        })
        writeGaps(context, gaps)
    }

    /** The most-hit gap Arya hasn't already asked about, or null if there isn't one worth
     *  bringing up yet — either nothing's been logged, or nothing has hit the "≥2 times" bar
     *  (a single one-off failure isn't a pattern), or everything past that bar has already
     *  been asked about once (no repeat-nagging about the same missing key). */
    fun nextUnaskedGap(context: Context): JSONObject? {
        val gaps = readGaps(context)
        return gaps.keys().asSequence()
            .map { gaps.getJSONObject(it) }
            .filter { !it.optBoolean("asked") && it.optInt("count") >= 2 }
            .maxByOrNull { it.optInt("count") }
    }

    fun markAsked(context: Context, toolName: String, reason: String) {
        val gaps = readGaps(context)
        val key = "$toolName:$reason"
        gaps.optJSONObject(key)?.put("asked", true)
        writeGaps(context, gaps)
    }

    fun listGaps(context: Context): String {
        val gaps = readGaps(context)
        if (gaps.length() == 0) return "🧐 Koi capability gap track nahi hui abhi tak"
        return "🧐 Tracked gaps:\n" + gaps.keys().asSequence().joinToString("\n") { key ->
            val g = gaps.getJSONObject(key)
            "• ${g.optString("tool")} (${g.optString("reason")}) — ${g.optInt("count")}x seen, asked: ${g.optBoolean("asked")}"
        }
    }
}
