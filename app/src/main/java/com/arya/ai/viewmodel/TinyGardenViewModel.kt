package com.arya.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arya.ai.data.GardenState
import com.arya.ai.inference.ToolCallParser
import com.arya.ai.inference.ToolDefinition
import com.arya.ai.inference.ToolParam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val GARDEN_TOOLS = listOf(
    ToolDefinition(
        "plant", "Ek plot me beej lagata hai",
        listOf(ToolParam("plot", "number", "Plot index 0-5"), ToolParam("seed", "string", "Seed/plant ka naam"))
    ),
    ToolDefinition("water", "Ek plot ko paani deta hai", listOf(ToolParam("plot", "number", "Plot index 0-5"))),
    ToolDefinition("harvest", "Ek grown plot ko harvest karta hai", listOf(ToolParam("plot", "number", "Plot index 0-5")))
)

/**
 * @param generateOnline Calls the free online relay (Groq/Gemini/OpenRouter) with a full
 * prompt and returns the raw reply text. Null (e.g. relay not configured) means every
 * command goes straight to the keyword fallback below.
 */
class TinyGardenViewModel(
    private val generateOnline: (suspend (String) -> String)? = null
) : ViewModel() {

    private val _garden = MutableStateFlow(GardenState())
    val garden: StateFlow<GardenState> = _garden.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** Runs [command] through the online model if configured; falls back to a keyword parser otherwise. */
    fun runCommand(command: String) {
        if (command.isBlank() || _isThinking.value) return
        _isThinking.value = true

        viewModelScope.launch {
            try {
                val online = generateOnline
                if (online != null) {
                    val systemPrompt = ToolCallParser.buildSystemPrompt(
                        GARDEN_TOOLS,
                        persona = "Tum Tiny Garden game ke assistant ho. User ke command ko tool call me convert karo."
                    )
                    val raw = online("$systemPrompt\n\nUser: $command")
                    val call = ToolCallParser.parseToolCall(raw)
                    if (call != null) {
                        applyToolCall(call.name, call.args)
                    } else {
                        _garden.value = _garden.value.withLog("🤔 Samajh nahi aaya: \"$command\"")
                    }
                } else {
                    applyKeywordFallback(command)
                }
            } catch (e: Exception) {
                applyKeywordFallback(command)
            } finally {
                _isThinking.value = false
            }
        }
    }

    private fun applyToolCall(name: String, args: Map<String, String>) {
        val plot = args["plot"]?.toIntOrNull() ?: -1
        _garden.value = when (name) {
            "plant" -> _garden.value.plant(plot, args["seed"] ?: "beej")
            "water" -> _garden.value.water(plot)
            "harvest" -> _garden.value.harvest(plot)
            else -> _garden.value.withLog("🤔 Tool '$name' pehchana nahi gaya.")
        }
    }

    /** No-model fallback so the game stays playable before any model is downloaded/loaded. */
    private fun applyKeywordFallback(command: String) {
        val lower = command.lowercase()
        val plotMatch = Regex("\\d+").find(lower)?.value?.toIntOrNull() ?: 0
        _garden.value = when {
            "plant" in lower || "laga" in lower -> {
                val seed = lower.substringAfter("plant").substringAfter("laga").trim().ifBlank { "beej" }
                _garden.value.plant(plotMatch, seed.take(20))
            }
            "water" in lower || "paani" in lower -> _garden.value.water(plotMatch)
            "harvest" in lower || "kaato" in lower || "todo" in lower -> _garden.value.harvest(plotMatch)
            else -> _garden.value.withLog("🤔 Model load nahi hai — 'plant', 'water', ya 'harvest' + plot number try karo.")
        }
    }

    fun reset() { _garden.value = GardenState() }
}
