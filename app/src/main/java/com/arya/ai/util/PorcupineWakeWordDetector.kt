package com.arya.ai.util

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerErrorCallback
import android.content.Context

/**
 * Wraps Picovoice's [PorcupineManager] for the "Hey Arya" wake word — this is "Option A"
 * from the battery-optimization plan: a tiny dedicated keyword-spotting model that runs
 * continuously for a fraction of the battery cost of looping Android's full
 * [android.speech.SpeechRecognizer] or even the VAD-based [VoiceActivityDetector] approach
 * ("Option B"), because it never runs real speech-to-text — it only ever checks "was that
 * the trained keyword?".
 *
 * This needs TWO things that only the user can provide (neither can be generated here):
 *  1. A free Picovoice AccessKey — get one at console.picovoice.ai and save it in the app
 *     under Settings -> API Keys -> "Hey Arya" wake word.
 *  2. A custom "Hey Arya" keyword file (.ppn) — train it for free in the same Picovoice
 *     Console, download the **Android** build of it, and place it at
 *     app/src/main/assets/hey-arya_android.ppn in this project before building the app.
 *     This is a trained binary model file; it cannot be produced in code, only by
 *     Picovoice's own training service.
 *
 * If either piece is missing, [start] returns null and [isAvailable] returns false, and
 * the caller (WakeWordService) should fall back to Option B, which needs neither.
 */
object PorcupineWakeWordDetector {

    /** Filename this app expects in assets/ once the user drops in their trained model. */
    const val CUSTOM_KEYWORD_ASSET = "hey-arya_android.ppn"

    /** True once the user has placed their trained "Hey Arya" model in the assets folder. */
    fun isAvailable(context: Context): Boolean =
        try {
            context.assets.open(CUSTOM_KEYWORD_ASSET).close()
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Builds and starts a [PorcupineManager] listening for the custom "Hey Arya" keyword.
     * Returns null instead of throwing on any setup problem (bad/missing AccessKey, missing
     * asset, no mic permission, incompatible device, etc.) so the caller can cleanly fall
     * back to Option B rather than crash the service.
     */
    fun start(
        context: Context,
        accessKey: String,
        onWakeWordDetected: () -> Unit,
        onError: (String) -> Unit
    ): PorcupineManager? {
        if (accessKey.isBlank() || !isAvailable(context)) return null
        return try {
            PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(CUSTOM_KEYWORD_ASSET))
                .setSensitivity(0.6f)
                .setErrorCallback(PorcupineManagerErrorCallback { e ->
                    onError(e.message ?: "Porcupine error")
                })
                .build(context) { _ -> onWakeWordDetected() }
                .also { it.start() }
        } catch (e: PorcupineException) {
            onError(e.message ?: "Porcupine setup failed")
            null
        } catch (e: Exception) {
            onError(e.message ?: "Porcupine setup failed")
            null
        }
    }
}
