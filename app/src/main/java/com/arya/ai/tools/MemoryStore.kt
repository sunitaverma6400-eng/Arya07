package com.arya.ai.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The original project stored `remember`/`recall`/todos in a sqlite table (`memory.py`). On Arya
 * there's already Room for chat history, but this is a much smaller free-form
 * key-value/list store, so it's kept as simple JSON blobs in SharedPreferences —
 * no schema/migration overhead for a handful of small values.
 */
object MemoryStore {

    // Encrypted at rest — this store can hold personal facts the user asked Arya
    // to "remember" (birthdays, addresses, etc.), so it's treated as sensitive.
    private fun prefs(context: Context) = com.arya.ai.util.SecurePrefs.get(context, "arya_tool_memory")

    // ---- remember / recall ----

    private fun records(context: Context): JSONObject {
        val p = prefs(context)
        val existing = JSONObject(p.getString("records", "{}") ?: "{}")
        if (existing.length() == 0) {
            // One-time migration from the old key/value-only format.
            val legacy = JSONObject(p.getString("kv", "{}") ?: "{}")
            val now = System.currentTimeMillis()
            legacy.keys().forEach { key ->
                existing.put(key, JSONObject().put("value", legacy.getString(key)).put("createdAt", now).put("updatedAt", now).put("importance", 0.5)
                )
            }
            if (legacy.length() > 0) p.edit().putString("records", existing.toString()).apply()
        }
        return existing
    }

    fun remember(context: Context, key: String, value: String): String {
        val cleanKey = key.trim().take(120)
        val cleanValue = value.trim().take(4000)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return "❌ Memory key/value khaali nahi ho sakta"
        val json = records(context)
        val now = System.currentTimeMillis()
        val old = json.optJSONObject(cleanKey)
        json.put(cleanKey, JSONObject()
            .put("value", cleanValue)
            .put("createdAt", old?.optLong("createdAt", now) ?: now)
            .put("updatedAt", now)
            .put("importance", old?.optDouble("importance", 0.5) ?: 0.5))
        prefs(context).edit().putString("records", json.toString()).putString("kv", legacyKv(json).toString()).apply()
        return "🧠 Yaad rakh liya: \"$cleanKey\" = \"$cleanValue\""
    }

    private fun legacyKv(records: JSONObject): JSONObject {
        val out = JSONObject()
        records.keys().forEach { key -> out.put(key, records.optJSONObject(key)?.optString("value", "") ?: "") }
        return out
    }

    fun recall(context: Context, key: String): String {
        val json = records(context)
        val item = json.optJSONObject(key)
        return if (item != null) "🧠 $key: ${item.optString("value")}" else "❌ '$key' yaad nahi hai"
    }

    fun search(context: Context, query: String, limit: Int = 5): String {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return "❌ Search query khaali hai"
        val terms: Set<String> = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        val allRecords = records(context)
        val scored = mutableListOf<Triple<String, String, Int>>()
        val keyIterator = allRecords.keys()
        while (keyIterator.hasNext()) {
            val key: String = keyIterator.next()
            val item = allRecords.optJSONObject(key) ?: continue
            val value = item.optString("value")
            val hay = "$key $value".lowercase(Locale.ROOT)
            var score = 0
            for (term in terms) {
                if (hay.contains(term)) score += 1
            }
            if (hay.contains(q)) score += 3
            if (score > 0) scored.add(Triple(key, value, score))
        }
        val ranked = scored.sortedByDescending { triple -> triple.third }.take(limit.coerceIn(1, 10))
        if (ranked.isEmpty()) return "❌ Koi matching memory nahi mili"
        return "🧠 Matching memories:\n" + ranked.joinToString("\n") { "• ${it.first}: ${it.second}" }
    }

    fun listMemories(context: Context): String {
        val json = records(context)
        if (json.length() == 0) return "🧠 Koi memory saved nahi hai"
        return "🧠 Saved memories:\n" + json.keys().asSequence().map { key -> "• $key: ${json.optJSONObject(key)?.optString("value", "")}" }.joinToString("\n")
    }

    fun forget(context: Context, key: String): String {
        val json = records(context)
        if (!json.has(key)) return "❌ '$key' yaad me thi hi nahi"
        json.remove(key)
        prefs(context).edit().putString("records", json.toString()).putString("kv", legacyKv(json).toString()).apply()
        return "🗑️ '$key' bhula diya"
    }

    // ---- todos ----

    private fun readTodos(context: Context): JSONArray = JSONArray(prefs(context).getString("todos", "[]") ?: "[]")
    private fun writeTodos(context: Context, arr: JSONArray) = prefs(context).edit().putString("todos", arr.toString()).apply()

    fun addTodo(context: Context, task: String, priority: String): String {
        val arr = readTodos(context)
        val nextId = (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).optInt("id") } ?: 0
        val item = JSONObject().apply {
            put("id", nextId + 1)
            put("task", task)
            put("priority", priority.ifBlank { "medium" })
            put("done", false)
        }
        arr.put(item)
        writeTodos(context, arr)
        return "✅ Todo add ki (#${nextId + 1}): \"$task\" [$priority]"
    }

    fun listTodos(context: Context): String {
        val arr = readTodos(context)
        if (arr.length() == 0) return "📋 Koi todo nahi hai"
        val lines = (0 until arr.length()).map {
            val t = arr.getJSONObject(it)
            val mark = if (t.optBoolean("done")) "✔" else "◻"
            "$mark #${t.optInt("id")} [${t.optString("priority")}] ${t.optString("task")}"
        }
        return "📋 Todos:\n" + lines.joinToString("\n")
    }

    fun completeTodo(context: Context, taskId: Int): String {
        val arr = readTodos(context)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optInt("id") == taskId) {
                t.put("done", true)
                writeTodos(context, arr)
                return "✅ Todo #$taskId complete mark kar di"
            }
        }
        return "❌ Todo #$taskId nahi mili"
    }

    fun deleteTodo(context: Context, taskId: Int): String {
        val arr = readTodos(context)
        val kept = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optInt("id") == taskId) found = true else kept.put(t)
        }
        writeTodos(context, kept)
        return if (found) "🗑️ Todo #$taskId delete kar di" else "❌ Todo #$taskId nahi mili"
    }
}
