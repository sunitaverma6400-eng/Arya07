package com.arya.ai.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class UseCaseRoute { CHAT, VISION_CHAT, AGENT_CHAT, AUDIO_SCRIBE, PROMPT_LAB, TINY_GARDEN, MOBILE_ACTIONS }

data class UseCase(
    val id: String,
    val title: String,
    val tagline: String,
    val description: String,
    val icon: ImageVector,
    val iconBackground: Color,
    val apiDocsUrl: String,
    val exampleCodeUrl: String,
    val route: UseCaseRoute,
    val experimental: Boolean = false
)

/** Every use case here runs on Arya's free online relay (Groq/Gemini/OpenRouter) — no
 *  on-device model to download or load first. */
object UseCases {
    private const val DOCS = "https://console.groq.com/docs"
    private const val EXAMPLE = "https://github.com/sunitaverma6400-eng/Arya07"

    val askImage = UseCase(
        id = "ask_image",
        title = "Ask Image",
        tagline = "Ask questions about images",
        description = "Ask questions about images — powered by Gemini vision via Arya Relay",
        icon = Icons.Filled.Image,
        iconBackground = Color(0xFFDB4437),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.VISION_CHAT
    )

    val audioScribe = UseCase(
        id = "audio_scribe",
        title = "Audio Scribe",
        tagline = "Transcribe and translate audio",
        description = "Instantly transcribe and/or translate audio clips — speech-to-text on device, translation via Arya's free online model",
        icon = Icons.Filled.Mic,
        iconBackground = Color(0xFF34A853),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.AUDIO_SCRIBE
    )

    val aiChat = UseCase(
        id = "ai_chat",
        title = "AI Chat",
        tagline = "Chat with Arya's free online model",
        description = "Chat with Arya, powered by free online models (Groq/Gemini/OpenRouter)",
        icon = Icons.Filled.Chat,
        iconBackground = Color(0xFF4285F4),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.CHAT
    )

    val agentSkills = UseCase(
        id = "agent_skills",
        title = "Agent Skills",
        tagline = "Complete agentic tasks with chat",
        description = "Chat with Arya's free online model with skills and tools",
        icon = Icons.Filled.Bolt,
        iconBackground = Color(0xFFE8A33D),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.AGENT_CHAT
    )

    val promptLab = UseCase(
        id = "prompt_lab",
        title = "Prompt Lab",
        tagline = "Single turn use cases",
        description = "Single turn use cases: summarize, rewrite, brainstorm and more, powered by Arya's free online model",
        icon = Icons.Filled.Grid3x3,
        iconBackground = Color(0xFFDB4437),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.PROMPT_LAB
    )

    val tinyGarden = UseCase(
        id = "tiny_garden",
        title = "Tiny Garden",
        tagline = "Use natural language to plant",
        description = "Use natural language to plant, water, and harvest in this mini-game — powered by " +
            "Arya's free online model, with a quick keyword fallback when the relay isn't reachable.",
        icon = Icons.Filled.LocalFlorist,
        iconBackground = Color(0xFF34A853),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.TINY_GARDEN,
        experimental = true
    )

    val mobileActions = UseCase(
        id = "mobile_actions",
        title = "Mobile Actions",
        tagline = "Leverage device mobile actions",
        description = "Perform various device actions through natural language, powered by Arya's free online model",
        icon = Icons.Filled.Functions,
        iconBackground = Color(0xFF4285F4),
        apiDocsUrl = DOCS,
        exampleCodeUrl = EXAMPLE,
        route = UseCaseRoute.MOBILE_ACTIONS,
        experimental = true
    )

    val all = listOf(askImage, audioScribe, aiChat, agentSkills, promptLab, tinyGarden, mobileActions)

    fun byId(id: String): UseCase? = all.find { it.id == id }
}
