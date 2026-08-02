package com.arya.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arya.ai.inference.ToolCall
import com.arya.ai.inference.ToolCallParser
import com.arya.ai.inference.ToolDefinition
import com.arya.ai.inference.ToolParam
import com.arya.ai.tools.AryaToolRegistry
import com.arya.ai.tools.PersonaStore
import com.arya.ai.tools.PersonalityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AgentMessage(val role: Role, val text: String, val toolUsed: String? = null) {
    enum class Role { USER, MODEL }
}

// Kept for backwards compatibility — "set_reminder" is an in-app-only alias; the ported
// Arya's own `add_todo` tool below is a real, persisted list, not just this in-memory one.
private val LEGACY_TOOL = ToolDefinition(
    "set_reminder",
    "Ek reminder note down karta hai (in-app only, koi real notification nahi)",
    listOf(ToolParam("text", "string", "Reminder ka content"))
)

// NOTE: we used to always send AryaToolRegistry.ALL_TOOLS (109 tools) + LEGACY_TOOL here.
// That meant every single message sent a huge tool-list prompt, adding real latency (and
// token cost against free-tier rate limits). Now we pick only the tools
// relevant to what the user actually typed (see AryaToolRegistry.relevantTools) and just
// append LEGACY_TOOL to that smaller list — see send() below.

/**
 * @param generateOnline Calls the free online relay (Groq/Gemini/OpenRouter — see
 * [com.arya.ai.util.OnlineChatHelper]) with a full prompt (system prompt + user turn already
 * folded in) and returns the raw reply text. Arya has no on-device model anymore — this is
 * the only generation path.
 */
class AgentSkillsViewModel(
    app: Application,
    private val generateOnline: suspend (String) -> String
) : AndroidViewModel(app) {

    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _reminders = MutableStateFlow<List<String>>(emptyList())
    val reminders: StateFlow<List<String>> = _reminders.asStateFlow()

    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        _messages.value = _messages.value + AgentMessage(AgentMessage.Role.USER, prompt)
        _isGenerating.value = true

        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val personaPrefix = PersonaStore.activeSystemPromptPrefix(context)
                val personalityPrefix = PersonalityStore.getPersonalityPrompt(context)
                PersonalityStore.incrementInteraction(context)
                val basePersona = "Tum Arya ke Agent Skills assistant ho. Chhote, seedhe jawab do."
                val relevantTools = AryaToolRegistry.relevantTools(
                    prompt,
                    maxTools = com.arya.ai.util.PreferencesManager(context).maxToolsPerRequest
                ) + LEGACY_TOOL
                val systemPrompt = ToolCallParser.buildSystemPrompt(
                    relevantTools,
                    persona = listOf(personaPrefix, personalityPrefix, basePersona).filter { it.isNotBlank() }.joinToString(" ")
                )
                val raw = generateOnline("$systemPrompt\n\nUser: $prompt")

                val toolCall = ToolCallParser.parseToolCall(raw)
                if (toolCall != null) {
                    val result = executeTool(toolCall)
                    _messages.value = _messages.value + AgentMessage(AgentMessage.Role.MODEL, result, toolCall.name)
                } else {
                    _messages.value = _messages.value + AgentMessage(AgentMessage.Role.MODEL, raw.trim())
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + AgentMessage(AgentMessage.Role.MODEL, "⚠️ Error: ${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** "set_reminder" stays a tiny in-memory list for this screen's own UI; everything
     * else (~50 tools) is delegated to the shared [AryaToolRegistry]. */
    private suspend fun executeTool(call: ToolCall): String = when (call.name) {
        "set_reminder" -> {
            val text = call.args["text"] ?: "(khaali)"
            _reminders.value = _reminders.value + text
            "✅ Reminder save kar diya: \"$text\""
        }
        else -> AryaToolRegistry.execute(getApplication(), call)
    }

    fun clear() { _messages.value = emptyList() }
}

/** Minimal, dependency-free +,-,*,/ and-parentheses evaluator for the `calculate` tool. */
object ArithmeticEvaluator {

    fun eval(expression: String): String = try {
        val tokens = Regex("\\d+\\.?\\d*|[()+\\-*/]")
            .findAll(expression.filter { it.isDigit() || it in "+-*/(). " })
            .map { it.value }
            .toList()
        val result = Parser(tokens).parseExpression()
        if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()
    } catch (e: Exception) {
        "error"
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0
        private fun peek() = tokens.getOrNull(pos)

        fun parseExpression(): Double {
            var value = parseTerm()
            while (peek() == "+" || peek() == "-") {
                val op = peek(); pos++
                val rhs = parseTerm()
                value = if (op == "+") value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (peek() == "*" || peek() == "/") {
                val op = peek(); pos++
                val rhs = parseFactor()
                value = if (op == "*") value * rhs else value / rhs
            }
            return value
        }

        private fun parseFactor(): Double {
            val tok = peek() ?: return 0.0
            return if (tok == "(") {
                pos++
                val v = parseExpression()
                if (peek() == ")") pos++
                v
            } else {
                pos++
                tok.toDoubleOrNull() ?: 0.0
            }
        }
    }
}
