package com.arya.ai.util

import android.graphics.Bitmap

enum class VisionSource { NONE, CAMERA, SCREEN }

/**
 * Holds the most recent frame from either the camera (`CameraFrameCapture`, only while
 * `LiveConversationScreen` is foregrounded — Android doesn't allow background camera access)
 * or a shared screen (`ScreenShareCaptureService`, can keep running in the background as a
 * foreground service). [com.arya.ai.service.WakeWordService] reads [latest] when answering a
 * live-conversation command, so voice + vision stay decoupled — the mic loop doesn't need to
 * know or care whether a frame is coming from the camera, the screen, or nowhere at all.
 *
 * Deliberately process-memory-only (no persistence) — a frame older than a few seconds isn't
 * "what the user is looking at right now" anymore, so callers should check [timestampMs].
 */
object VisionFrameProvider {
    @Volatile var latest: Bitmap? = null
        private set
    @Volatile var source: VisionSource = VisionSource.NONE
        private set
    @Volatile var timestampMs: Long = 0
        private set

    fun update(bitmap: Bitmap, from: VisionSource) {
        latest = bitmap
        source = from
        timestampMs = System.currentTimeMillis()
    }

    fun clear() {
        latest = null
        source = VisionSource.NONE
        timestampMs = 0
    }

    /** A frame is only useful if it's recent — anything older is probably stale/irrelevant. */
    fun freshFrame(maxAgeMs: Long = 5000): Bitmap? {
        val frame = latest ?: return null
        return if (System.currentTimeMillis() - timestampMs <= maxAgeMs) frame else null
    }
}
