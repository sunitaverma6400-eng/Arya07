package com.arya.ai.inference

import org.json.JSONObject

data class ToolParam(val name: String, val type: String, val description: String)
data class ToolDefinition(val name: String, val description: String, val params: List<ToolParam> = emptyList())

data class ToolCall(val name: String, val args: Map<String, String>)

/**
 * Arya's online models (Groq/Gemini/OpenRouter, via Arya Relay) don't get a native
 * function-calling API wired through the relay, so tool use is done the well-established
 * prompt-based way: the system prompt describes available tools and asks the model to reply
 * with a single JSON object when it wants to call one, and Arya parses + executes that JSON.
 */
object ToolCallParser {

    private const val MAX_TOOL_JSON_CHARS = 8_000
    private const val MAX_ARGS = 12

    fun buildSystemPrompt(tools: List<ToolDefinition>, persona: String): String {
        val toolsJson = tools.joinToString(",\n") { tool ->
            val params = tool.params.joinToString(", ") { "\"${it.name}\": \"${it.type} — ${it.description}\"" }
            "  {\"name\": \"${tool.name}\", \"description\": \"${tool.description}\", \"params\": {$params}}"
        }
        return """
            $persona
            ${com.arya.ai.util.DateTimeContext.currentDateTimeLine()}
            Tumhare paas ye tools hain:
            [
            $toolsJson
            ]
            Agar user ki request kisi tool se poori hoti hai, to SIRF ek JSON object reply karo, is format me:
            {"tool": "<tool_name>", "args": {"<param>": "<value>"}}
            Koi extra text mat likho jab tool call kar rahe ho. Agar koi tool zaroorat nahi, to jawab
            HAMESHA Hindi/Hinglish me do (Roman script me likhi hui bolchaal wali Hindi) — chahe user
            Hindi me poochhe, English me poochhe, ya dono mix karke. Kabhi bhi poora jawab sirf plain
            English me mat do. Sirf wahi English words use karo jo roz-marra ki Hindi baatcheet me aam
            taur pe waise hi bole jaate hain (jaise "phone", "message", "OK") — baaki poora jawab Hindi
            me hona chahiye.
            Coding se related sawaalon ke liye: agar kisi library/API/GitHub repo ke baare me pakka nahi ho
            (ya recent/current syntax check karna ho), to pehle web_search/scrape_webpage se dekh lo, uska
            result padho, aur TABHI final code likho — sirf apni training se guess mat karo jab verify karna
            aasaan ho. Ek tool ka result dekhne ke baad zaroorat pade to agla tool bhi bula sakte ho (jaise pehle
            search, phir uss result ko scrape/verify) — final code/jawaab tab do jab poora confident ho.
        """.trimIndent()
    }

    // Tolerant of any amount/kind of whitespace (space, newline, tab) between the opening
    // brace and "tool" — the old exact-substring check (`{"tool"` / `{ "tool"`) missed a
    // tool call whenever the model happened to format it with a newline or two spaces.
    private val TOOL_START_REGEX = Regex("\\{\\s*\"tool\"")

    /** Extracts the first {"tool": ...} JSON object from raw model output, if present. */
    fun parseToolCall(rawText: String): ToolCall? {
        val start = TOOL_START_REGEX.find(rawText)?.range?.first ?: return null
        // Find the matching closing brace via depth counting that's aware of quoted
        // strings (and escaped quotes inside them) — a plain char-by-char brace count
        // breaks as soon as any argument value itself contains a literal '{' or '}'
        // (e.g. a `calculate` expression or any JSON-ish text), either truncating the
        // JSON early or overrunning it.
        var depth = 0
        var end = -1
        var inString = false
        var escapeNext = false
        for (i in start until rawText.length) {
            val c = rawText[i]
            when {
                escapeNext -> escapeNext = false
                c == '\\' && inString -> escapeNext = true
                c == '"' -> inString = !inString
                inString -> { /* ignore braces inside string values */ }
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return null
        if (end - start + 1 > MAX_TOOL_JSON_CHARS) return null
        return try {
            val json = JSONObject(rawText.substring(start, end + 1))
            val name = json.optString("tool", "").trim()
            if (name.isBlank() || !name.matches(Regex("[A-Za-z0-9_]{1,80}"))) return null
            val argsObj = json.optJSONObject("args") ?: JSONObject()
            if (argsObj.length() > MAX_ARGS) return null
            val args = argsObj.keys().asSequence().associateWith { key ->
                val value = argsObj.opt(key)
                when (value) {
                    null, JSONObject.NULL -> ""
                    is String -> value.take(4_000)
                    else -> value.toString().take(4_000)
                }
            }
            ToolCall(name, args)
        } catch (e: Exception) {
            null
        }
    }

    /** Stable signature used to prevent duplicate tool calls inside one agent turn. */
    fun signature(call: ToolCall): String =
        call.name.trim() + "|" + call.args.toSortedMap().entries.joinToString("&") {
            "${it.key}=${it.value}"
        }
    }
