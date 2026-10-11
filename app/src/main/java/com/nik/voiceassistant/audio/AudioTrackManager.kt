package com.nik.voiceassistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Handles low-latency 24kHz 16-bit Mono PCM audio playback received from Gemini Live WebSocket.
 * Features instant barge-in / interruption flush to cut assistant speech immediately when needed.
 */
class AudioTrackManager(
    private val onPlaybackAmplitudeChanged: ((Float) -> Unit)? = null,
    // true when reply audio starts coming out of the speaker, false once all of it has played
    // (plus a short tail); the mic is sent as silence meanwhile so the assistant doesn't hear itself
    private val onPlaybackStateChanged: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AudioTrackManager"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // After the last sample has played, the room still echoes and the speaker lags a little;
        // a gap this short between network chunks also shouldn't count as the reply ending
        private const val TAIL_MS = 600L
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val isPlaying = AtomicBoolean(false)

    // Frames written since the track was last flushed, and whether reply audio is audible now
    private val framesWritten = AtomicLong(0)
    private val audible = AtomicBoolean(false)

    // When the track finished everything written (0 = still playing)
    private var drainedAt = 0L

    /** Reply audio is coming out of the speaker now (or just stopped, within TAIL_MS). */
    fun isAudible(): Boolean = audible.get()

    private fun setAudible(value: Boolean) {
        if (audible.getAndSet(value) != value) onPlaybackStateChanged?.invoke(value)
    }

    fun initialize() {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, 24000 * 2) // ~1 sec buffer capacity

        // Voice-communication usage so the phone's echo canceller removes the reply from the mic (lets the
        // user cut in by voice). It plays at the in-call volume, which CallAudio turns up for the
        // conversation (USAGE_ASSISTANT was louder but left the reply in the mic)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack?.play()
            isPlaying.set(true)
            Log.i(TAG, "AudioTrack initialized at $SAMPLE_RATE Hz, bufferSize: $bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack: ${e.message}", e)
        }
    }

    fun startPlaybackWorker(coroutineScope: CoroutineScope) {
        initialize()
        playbackJob?.cancel()

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = audioQueue.tryReceive().getOrNull() ?: run {
                    // Nothing queued: report silence once the speaker has played everything written
                    val head = audioTrack?.playbackHeadPosition?.toLong() ?: Long.MAX_VALUE
                    if (audible.get() && head >= framesWritten.get()) {
                        val now = System.currentTimeMillis()
                        if (drainedAt == 0L) drainedAt = now
                        if (now - drainedAt >= TAIL_MS) setAudible(false)
                    }
                    withTimeoutOrNull(50) { audioQueue.receive() }
                } ?: continue
                val track = audioTrack ?: continue
                drainedAt = 0L
                setAudible(true)

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.play()
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack play state error: ${e.message}")
                    }
                }

                val written = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written > 0) {
                    framesWritten.addAndGet(written / 2L)
                    onPlaybackAmplitudeChanged?.let { callback ->
                        val rms = calculateRms(chunk, written)
                        callback(rms)
                    }
                }
            }
        }
    }

    /**
     * Enqueue a PCM chunk from WebSocket server for playback.
     */
    fun enqueueAudioChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        audioQueue.trySend(chunk)
    }

    /**
     * Barge-in handler: instantly stops and discards pending audio frames.
     * Called when user starts speaking or server sends {"type": "interrupted"}.
     */
    fun stopAndFlush() {
        Log.i(TAG, "Barge-in triggered: flushing audio track queue")
        // Drain any pending items in channel
        while (audioQueue.tryReceive().isSuccess) {
            // drop
        }

        val track = audioTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
                track.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while flushing AudioTrack: ${e.message}")
        }
        // flush() resets the playback head to 0
        framesWritten.set(0)
        setAudible(false)

        onPlaybackAmplitudeChanged?.invoke(0f)
    }

    fun release() {
        playbackJob?.cancel()
        playbackJob = null

        val track = audioTrack
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
            }
        }
        audioTrack = null
        isPlaying.set(false)
        onPlaybackAmplitudeChanged?.invoke(0f)
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
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
