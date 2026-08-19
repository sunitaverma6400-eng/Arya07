package com.arya.ai.inference


/** Central validation/safety layer for native tool execution. */
object ToolGuard {
    private const val MAX_TOOL_NAME = 80
    private const val MAX_ARG_KEYS = 32
    private const val MAX_ARG_CHARS = 12_000
    private const val MAX_RESULT_CHARS = 20_000

    fun validate(call: ToolCall, definitions: List<ToolDefinition>): String? {
        if (call.name.isBlank() || call.name.length > MAX_TOOL_NAME || !call.name.matches(Regex("[A-Za-z0-9_]+"))) {
            return "❌ Invalid tool name"
        }
        val definition = definitions.firstOrNull { it.name == call.name }
            ?: return "❌ Unknown tool: ${call.name}"
        if (call.args.size > MAX_ARG_KEYS) return "❌ Too many tool arguments"
        val knownParams = definition.params.map { it.name }.toSet()
        if (call.args.keys.any { it !in knownParams }) return "❌ Unknown argument for '${call.name}'"
        if (call.args.keys.any { it.isBlank() || it.length > 80 || !it.matches(Regex("[A-Za-z0-9_]+")) }) {
            return "❌ Invalid tool argument name"
        }
        if (call.args.values.sumOf { it.length } > MAX_ARG_CHARS) return "❌ Tool arguments too large"
        for (param in definition.params) {
            val value = call.args[param.name] ?: continue
            when (param.type.substringBefore(' ').lowercase()) {
                "number", "integer" -> if (value.toDoubleOrNull() == null) return "❌ '${param.name}' number hona chahiye"
                "boolean" -> if (value.lowercase() !in setOf("true", "false", "1", "0", "yes", "no")) return "❌ '${param.name}' boolean hona chahiye"
            }
        }
        return null
    }

    fun capResult(result: String): String = if (result.length <= MAX_RESULT_CHARS) result
    else result.take(MAX_RESULT_CHARS) + "\n…[tool result truncated]"

    fun isLiveToolAllowed(name: String): Boolean = name in SAFE_LIVE_TOOLS

    /** Live mode is intentionally read/query oriented. Actions with side effects stay out. */
    val SAFE_LIVE_TOOLS: Set<String> = setOf(
        "get_current_time", "calculate", "convert_units", "text_analyzer", "get_random_quote",
        "system_info", "get_weather", "get_crypto_price", "convert_currency", "get_country_info",
        "get_dictionary", "translate_text", "get_wikipedia_summary", "get_sunrise_sunset",
        "get_public_holidays", "get_spacex_launches", "get_news", "web_search", "scrape_webpage",
        "smart_search", "get_current_mood", "get_personality_status", "recall", "list_memories",
        "search_radio", "search_youtube", "search_videos", "stream_status", "list_saved_streams",
        "search_images", "get_battery_status", "get_location", "list_todos", "list_reminders",
        "list_saved_sites", "get_default_stream_quality", "list_stream_qualities"
    )
}
