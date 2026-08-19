package com.arya.ai.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
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

        // Bug fix (see chat history): a single fixed RMS_THRESHOLD can't account for how much
        // mic gain/ambient noise varies phone-to-phone and room-to-room — too low for a noisy
        // phone/room and it fires on ambient hum constantly (the "hamesha blink tone bajta hai"
        // symptom: SpeechRecognizer keeps getting started for nothing, and each start plays
        // Android's own recognizer beep). [start] now samples a short burst of ambient audio
        // first and calibrates the actual threshold off that, instead of using this constant
        // directly. RMS_THRESHOLD_FLOOR is the safety minimum so a dead-silent room doesn't
        // calibrate to something so low it picks up a whisper of AC hum.
        private const val RMS_THRESHOLD_FLOOR = 1200.0

        // How many ~150ms chunks to sample for the ambient-noise calibration before the real
        // detection loop starts — ~6 chunks is under a second, short enough nobody notices the
        // service "warming up" when the screen turns on.
        private const val CALIBRATION_CHUNKS = 6

        // How far above the measured noise floor counts as "someone started talking". Higher
        // = fewer false triggers but risks missing quiet speech; lower = the opposite. 2.5x is
        // a middle-ground starting point — see this class's kdoc if it still needs tuning after
        // a build.
        private const val CALIBRATION_MULTIPLIER = 2.5

        // Require a couple of consecutive loud chunks before treating it as real speech,
        // so a single click/pop/tap doesn't wake the full recognizer.
        private const val CONSECUTIVE_HITS_NEEDED = 2

        // Sleep between chunks while it's quiet — this idle gap (most of the time, in
        // practice) is where the actual battery saving comes from vs. a tight loop or vs.
        // running full SpeechRecognizer nonstop.
        private const val IDLE_POLL_DELAY_MS = 150L
    }

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
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

        // Option A (see chat history — the "Arya hears her own voice and loops" bug): most
        // phones have a hardware/OEM-implemented echo canceller sitting behind this exact
        // Android API, bound to a specific AudioRecord session. VOICE_RECOGNITION as an
        // AudioSource deliberately ships with AEC/AGC/NS off (raw audio is what STT wants),
        // which is *why* this class's own TTS-echo self-triggering was possible in the first
        // place — attaching one explicitly here undoes that, on whichever devices support it.
        // isAvailable() is false on some budget/older devices; this silently no-ops there
        // rather than failing [start] outright, same as every other best-effort branch in this
        // class. A from-source WebRTC AEC build would work everywhere regardless of OEM
        // support, at real integration cost/risk (see chat history) — worth revisiting if this
        // turns out insufficient on a real device.
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.also { it.enabled = true }
            }
        } catch (e: Exception) {
            aec = null
        }

        record = audioRecord
        running = true
        audioRecord.startRecording()

        job = scope.launch {
            val buf = ShortArray(bufferSize / 2)
            val threshold = calibrateThreshold(audioRecord, buf)
            var consecutiveHits = 0
            while (running) {
                val read = try {
                    audioRecord.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    if (rms(buf, read) > threshold) {
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

    /** Samples [CALIBRATION_CHUNKS] chunks of whatever's currently coming through the mic
     *  (assumed to be ambient noise, not someone mid-sentence — a reasonable bet right after
     *  [start] is called, e.g. right after the screen turns on or the service restarts) and
     *  returns a threshold set relative to that, floored at [RMS_THRESHOLD_FLOOR]. */
    private suspend fun calibrateThreshold(audioRecord: AudioRecord, buf: ShortArray): Double {
        var sum = 0.0
        var samples = 0
        repeat(CALIBRATION_CHUNKS) {
            val read = try {
                audioRecord.read(buf, 0, buf.size)
            } catch (e: Exception) {
                -1
            }
            if (read > 0) {
                sum += rms(buf, read)
                samples++
            }
            delay(IDLE_POLL_DELAY_MS)
        }
        val ambientFloor = if (samples > 0) sum / samples else RMS_THRESHOLD_FLOOR
        return maxOf(RMS_THRESHOLD_FLOOR, ambientFloor * CALIBRATION_MULTIPLIER)
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
            aec?.release()
        } catch (_: Exception) {
        }
        aec = null
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
