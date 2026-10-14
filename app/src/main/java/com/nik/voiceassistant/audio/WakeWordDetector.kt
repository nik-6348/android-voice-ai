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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onWake: (preroll: ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 3200 // 100 ms
        private const val PREROLL_BYTES = SAMPLE_RATE * 2 * 4 // 4 s

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

    private var model: Model? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** Loads the model (first run unpacks it from the APK, a few seconds) and starts listening. */
    fun start() {
        if (running.getAndSet(true)) return
        val loaded = model
        if (loaded != null) {
            listen(loaded)
            return
        }
        StorageService.unpack(context, "model-en-in", "model",
            { m ->
                model = m
                if (running.get()) listen(m)
            },
            { e ->
                Log.e(TAG, "Wake word model failed to load: ${e.message}", e)
                running.set(false)
            })
    }

    fun stop() {
        running.set(false)
        thread?.takeIf { it !== Thread.currentThread() }?.join(500)
        thread = null
    }

    fun isListening(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    private fun listen(model: Model) {
        thread = Thread({
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
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Mic unavailable for wake word")
                record.release()
                recognizer.close()
                running.set(false)
                return@Thread
            }
            // Last few seconds of audio, handed to the conversation on a match
            val ring = ByteArray(PREROLL_BYTES)
            var ringPos = 0
            var ringFull = false
            val buf = ByteArray(CHUNK_BYTES)
            record.startRecording()
            Log.i(TAG, "Listening for wake word (\"Hi ${assistantNameProvider()}\")")
            // A background-started service gets a silenced mic (all zeros): report it so the app can fix it
            val sessionId = record.audioSessionId
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                val silenced = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    audio?.activeRecordingConfigurations?.any { it.clientAudioSessionId == sessionId && it.isClientSilenced } == true
                if (silenced) Log.w(TAG, "Mic is silenced (started from the background)")
                if (running.get()) onListening(silenced)
            }, 1500)
            try {
                while (running.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) {
                        ring[ringPos] = buf[i]
                        ringPos = (ringPos + 1) % ring.size
                        if (ringPos == 0) ringFull = true
                    }
                    if (!recognizer.acceptWaveForm(buf, n)) continue
                    val text = JSONObject(recognizer.result).optString("text")
                    if (text.isBlank()) continue
                    Log.d(TAG, "Heard: $text")
                    if (isWakePhrase(text, assistantNameProvider())) {
                        Log.i(TAG, "Wake word matched: \"$text\"")
                        val preroll = if (ringFull) ring.copyOfRange(ringPos, ring.size) + ring.copyOfRange(0, ringPos) else ring.copyOfRange(0, ringPos)
                        running.set(false)
                        record.stop()
                        onWake(preroll)
                        break
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                recognizer.close()
            }
        }, "wake-word").also { it.start() }
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
