package com.arya.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arya.ai.data.DeviceActions
import com.arya.ai.inference.ToolCallParser
import com.arya.ai.inference.ToolDefinition
import com.arya.ai.inference.ToolParam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActionLogEntry(val command: String, val result: String)

private val DEVICE_TOOLS = listOf(
    ToolDefinition("open_camera", "Camera app kholta hai"),
    ToolDefinition("open_dialer", "Dialer kholta hai", listOf(ToolParam("number", "string", "optional phone number"))),
    ToolDefinition("open_browser", "Browser me ek URL kholta hai", listOf(ToolParam("url", "string", "website URL"))),
    ToolDefinition("open_maps", "Maps me kuch search karta hai", listOf(ToolParam("query", "string", "jagah/address"))),
    ToolDefinition("toggle_flashlight", "Flashlight on/off karta hai", listOf(ToolParam("on", "boolean", "\"true\" ya \"false\"")))
)

/**
 * @param generateOnline Calls the free online relay with a full prompt, returns the reply
 * text. Null means every command goes straight to the keyword fallback below.
 */
class MobileActionsViewModel(
    app: Application,
    private val generateOnline: (suspend (String) -> String)? = null
) : AndroidViewModel(app) {

    private val _log = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val log: StateFlow<List<ActionLogEntry>> = _log.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    fun runCommand(command: String) {
        if (command.isBlank() || _isThinking.value) return
        _isThinking.value = true

        viewModelScope.launch {
            val result = try {
                val online = generateOnline
                if (online != null) {
                    val systemPrompt = ToolCallParser.buildSystemPrompt(
                        DEVICE_TOOLS,
                        persona = "Tum device actions control karne wale assistant ho."
                    )
                    val raw = online("$systemPrompt\n\nUser: $command")
                    val call = ToolCallParser.parseToolCall(raw)
                    if (call != null) executeAction(call.name, call.args) else "🤔 Samajh nahi aaya: \"$command\""
                } else {
                    keywordFallback(command)
                }
            } catch (e: Exception) {
                keywordFallback(command)
            }
            _log.value = _log.value + ActionLogEntry(command, result)
            _isThinking.value = false
        }
    }

    private fun executeAction(name: String, args: Map<String, String>): String {
        val context = getApplication<Application>()
        return when (name) {
            "open_camera" -> DeviceActions.openCamera(context)
            "open_dialer" -> DeviceActions.openDialer(context, args["number"])
            "open_browser" -> DeviceActions.openBrowser(context, args["url"])
            "open_maps" -> DeviceActions.openMaps(context, args["query"])
            "toggle_flashlight" -> DeviceActions.toggleFlashlight(context, args["on"]?.equals("true", true) ?: true)
            else -> "Tool '$name' pehchana nahi gaya."
        }
    }

    /** No-model fallback so the screen is still usable before a model is downloaded. */
    private fun keywordFallback(command: String): String {
        val lower = command.lowercase()
        val context = getApplication<Application>()
        return when {
            "camera" in lower -> DeviceActions.openCamera(context)
            "call" in lower || "dial" in lower -> DeviceActions.openDialer(context, Regex("\\d{6,}").find(lower)?.value)
            "map" in lower -> DeviceActions.openMaps(context, lower.substringAfter("map").trim().ifBlank { null })
            "flashlight" in lower || "torch" in lower -> DeviceActions.toggleFlashlight(context, "off" !in lower)
            "browser" in lower || "open" in lower && ("http" in lower || ".com" in lower) ->
                DeviceActions.openBrowser(context, Regex("\\S+\\.\\S+").find(lower)?.value)
            else -> "🤔 Model load nahi hai — 'camera', 'call', 'map', 'flashlight' jaise keywords try karo."
        }
    }
}
