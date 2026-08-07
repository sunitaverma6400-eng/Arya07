package com.arya.ai.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Hands-free version of [WhisperRecorder] for [com.arya.ai.service.WakeWordService]'s
 * command-listening step (after "Hey Arya" wakes it up). Records raw PCM using the same
 * [AudioRecord] primitive [VoiceActivityDetector] already uses elsewhere in this service,
 * auto-stops once it hears a stretch of silence *after* it first detected speech (or hits a
 * safety cap), writes a WAV file, and transcribes it via [WhisperUploader].
 *
 * Returns null — caller falls back to Android's [android.speech.SpeechRecognizer], same as
 * [WhisperRecorder] on the Chat screen — if there's no relay configured, nobody actually
 * spoke within the wait window, or the upload/transcription failed.
 */
class VadCommandRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SAMPLES = 1600 // ~100ms per chunk at 16kHz

        // Same RMS threshold/consecutive-hits idea as VoiceActivityDetector, reused here to
        // decide both "has speech actually started" and (inverted) "has it gone quiet again".
        private const val RMS_THRESHOLD = 1200.0
        private const val SPEECH_HITS_NEEDED = 2
        private const val SILENCE_CHUNKS_TO_STOP = 9 // ~900ms trailing silence = "done speaking"
        private const val MAX_WAIT_FOR_SPEECH_MS = 6000L
        private const val MAX_RECORDING_MS = 10000L
    }

    val isAvailable: Boolean get() = WhisperUploader.isAvailable

    /** Records until silence-after-speech or a timeout, then transcribes. Null on any failure/no speech. */
    suspend fun recordAndTranscribe(): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            WhisperUploader.setLastError("mic_permission_missing")
            return@withContext null
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            WhisperUploader.setLastError("mic_unsupported_config")
            return@withContext null
        }

        @SuppressLint("MissingPermission")
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 2)
        } catch (e: Exception) {
            null
        } ?: run { WhisperUploader.setLastError("mic_busy_or_unavailable"); return@withContext null }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            WhisperUploader.setLastError("mic_init_failed")
            return@withContext null
        }

        val file = File.createTempFile("arya_cmd_", ".wav", context.cacheDir)
        var hasSpoken = false

        try {
            record.startRecording()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44)) // placeholder header, patched once the final size is known
                val buf = ShortArray(CHUNK_SAMPLES)
                val byteBuf = ByteArray(CHUNK_SAMPLES * 2)
                var speechHits = 0
                var silenceChunks = 0
                var totalMs = 0L

                while (true) {
                    val read = try { record.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                    if (read <= 0) break

                    for (i in 0 until read) {
                        val s = buf[i].toInt()
                        byteBuf[i * 2] = (s and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    raf.write(byteBuf, 0, read * 2)

                    totalMs += (read * 1000L) / SAMPLE_RATE
                    if (rms(buf, read) > RMS_THRESHOLD) {
                        speechHits++
                        silenceChunks = 0
                        if (speechHits >= SPEECH_HITS_NEEDED) hasSpoken = true
                    } else {
                        speechHits = 0
                        if (hasSpoken) silenceChunks++
                    }

                    if (hasSpoken && silenceChunks >= SILENCE_CHUNKS_TO_STOP) break
                    if (!hasSpoken && totalMs >= MAX_WAIT_FOR_SPEECH_MS) break
                    if (totalMs >= MAX_RECORDING_MS) break
                }
                writeWavHeader(raf)
            }
            record.stop()
            record.release()
        } catch (e: Exception) {
            try {
                record.stop()
                record.release()
            } catch (e2: Exception) {
                // already in a bad state — nothing more to clean up
            }
            file.delete()
            WhisperUploader.setLastError("recording_error: ${e.message}")
            return@withContext null
        }

        if (!hasSpoken) {
            file.delete()
            WhisperUploader.setLastError("no_speech_detected_in_time")
            return@withContext null
        }
        try {
            WhisperUploader.transcribe(file, "audio/wav")
        } catch (e: Exception) {
            WhisperUploader.setLastError("${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            file.delete()
        }
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val sample = buf[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / len)
    }

    /** Patches the 44-byte placeholder at the start of [raf] into a real WAV header now that the final data size is known. */
    private fun writeWavHeader(raf: RandomAccessFile) {
        val dataSize = (raf.length() - 44).toInt()
        raf.seek(0)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2) // byte rate (16-bit mono)
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        raf.write(header.array())
    }
}
