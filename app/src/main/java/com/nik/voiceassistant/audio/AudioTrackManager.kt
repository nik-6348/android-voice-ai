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
    private val usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
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

    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    // Each chunk carries the flush generation it was queued in
    private val audioQueue = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
    private val isPlaying = AtomicBoolean(false)

    // Frames written since the track was last flushed, and whether reply audio is audible now
    private val framesWritten = AtomicLong(0)
    private val audible = AtomicBoolean(false)

    // When the track finished everything written (0 = still playing)
    private var drainedAt = 0L

    // Bumped by every flush: a chunk taken off the queue before a flush is stale and never plays
    private val flushGeneration = AtomicLong(0)
    // Serializes flushing, rebuilding and releasing the track (never held during a blocking write)
    private val trackLock = Any()
    // Last time the track was (re)built after a failure, so a broken audioserver isn't hammered
    private var rebuiltAt = 0L

    /** Reply audio is coming out of the speaker now (or just stopped, within TAIL_MS). */
    fun isAudible(): Boolean = audible.get()

    /**
     * Blocks the calling thread until the audio queue has drained and the speaker has gone silent,
     * or until [maxMs] milliseconds have passed (safety cap so a tool never stalls indefinitely).
     * Call this before an acting tool so the voice finishes before the action fires on-screen.
     */
    fun waitUntilSilent(maxMs: Long = 3000L) {
        val deadline = System.currentTimeMillis() + maxMs
        while (isAudible() && System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
        }
    }

    private fun setAudible(value: Boolean) {
        if (audible.getAndSet(value) != value) onPlaybackStateChanged?.invoke(value)
    }

    fun initialize() {
        synchronized(trackLock) {
            if (audioTrack != null) return
            audioTrack = buildTrack()
        }
    }

    private fun buildTrack(): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, 24000 * 2) // ~1 sec buffer capacity

        // Usage dictates how Android and the hardware AEC process the audio.
        // USAGE_VOICE_COMMUNICATION is filtered from the mic by hardware AEC (normal assistant mode).
        // USAGE_ASSISTANT / USAGE_MEDIA is heard by the mic (required for cellular phone call coupling).
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack: ${e.message}", e)
            return null
        }

        try {
            track.play()
            isPlaying.set(true)
            Log.i(TAG, "AudioTrack initialized at $SAMPLE_RATE Hz, bufferSize: $bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack: ${e.message}", e)
        }
        return track
    }

    /**
     * Replaces a track that stopped working (audioserver restarted → ERROR_DEAD_OBJECT on every
     * write), at most once a second. Returns the new track, or null if it can't be built right now.
     */
    private fun rebuildTrack(broken: AudioTrack?): AudioTrack? = synchronized(trackLock) {
        val current = audioTrack
        if (current != null && current !== broken) return current // someone else already rebuilt it
        val now = System.currentTimeMillis()
        if (now - rebuiltAt < 1000) return null
        rebuiltAt = now
        broken?.let { runCatching { it.release() } }
        framesWritten.set(0)
        audioTrack = buildTrack()
        audioTrack
    }

    fun startPlaybackWorker(coroutineScope: CoroutineScope) {
        initialize()
        playbackJob?.cancel()

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val (generation, chunk) = audioQueue.tryReceive().getOrNull() ?: if (!audible.get()) {
                    // Idle: sleep until the next reply instead of waking up every 50 ms
                    audioQueue.receive()
                } else {
                    // Nothing queued: report silence once the speaker has played everything written
                    val head = audioTrack?.playbackHeadPosition?.toLong() ?: Long.MAX_VALUE
                    if (head >= framesWritten.get()) {
                        val now = System.currentTimeMillis()
                        if (drainedAt == 0L) drainedAt = now
                        if (now - drainedAt >= TAIL_MS) setAudible(false)
                    }
                    withTimeoutOrNull(50) { audioQueue.receive() }
                } ?: continue
                // Queued before the last flush: part of the interrupted reply
                if (flushGeneration.get() != generation) continue
                val track = audioTrack ?: rebuildTrack(null) ?: continue

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.play()
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack play state error: ${e.message}")
                    }
                }

                // Flushed while this chunk was on its way: it belongs to the interrupted reply
                if (flushGeneration.get() != generation) continue
                drainedAt = 0L
                setAudible(true)
                var written = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack write failed ($written); rebuilding the track")
                    val fresh = rebuildTrack(track)
                    if (fresh != null && flushGeneration.get() == generation) {
                        written = fresh.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
                if (flushGeneration.get() != generation) {
                    // A flush landed during the blocking write, and the rest of this stale chunk went in
                    // after it: flush again (nothing newer has been written; this is the only writer)
                    flushTrack()
                    continue
                }
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
        audioQueue.trySend(flushGeneration.get() to chunk)
    }

    /**
     * Barge-in handler: instantly stops and discards pending audio frames.
     * Called when user starts speaking or server sends {"type": "interrupted"}.
     */
    fun stopAndFlush() {
        Log.i(TAG, "Barge-in triggered: flushing audio track queue")
        // Before draining: chunks already queued or taken by the worker are now stale and never play
        flushGeneration.incrementAndGet()
        // Drain any pending items in channel
        while (audioQueue.tryReceive().isSuccess) {
            // drop
        }

        flushTrack()
        setAudible(false)

        onPlaybackAmplitudeChanged?.invoke(0f)
    }

    private fun flushTrack() {
        synchronized(trackLock) {
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
        }
    }

    /** Stops the worker and frees the track; startPlaybackWorker() builds a new one. */
    fun release() {
        playbackJob?.cancel()
        playbackJob = null
        flushGeneration.incrementAndGet()

        synchronized(trackLock) {
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
            framesWritten.set(0)
        }
        isPlaying.set(false)
        setAudible(false)
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
