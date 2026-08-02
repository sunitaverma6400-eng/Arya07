package com.arya.ai.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    fun remember(context: Context, key: String, value: String): String {
        val json = JSONObject(prefs(context).getString("kv", "{}") ?: "{}")
        json.put(key, value)
        prefs(context).edit().putString("kv", json.toString()).apply()
        return "🧠 Yaad rakh liya: \"$key\" = \"$value\""
    }

    fun recall(context: Context, key: String): String {
        val json = JSONObject(prefs(context).getString("kv", "{}") ?: "{}")
        return if (json.has(key)) "🧠 $key: ${json.getString(key)}" else "❌ '$key' yaad nahi hai"
    }

    fun listMemories(context: Context): String {
        val json = JSONObject(prefs(context).getString("kv", "{}") ?: "{}")
        if (json.length() == 0) return "🧠 Koi memory saved nahi hai"
        return "🧠 Saved memories:\n" + json.keys().asSequence().joinToString("\n") { "• $it: ${json.getString(it)}" }
    }

    fun forget(context: Context, key: String): String {
        val json = JSONObject(prefs(context).getString("kv", "{}") ?: "{}")
        if (!json.has(key)) return "❌ '$key' yaad me thi hi nahi"
        json.remove(key)
        prefs(context).edit().putString("kv", json.toString()).apply()
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
