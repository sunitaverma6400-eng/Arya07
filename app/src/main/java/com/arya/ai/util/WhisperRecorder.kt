package com.arya.ai.util

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Records the mic to a local .m4a file and transcribes it via Arya Relay's `/v1/whisper`
 * endpoint (Groq's `whisper-large-v3` — good Hindi/Hinglish support, uses the same GROQ_KEYS
 * the relay already has for online chat, no separate key needed).
 *
 * [isAvailable] tells a caller whether to even offer this flow (needs RELAY_URL configured
 * at build time). If recording or transcription fails for any other reason, [stopAndTranscribe]
 * returns null and the caller is expected to fall back to Android's built-in [android.speech.SpeechRecognizer]
 * (see [VoiceHelper.speechRecognizerIntent]) — same "try the nicer online thing, fall back to the
 * free offline thing" pattern used everywhere else in Arya (e.g. [com.arya.ai.tools.WebTools.webSearch]).
 */
class WhisperRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isAvailable: Boolean get() = WhisperUploader.isAvailable

    /** Starts recording. Caller must already hold RECORD_AUDIO permission. */
    fun startRecording() {
        val file = File.createTempFile("arya_rec_", ".m4a", context.cacheDir)
        outputFile = file
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    /** Stops recording and transcribes via the relay. Returns null on any failure — caller falls back. */
    suspend fun stopAndTranscribe(): String? = withContext(Dispatchers.IO) {
        val file = outputFile
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // stop() throws if the recording was too short/silent — nothing usable either way
        }
        recorder = null
        outputFile = null
        if (file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return@withContext null
        }
        try {
            WhisperUploader.transcribe(context, file, "audio/mp4")
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    /** Discards an in-progress recording without transcribing (e.g. user cancels). */
    fun cancelRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // ignore — we're discarding anyway
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

}
