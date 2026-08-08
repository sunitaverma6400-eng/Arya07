package com.arya.ai.util

/**
 * The single fixed vocabulary of emotion tags shared across the whole pipeline:
 *  - arya-relay's `EMOTION_TAGS` (server asks the LLM to prefix replies with `[emotion:xxx]`)
 *  - [OnlineChatHelper.OnlineChatResult.emotion] / the streaming `onEmotion` callback
 *  - [VoiceHelper.speak]'s `emotion` param (shapes ElevenLabs delivery)
 *  - [VrmAvatarView]'s `setExpression` (shapes the 3D face's blendshapes)
 *
 * Keeping it as one small closed list (instead of letting the LLM emit free-form labels)
 * means every layer downstream can rely on a known, finite set instead of guessing.
 */
object AvatarEmotion {
    const val NEUTRAL = "neutral"
    const val HAPPY = "happy"
    const val SAD = "sad"
    const val ANGRY = "angry"
    const val SURPRISED = "surprised"
    const val CARING = "caring"
    const val PLAYFUL = "playful"
    const val SERIOUS = "serious"

    val ALL = setOf(NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, CARING, PLAYFUL, SERIOUS)

    /** Defensive validation — the relay already restricts to [ALL], but this is what the
     *  client trusts before interpolating a value into a JS call or an API request. */
    fun sanitize(tag: String?): String {
        val t = tag?.lowercase()?.trim()
        return if (t != null && t in ALL) t else NEUTRAL
    }

    /**
     * (pitch, speechRate) multipliers (1f = engine default) for Android's plain system
     * [android.speech.tts.TextToSpeech] fallback — used when ElevenLabs isn't reachable
     * (relay down/not configured/request failed). ElevenLabs gets its emotional delivery from
     * the relay's per-emotion `voice_settings` (+ v3 audio tags) instead; system TTS has no
     * "style"/expressiveness knob, so pitch/rate is the only lever available here, same idea
     * restored from an earlier build of [com.arya.ai.service.WakeWordService] that only
     * covered the wake-word path — this extends it to every [VoiceHelper.speak] caller too, so
     * the fallback voice still sounds emotionally distinct instead of always flat.
     */
    fun systemTtsSettings(tag: String): Pair<Float, Float> = when (sanitize(tag)) {
        HAPPY -> 1.1f to 1.05f
        PLAYFUL -> 1.15f to 1.1f
        CARING -> 1.02f to 0.95f
        SAD -> 0.92f to 0.92f
        SERIOUS -> 0.97f to 0.97f
        ANGRY -> 0.95f to 1.08f
        SURPRISED -> 1.18f to 1.05f
        else -> 1.0f to 1.0f
    }
}
