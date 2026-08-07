package com.arya.ai.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveStatus { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * In-process (no IPC needed — [com.arya.ai.service.WakeWordService] and the UI share the
 * same process) shared state for Gemini-Live-style continuous conversation mode.
 * [com.arya.ai.service.WakeWordService] writes to this as it moves through its loop;
 * `LiveConversationScreen` collects it to animate/label the UI.
 */
object LiveConversationState {
    val isActive = MutableStateFlow(false)
    val status = MutableStateFlow(LiveStatus.IDLE)
    val lastUserText = MutableStateFlow("")
    val lastAryaText = MutableStateFlow("")
    // Written by WakeWordService from OnlineChatHelper's onEmotion callback (fires as soon as
    // the relay resolves the reply's [emotion:xxx] tag) and from VoiceHelper's onMouthLevel
    // (real ElevenLabs playback amplitude, ~30-60Hz while SPEAKING). LiveConversationScreen
    // feeds both straight into VrmAvatarController.
    val lastEmotion = MutableStateFlow(AvatarEmotion.NEUTRAL)
    val mouthLevel = MutableStateFlow(0f)
}
