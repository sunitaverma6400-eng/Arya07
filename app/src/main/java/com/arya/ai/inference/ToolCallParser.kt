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
            Koi extra text mat likho jab tool call kar rahe ho. Agar koi tool zaroorat nahi, to normal Hinglish/English me jawab do.
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
        return try {
            val json = JSONObject(rawText.substring(start, end + 1))
            val name = json.getString("tool")
            val argsObj = json.optJSONObject("args") ?: JSONObject()
            val args = argsObj.keys().asSequence().associateWith { argsObj.getString(it) }
            ToolCall(name, args)
        } catch (e: Exception) {
            null
        }
    }
}
