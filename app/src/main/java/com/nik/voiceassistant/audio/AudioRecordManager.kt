package com.nik.voiceassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Handles continuous 16kHz 16-bit Mono PCM audio recording from the device microphone.
 * Designed for Gemini Live full-duplex real-time audio pipeline.
 */
class AudioRecordManager(
    private val onAudioChunkReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: ((Float) -> Unit)? = null,
    // The mic couldn't start, or died mid-conversation (resources are already released by then).
    // Called on the caller's thread for start failures, on the recording (IO) thread for read errors
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioRecordManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 512 samples * 2 bytes = 1024 bytes per chunk (approx. 32ms frames)
        const val CHUNK_SIZE_BYTES = 1024

        // Noise gate (16-bit RMS units): open above max(floor * GATE_RATIO, GATE_MIN_RMS),
        // stay open GATE_HOLD_MS after the last loud chunk so word endings aren't clipped
        private const val GATE_RATIO = 2.5
        private const val GATE_MIN_RMS = 200.0
        private const val GATE_HOLD_MS = 600L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    // Noise gate state: background level learned from quiet chunks, gate open until this time
    private var noiseFloor = 0.0
    private var openUntil = 0L
    private var previousChunk: ByteArray? = null

    /** Returns true if the mic is recording now (also when it already was); on false, onError was called. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Recording is already active")
            return true
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_BYTES * 4)

        try {
            // VOICE_COMMUNICATION enables hardware Acoustic Echo Cancellation (AEC) & Noise Suppression (NS)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            audioRecord = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return fail("AudioRecord initialization failed")
            }

            // On top of the VOICE_COMMUNICATION source's own processing, where the phone offers them
            record.audioSessionId.let { sid ->
                if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(sid)?.apply { enabled = true }
                if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
            }
            noiseFloor = 0.0
            openUntil = 0L
            previousChunk = null

            record.startRecording()
            // e.g. another app holds the mic exclusively
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return fail("AudioRecord did not start recording")
            }
            isRecording.set(true)
            Log.i(TAG, "AudioRecord started at $SAMPLE_RATE Hz, bufferSize: $bufferSize")

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val audioBuffer = ByteArray(CHUNK_SIZE_BYTES)

                while (isActive && isRecording.get()) {
                    val bytesRead = record.read(audioBuffer, 0, CHUNK_SIZE_BYTES)
                    if (bytesRead > 0) {
                        val chunkCopy = audioBuffer.copyOf(bytesRead)
                        val level = calculateRms(chunkCopy, bytesRead)
                        for (out in gate(chunkCopy, level * 32768.0)) onAudioChunkReady(out)

                        // Normalized RMS amplitude for visualization [0.0 .. 1.0]
                        onAmplitudeChanged?.invoke(level)
                    } else if (bytesRead < 0) {
                        // Not after stopRecording(): that clears isRecording first. Free the dead mic and
                        // say so, instead of leaving a conversation that silently hears nothing
                        if (closeAfterError(record)) {
                            Log.e(TAG, "AudioRecord read error: $bytesRead")
                            onError("Microphone stopped working (error $bytesRead)")
                        }
                        break
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            return fail("Failed to start recording: ${e.message}")
        }
    }

    private fun fail(message: String): Boolean {
        Log.e(TAG, message)
        release()
        onError(message)
        return false
    }

    /** Releases [record] if it is still the live one; false if it was already stopped (or replaced). */
    @Synchronized
    private fun closeAfterError(record: AudioRecord): Boolean {
        if (audioRecord !== record || !isRecording.compareAndSet(true, false)) return false
        recordingJob = null
        runCatching { record.stop() }
        release()
        return true
    }

    @Synchronized
    fun stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping audio recording")

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord: ${e.message}")
        }
        release()
    }

    fun isRecordingActive(): Boolean = isRecording.get()

    /**
     * Noise gate: steady background noise (fan, AC, traffic) goes out as silence, so the voice
     * model's speech detection only fires on speech. Silence instead of nothing keeps the stream
     * continuous. Opening also sends the chunk before it, so the first syllable isn't cut.
     */
    private fun gate(chunk: ByteArray, rms: Double): List<ByteArray> {
        val now = System.currentTimeMillis()
        val threshold = maxOf(noiseFloor * GATE_RATIO, GATE_MIN_RMS)
        val wasOpen = now < openUntil
        if (rms > threshold) {
            openUntil = now + GATE_HOLD_MS
        } else {
            // Learn the floor from quiet chunks only: quickly down, slowly up (~10 s)
            noiseFloor = when {
                noiseFloor == 0.0 -> rms
                rms < noiseFloor -> noiseFloor * 0.8 + rms * 0.2
                else -> noiseFloor * 0.997 + rms * 0.003
            }
        }
        val previous = previousChunk
        previousChunk = chunk
        return when {
            now < openUntil && !wasOpen && previous != null -> listOf(previous, chunk)
            now < openUntil -> listOf(chunk)
            else -> listOf(ByteArray(chunk.size))
        }
    }

    private fun release() {
        noiseSuppressor?.release()
        echoCanceler?.release()
        noiseSuppressor = null
        echoCanceler = null
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audioRecord: ${e.message}")
        } finally {
            audioRecord = null
            isRecording.set(false)
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val numSamples = length / 2
        if (numSamples == 0) return 0f

        var i = 0
        while (i < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += shortSample * shortSample
            i += 2
        }

        val rms = sqrt(sum / numSamples)
        // Normalize 16-bit PCM range (0..32767) into ~ 0.0f..1.0f
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
