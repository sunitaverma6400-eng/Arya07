package com.arya.ai.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Cheap always-on "did someone just start talking?" check using raw mic loudness (RMS),
 * NOT full speech recognition.
 *
 * This is the "Option B — smart duty-cycling" battery fix: [android.speech.SpeechRecognizer]
 * does real on-device STT work continuously even during silence, which is expensive. This
 * class instead reads small chunks of raw audio and only measures how loud they are. As
 * long as it's quiet, almost nothing happens (just a tiny read + a sleep). Only once the
 * loudness crosses a threshold — i.e. someone actually started speaking — does it fire
 * [onVoiceDetected], and the caller can then spin up the real SpeechRecognizer for a few
 * seconds to check whether it was actually the wake phrase.
 *
 * This is not as efficient as a dedicated hardware/DSP wake-word model (Picovoice Porcupine
 * — "Option A" — would get closer to that), but it needs no account, no API key, and no
 * extra dependency, and it cuts how often the heavy recognizer has to run.
 */
class VoiceActivityDetector(
    private val context: Context,
    private val onVoiceDetected: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // RMS level (0..~32767) above which a chunk counts as "loud". Raise this if it's
        // triggering on ambient noise/AC hum; lower it if it's missing quiet speech.
        private const val RMS_THRESHOLD = 1200.0

        // Require a couple of consecutive loud chunks before treating it as real speech,
        // so a single click/pop/tap doesn't wake the full recognizer.
        private const val CONSECUTIVE_HITS_NEEDED = 2

        // Sleep between chunks while it's quiet — this idle gap (most of the time, in
        // practice) is where the actual battery saving comes from vs. a tight loop or vs.
        // running full SpeechRecognizer nonstop.
        private const val IDLE_POLL_DELAY_MS = 150L
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile private var running = false

    /** Starts the cheap listen loop. Safe to call again after [stop]. No-op if already running. */
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return
        val bufferSize = minBuf * 2

        @SuppressLint("MissingPermission")
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
        } catch (e: Exception) {
            null
        } ?: return

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return
        }

        record = audioRecord
        running = true
        audioRecord.startRecording()

        job = scope.launch {
            val buf = ShortArray(bufferSize / 2)
            var consecutiveHits = 0
            while (running) {
                val read = try {
                    audioRecord.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    if (rms(buf, read) > RMS_THRESHOLD) {
                        consecutiveHits++
                        if (consecutiveHits >= CONSECUTIVE_HITS_NEEDED) {
                            running = false
                            withContext(Dispatchers.Main) { onVoiceDetected() }
                            break
                        }
                    } else {
                        consecutiveHits = 0
                    }
                }
                // The delay (not the read) is the point: it lets the CPU actually idle
                // between checks instead of continuously processing audio like STT does.
                delay(IDLE_POLL_DELAY_MS)
            }
            releaseRecord()
        }
    }

    /** Stops listening and releases the mic. Safe to call even if not running. */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        releaseRecord()
    }

    private fun releaseRecord() {
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val sample = buf[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / len)
    }
}
