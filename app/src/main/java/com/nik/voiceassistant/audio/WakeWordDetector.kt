package com.nik.voiceassistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Listens for "Hi Nik" (also Hey / Hello / Okay Nik) fully on the phone with Vosk; no audio
 * leaves the device until the wake word is heard.
 *
 * The recognizer only knows a small grammar: the wake phrases plus look-alike words ("nickel",
 * "stick", "neck", …) that soak up similar sounds, so only a real "hi nick" matches. "Hi Nikhil"
 * comes out as "hi nick hill" and is ignored (tested with synthetic voices).
 *
 * On a match it hands over the last few seconds of audio, so a request said in the same breath
 * ("Hi Nik, YouTube kholo") reaches the assistant too.
 */
class WakeWordDetector(
    private val context: Context,
    private val assistantNameProvider: () -> String = { "Shiv" },
    // Called once listening has started: true if Android gave a silenced (muted) mic
    private val onListening: (silenced: Boolean) -> Unit = {},
    // Called (on the listening thread) when listening gave up on its own: mic or recognizer kept failing
    private val onError: (reason: String) -> Unit = {},
    private val onWake: (preroll: ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 3200 // 100 ms
        private const val PREROLL_BYTES = SAMPLE_RATE * 2 * 4 // 4 s

        // Mic failures in a row (dead audioserver, mic taken) before giving up: reopened after 0.5, 1, 2, 4, 8 s
        private const val MAX_FAILURES = 5
        private const val RETRY_BASE_MS = 500L

        private val BASE_GRAMMAR = listOf(
            // Shiv and Indian English phonetic lookalikes
            "hi shiv", "hey shiv", "hello shiv", "okay shiv", "ok shiv", "shiv",
            "hi shiva", "hey shiva", "hello shiva", "okay shiva", "ok shiva", "shiva",
            "shiver", "shift",
            // Nik and fallback greetings
            "hi nick", "hey nick", "hello nick", "okay nick",
            "nick", "neck", "nickel", "nicole", "nickname", "stick", "picked", "hill", "kill",
            "hi", "hey", "hello", "okay", "ok", "high", "my", "nice", "night", "fine", "friend",
            "is", "here", "there", "how", "are", "you", "the", "name", "jarvis", "[unk]"
        )
    }

    // Guards everything below. Each start()/stop()/wake bumps the generation; a listening thread only
    // runs (and only reports) while its generation is current, so a quick stop → start never ends
    // up with two threads holding two mics
    private val lock = Any()
    private var model: Model? = null
    private var loading = false
    private var released = false
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var generation = 0

    /** Loads the model (first run unpacks it from the APK, a few seconds) and starts listening. */
    fun start() {
        synchronized(lock) {
            if (running || released) return
            running = true
            val gen = ++generation
            val loaded = model
            if (loaded != null) {
                listenLocked(gen, loaded)
                return
            }
            // Already unpacking: its callback starts listening for whatever generation is current then
            if (loading) return
            loading = true
        }
        StorageService.unpack(context, "model-en-in", "model",
            { m ->
                synchronized(lock) {
                    loading = false
                    if (released) {
                        m.close()
                        return@synchronized
                    }
                    model = m
                    if (running) listenLocked(generation, m)
                }
            },
            { e ->
                Log.e(TAG, "Wake word model failed to load: ${e.message}", e)
                synchronized(lock) {
                    loading = false
                    running = false
                }
            })
    }

    /** Doesn't wait: the listening thread lets go of the mic within one read (~100 ms). */
    fun stop() {
        synchronized(lock) {
            running = false
            generation++
        }
    }

    /** Stops and frees the native model (tens of MB); this detector can't be started again. */
    fun release() {
        synchronized(lock) {
            released = true
            running = false
            generation++
            // A recognizer still running holds its own native reference to the model
            model?.close()
            model = null
        }
    }

    fun isListening(): Boolean = running

    private fun isCurrent(gen: Int) = generation == gen

    /** Ends this generation; false if it already ended (stopped, or a newer start). */
    private fun finish(gen: Int): Boolean = synchronized(lock) {
        if (generation != gen) return false
        running = false
        generation++
        true
    }

    private fun listenLocked(gen: Int, model: Model) {
        val previous = thread
        thread = Thread({
            try {
                // A stopped listener may still hold the mic for one more read: let it go first
                previous?.takeIf { it.isAlive }?.join(1000)
                listen(gen, model)
            } catch (e: Throwable) {
                // Native recognizer / mic failure: must not take the whole app down from a raw thread
                Log.e(TAG, "Wake word listening failed: ${e.message}", e)
                if (finish(gen)) onError(e.message ?: e.javaClass.simpleName)
            }
        }, "wake-word").also { it.start() }
    }

    private fun listen(gen: Int, model: Model) {
        val currentName = assistantNameProvider().trim().lowercase()
        val dynamicGrammar = BASE_GRAMMAR.toMutableList()
        if (currentName.isNotBlank() && !dynamicGrammar.contains(currentName)) {
            dynamicGrammar.add(currentName)
            dynamicGrammar.add("hi $currentName")
            dynamicGrammar.add("hey $currentName")
            dynamicGrammar.add("hello $currentName")
            dynamicGrammar.add("okay $currentName")
            dynamicGrammar.add("ok $currentName")
        }
        val grammar = org.json.JSONArray(dynamicGrammar).toString()
        // Under the lock so release() can't free the model while the recognizer is taking its reference
        val recognizer = synchronized(lock) {
            if (!isCurrent(gen) || released) return
            Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
        }
        try {
            // Last few seconds of audio, handed to the conversation on a match
            val ring = ByteArray(PREROLL_BYTES)
            var ringPos = 0
            var ringFull = false
            val buf = ByteArray(CHUNK_BYTES)
            var failures = 0
            while (isCurrent(gen)) {
                val record = openMic()
                if (record != null) {
                    try {
                        record.startRecording()
                        Log.i(TAG, "Listening for wake word (\"Hi ${assistantNameProvider()}\")")
                        reportSilenced(gen, record.audioSessionId)
                        while (isCurrent(gen)) {
                            val n = record.read(buf, 0, buf.size)
                            if (n <= 0) {
                                // Dead audioserver / invalidated record: reading again would spin; reopen instead
                                Log.w(TAG, "Mic read failed ($n); reopening")
                                break
                            }
                            failures = 0
                            for (i in 0 until n) {
                                ring[ringPos] = buf[i]
                                ringPos = (ringPos + 1) % ring.size
                                if (ringPos == 0) ringFull = true
                            }
                            if (!recognizer.acceptWaveForm(buf, n)) continue
                            val text = JSONObject(recognizer.result).optString("text")
                            if (text.isBlank()) continue
                            // Never log what was said: only that something was heard
                            if (!isWakePhrase(text, assistantNameProvider())) {
                                Log.d(TAG, "Heard speech (no wake word)")
                                continue
                            }
                            if (!finish(gen)) return
                            Log.i(TAG, "Wake word matched")
                            val preroll = if (ringFull) ring.copyOfRange(ringPos, ring.size) + ring.copyOfRange(0, ringPos) else ring.copyOfRange(0, ringPos)
                            record.stop()
                            onWake(preroll)
                            return
                        }
                    } finally {
                        runCatching { record.stop() }
                        record.release()
                    }
                } else {
                    Log.e(TAG, "Mic unavailable for wake word")
                }
                if (!isCurrent(gen)) return
                if (++failures > MAX_FAILURES) {
                    if (finish(gen)) onError("Mic unavailable for wake word")
                    return
                }
                pause(gen, RETRY_BASE_MS shl (failures - 1))
            }
        } finally {
            recognizer.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't create the mic: ${e.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    /** A background-started service gets a silenced mic (all zeros): report it so the app can fix it. */
    private fun reportSilenced(gen: Int, sessionId: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isCurrent(gen)) return@postDelayed
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val silenced = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                audio?.activeRecordingConfigurations?.any { it.clientAudioSessionId == sessionId && it.isClientSilenced } == true
            if (silenced) Log.w(TAG, "Mic is silenced (started from the background, or another app is recording)")
            onListening(silenced)
        }, 1500)
    }

    /** Sleeps between mic retries, but wakes early once stopped. */
    private fun pause(gen: Int, ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (isCurrent(gen) && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    private fun isWakePhrase(text: String, assistantName: String): Boolean {
        val lower = text.lowercase().trim()
        val name = assistantName.lowercase().trim()

        // 1. Match Shiv and its Indian Vosk phonetic variants ("shiva", "shiver")
        if (name.contains("shiv") || lower.contains("shiv") || lower.contains("shiva")) {
            val shivRegex = Regex("""\b(hi|hey|hello|okay|ok|my|high)?\s*(shiva|shiv|shiver)\b""")
            if (shivRegex.containsMatchIn(lower)) return true
        }

        // 2. Match custom assistant name if configured
        if (name.isNotBlank()) {
            val customRegex = Regex("""\b(hi|hey|hello|okay|ok|my|high)?\s*${Regex.escape(name)}\b""")
            if (customRegex.containsMatchIn(lower)) return true
        }

        // 3. Fallback match for "Hi Nik" / "Nick" / "Jarvis"
        val nikRegex = Regex("""\b(hi|hey|hello|okay|my|high) (nick|neck)\b(?! (hill|kill|name)\b)""")
        if (nikRegex.containsMatchIn(lower)) return true

        if (Regex("""\b(hi|hey|hello|okay|ok)?\s*jarvis\b""").containsMatchIn(lower)) return true

        return false
    }
}
