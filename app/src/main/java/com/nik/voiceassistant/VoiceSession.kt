package com.nik.voiceassistant

import android.content.Context
import android.util.Log
import com.nik.voiceassistant.audio.AudioRecordManager
import com.nik.voiceassistant.audio.AudioTrackManager
import com.nik.voiceassistant.audio.CallAudio
import com.nik.voiceassistant.audio.WakeWordManager
import com.nik.voiceassistant.network.LiveAudioWebSocketClient
import com.nik.voiceassistant.service.PhoneAgentService
import com.nik.voiceassistant.service.VoiceAssistantService
import com.nik.voiceassistant.tools.DeviceToolExecutor
import com.nik.voiceassistant.ui.AssistantOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import com.nik.voiceassistant.data.history.ConversationHistoryManager
import com.nik.voiceassistant.data.history.ConversationSession
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A list the user picks from by tapping or saying it (show_choices tool). */
data class Choices(val question: String, val options: List<String>)

data class TranscriptItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "user", "assistant", "system", "tool"
    val text: String,
    val timeFormatted: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * One live conversation with the assistant, owned by the app process rather than the screen, so
 * "Hi Nik" can start it from the background service while the app is closed. The screen
 * (VoiceAssistantViewModel) only shows and controls it.
 *
 * A conversation ends on its own: when the user says bye (the model calls end_conversation) or
 * after IDLE_END_MS with nobody talking; the service then goes back to listening for "Hi Nik".
 */
class VoiceSession private constructor(val context: Context) {

    companion object {
        private const val TAG = "VoiceSession"
        private const val IDLE_END_MS = 30_000L
        // After end_conversation: let the goodbye finish playing first
        private const val END_AFTER_SILENT_MS = 800L

        @Volatile private var instance: VoiceSession? = null

        fun get(context: Context): VoiceSession =
            instance ?: synchronized(this) { instance ?: VoiceSession(context.applicationContext).also { instance = it } }

        // {NAME} is replaced with the assistant's name from settings (instructionsFor).
        val DEFAULT_INSTRUCTIONS = """
            You are {NAME}, an autonomous, high-intelligence AI assistant operating the user's Android phone directly.
            You can see the screen (read_screen, look_at_screen) and act on it: tap, type_text, scroll, swipe, navigate (back, home, recents, notifications, quick settings), open_app, list_apps, launch_intent, make_call, media_control, set_volume, toggle_flashlight, toggle_bluetooth, and automations.
            NEVER say you cannot access or control the phone.

            SYNCHRONOUS STEP-BY-STEP EXECUTION POLICY (CRITICAL):
            1. SPEECH POLICY (SPEAK ONLY AT START AND COMPLETION):
               - When a goal or request begins: Speak 1 short, natural sentence acknowledging your intention:
                 (e.g., "Theek hai, main developer options check karta hoon...", "Theek hai, WhatsApp open karke message bhejta hoon...").
               - INTERMEDIATE TOOL STEPS MUST BE SILENT:
                 Do NOT speak aloud while executing intermediate clicks, taps, or scrolls. Perform all intermediate screen actions SILENTLY, FAST, and SEAMLESSLY! This guarantees zero latency and no speech stutter.
               - Once the goal is 100% completed: Speak once confirming the final outcome in a clear, friendly sentence:
                 (e.g., "Aapka Developer options enable ho gaya hai. Ab batayein, aur kya help kar sakti hoon?")

            2. ZERO GUESSING & ACCURATE NAVIGATION:
               - NEVER tap random unverified elements on screen!
               - Whenever searching for ANY setting or feature in Settings:
                 ALWAYS use the Search bar in Settings (tap the 'Search settings' button, type the query, and tap the result)! This is fast, accurate, and 100% reliable.
               - You can also use direct launch_intent with Android setting actions:
                 * Developer options: action 'android.settings.APPLICATION_DEVELOPMENT_SETTINGS'
                 * About phone: action 'android.settings.DEVICE_INFO_SETTINGS'
                 * General settings: action 'android.settings.SETTINGS'

            3. BUILT-IN GOOGLE SEARCH (ALWAYS USE FOR UNFAMILIAR TASKS):
               You have built-in Google Search grounding enabled! Whenever you need to perform an action, find a hidden setting, or configure an Android feature that is not immediately visible or familiar:
               USE GOOGLE SEARCH IMMEDIATELY to retrieve the exact Android steps!
               Never guess, and NEVER ask the user: "Aapko dikh raha hai kya?" or "Aap scroll kar do".
               Take decisions yourself! Read the screen, scroll automatically, use the app's search bar, or look up the procedure on Google Search.

            4. ZERO USER BURDEN:
               - NEVER ask the user to scroll, look at the screen, or find elements.
               - You are holding the phone. Scroll down/up autonomously with 'scroll'.
               - ONLY ask the user for:
                 1. Truly irreversible financial payments/transfers.
                 2. Permanent data wipes (contacts, photos).

            5. COMPLETION & PERSISTENCE:
               - Chain tools step-by-step until the goal is 100% achieved.
               - If an intent or action fails, dynamically try the Search bar route or manual navigation.
               - Never call end_conversation while a multi-step task is still in progress.
               - Only call end_conversation when the user clearly says they are done ('bas', 'bye', 'band karo', 'that's all', 'theek hai').
               - Reply in the user's language (Hindi, Hinglish, or English).
        """.trimIndent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("voice_assistant_settings", Context.MODE_PRIVATE)

    // ---- Settings ----

    private val _selectedModel = MutableStateFlow(prefs.getString("model", "gemini-2.5-flash-native-audio-latest") ?: "gemini-2.5-flash-native-audio-latest")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _selectedVoice = MutableStateFlow(prefs.getString("voice", "Zephyr") ?: "Zephyr")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _isSearchEnabled = MutableStateFlow(prefs.getBoolean("search_enabled", true))
    val isSearchEnabled: StateFlow<Boolean> = _isSearchEnabled.asStateFlow()

    // What the assistant calls itself (settings); shown in the UI and used in the prompt
    private val _assistantName = MutableStateFlow(prefs.getString("assistant_name", "Shiv")?.ifBlank { "Shiv" } ?: "Shiv")
    val assistantName: StateFlow<String> = _assistantName.asStateFlow()

    fun setAssistantName(name: String) {
        val n = name.trim().take(20).ifBlank { "Shiv" }
        _assistantName.value = n
        prefs.edit().putString("assistant_name", n).apply()
    }

    // Auto sleep: end the conversation after a while idle. Off = keep listening until the user stops it.
    private val _autoSleep = MutableStateFlow(prefs.getBoolean("auto_sleep", true))
    val autoSleep: StateFlow<Boolean> = _autoSleep.asStateFlow()

    fun setAutoSleep(enabled: Boolean) {
        _autoSleep.value = enabled
        prefs.edit().putBoolean("auto_sleep", enabled).apply()
    }

    // Saved copies of earlier shipped defaults move to the current one (Cloud Run): the old Python
    // server (voice.ai.nik6348.in) and the Vercel default (exact ai.nik6348.in/ws/live). A custom URL stays.
    private val _serverUrl = MutableStateFlow(
        prefs.getString("server_url", null)
            ?.takeUnless { it.contains("voice.ai.nik6348.in") || it.trimEnd('/') == "wss://ai.nik6348.in/ws/live" }
            ?: BuildConfig.DEFAULT_WS_URL
    )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    // Entered in Settings and kept only on the phone (nothing is built into the APK)
    private val _apiKey = MutableStateFlow(prefs.getString("api_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    // "Hi Nik" listening in the background between conversations. Off by default: it keeps the mic
    // open all the time; the assistant gesture (AssistActivity) opens it only when called
    private val _isWakeWordEnabled = MutableStateFlow(prefs.getBoolean("wake_word", false))
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    // ---- Live state ----

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _aiAmplitude = MutableStateFlow(0f)
    val aiAmplitude: StateFlow<Float> = _aiAmplitude.asStateFlow()

    private val _transcripts = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<TranscriptItem>> = _transcripts.asStateFlow()

    private val _historySessions = MutableStateFlow<List<ConversationSession>>(ConversationHistoryManager.getAllSessions(context))
    val historySessions: StateFlow<List<ConversationSession>> = _historySessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _currentSessionTitle = MutableStateFlow("New Chat")
    val currentSessionTitle: StateFlow<String> = _currentSessionTitle.asStateFlow()

    @Volatile private var currentSession: ConversationSession? = null

    /** Called when a conversation ends by itself (bye or idle), so the service can listen for "Hi Nik" again. */
    @Volatile var onConversationEnded: (() -> Unit)? = null

    // Last sign of a conversation going on (speech either way, a tool call); for the idle end
    @Volatile private var lastActivity = 0L
    @Volatile private var taskInProgressUntil = 0L
    @Volatile private var endRequested = false
    @Volatile private var reconnecting = false
    // Typed while no conversation was running: sent once the connection is up
    @Volatile private var pendingText: String? = null
    private var watchdog: Job? = null

    private fun touch() { lastActivity = System.currentTimeMillis() }

    fun markTaskActivity(durationMs: Long = 45_000L) {
        taskInProgressUntil = System.currentTimeMillis() + durationMs
        touch()
    }

    private val toolExecutor = DeviceToolExecutor(
        context,
        onEndConversation = { endRequested = true },
        describeImage = ::describeImage,
        onShowChoices = { question, options ->
            _choices.value = Choices(question, options)
            choicesShownAt = System.currentTimeMillis()
        },
        takeConfirmation = ::takeConfirmation
    )

    // Yes-after-asking, for buttons that send / pay / delete (see DeviceToolExecutor.NEEDS_YES)
    @Volatile private var choicesShownAt = 0L
    @Volatile private var yesAt = 0L
    private val YES = Regex("""(?i)\b(haan|haa|han|ha|yes|yeah|yep|ok|okay|sure|theek|bhej|send|kar do|go ahead)\b|हाँ|हां|भेज|ठीक""")
    private val NO = Regex("""(?i)\b(nahi|nahin|nai|no|mat|ruko|cancel|badal)\w*|नहीं|मत|रुको""")

    private fun noteAnswer(text: String) {
        if (choicesShownAt > 0 && YES.containsMatchIn(text) && !NO.containsMatchIn(text)) yesAt = System.currentTimeMillis()
    }

    /** True once if the user said yes after the last list was shown, within 2 minutes. */
    private fun takeConfirmation(): Boolean {
        val ok = choicesShownAt > 0 && yesAt > choicesShownAt && System.currentTimeMillis() - yesAt < 120_000
        if (ok) yesAt = 0L
        return ok
    }

    // The open list to pick from, if any; cleared by a pick, by the user speaking, or at the end
    private val _choices = MutableStateFlow<Choices?>(null)
    val choices: StateFlow<Choices?> = _choices.asStateFlow()

    /** A tap on one of the options: sent as if the user had said it. */
    fun pickChoice(option: String) {
        _choices.value = null
        noteAnswer(option)
        sendText(option)
    }

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    /**
     * What a screenshot shows, from the gateway's /v1/chat/completions (its Gemini web backend, so
     * no Gemini API key quota is used). Blocking; null on any failure. ~4 s.
     */
    private fun describeImage(jpeg: ByteArray, prompt: String): String? {
        val base = _serverUrl.value.trim().substringBefore("?")
            .replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            .substringBefore("/ws/").substringBefore("/v1/")
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("file_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .put("file_name", "screen.jpg")
            .put("file_mime", "image/jpeg")
            .put("stream", false)
        val request = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer ${_apiKey.value}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Screen description failed: HTTP ${response.code}")
                    return null
                }
                JSONObject(response.body?.string().orEmpty()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screen description failed: ${e.message}")
            null
        }
    }

    val audioTrackManager = AudioTrackManager(
        onPlaybackAmplitudeChanged = { amp ->
            _aiAmplitude.value = amp
            if (amp > 0.02f) touch()
        },
        // The server learns when the reply is audible (it uses this for barge-in with barge_in=words)
        onPlaybackStateChanged = { playing -> sendPlayback(playing) }
    )

    private fun sendPlayback(playing: Boolean) {
        webSocketClient.sendJson("""{"type":"playback","playing":$playing}""")
    }

    val webSocketClient: LiveAudioWebSocketClient = LiveAudioWebSocketClient(
        audioTrackManager = audioTrackManager,
        toolExecutor = toolExecutor,
        listener = object : LiveAudioWebSocketClient.Callback {
            override fun onConnected() {
                val wasReconnecting = reconnecting
                reconnecting = false
                _connectionStatus.value = ConnectionStatus.CONNECTED
                touch()
                pendingText?.let { sendTextNow(it) }
                pendingText = null
                // A new connection starts with the server thinking nothing is playing
                if (audioTrackManager.isAudible()) sendPlayback(true)
                if (wasReconnecting) {
                    addTranscript("system", "Reconnected")
                    return
                }
                val searchStatus = if (_isSearchEnabled.value) "Web Search ON" else "Instant Low-Latency"
                addTranscript("system", "Connected (${_selectedModel.value.substringAfter("gemini-")}, Voice: ${_selectedVoice.value}, $searchStatus)")
            }

            override fun onReconnecting(attempt: Int, reason: String) {
                reconnecting = true
                _connectionStatus.value = ConnectionStatus.CONNECTING
                if (attempt == 1) addTranscript("system", "Connection dropped; reconnecting…")
            }

            override fun onDisconnected(reason: String) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                addTranscript("system", "Disconnected ($reason)")
            }

            override fun onError(error: Throwable) {
                _connectionStatus.value = ConnectionStatus.ERROR
                addTranscript("system", "Network Error: ${error.localizedMessage ?: error.message}")
                // Give the mic back to "Hi Nik"
                scope.launch { endConversation() }
            }

            override fun onUserTranscript(text: String) {
                touch()
                markTaskActivity()
                noteAnswer(text)
                _choices.value = null // answered by voice
                addTranscript("user", text)

                if (WakeWordManager.isWakeWord(text, _assistantName.value)) {
                    WakeWordManager.playWakeChime()
                }

                if (handleAutomationShortcutOrRecord(text)) {
                    return
                }
            }

            override fun onUserSpeaking() = touch()

            override fun onModelTranscript(text: String) {
                touch()
                addTranscript("assistant", text)

                // If this task was being auto-recorded, finalize and save!
                if (com.nik.voiceassistant.data.automation.AutomationManager.isRecording()) {
                    val saved = com.nik.voiceassistant.data.automation.AutomationManager.finishRecording(context, success = true)
                    if (saved != null) {
                        addTranscript("system", "⚡ Automation '${saved.title}' saved! Next time it will execute directly.")
                    }
                }
            }

            override fun onResumeHandle(handle: String?) {
                if (handle == null) return
                currentSession?.let { sess ->
                    sess.resumeHandle = handle
                    sess.resumeAt = System.currentTimeMillis()
                    ConversationHistoryManager.saveSession(context, sess)
                }
            }

            override fun onToolExecuted(toolName: String, args: Map<String, Any?>, resultSummary: String) {
                touch()
                markTaskActivity()
                val shownArgs = args.mapValues { (k, v) -> if (k == "pin") "***" else v }
                addTranscript("tool", "Executed $toolName $shownArgs: $resultSummary")

                // Dynamically record every action step executed by AI
                if (com.nik.voiceassistant.data.automation.AutomationManager.isRecording()) {
                    com.nik.voiceassistant.data.automation.AutomationManager.recordStep(toolName, args, resultSummary)
                }
            }
        }
    )

    private val callAudio = CallAudio(context)

    // Mic level (RMS 0..1) that counts as the user talking over the reply, after echo cancelling
    private val BARGE_IN_LEVEL = 0.05
    @Volatile private var bargeInOpenUntil = 0L

    private val audioManager = context.getSystemService(android.media.AudioManager::class.java)

    /** Music / video playing on the phone (our own reply is call audio, so it doesn't count). */
    private fun otherAudioPlaying(): Boolean = audioManager?.isMusicActive == true

    private fun rms(chunk: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < chunk.size) {
            val s = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return Math.sqrt(sum / maxOf(1, chunk.size / 2)) / 32768.0
    }

    private val audioRecordManager = AudioRecordManager(
        onAudioChunkReady = { chunk ->
            // Strict listening while the reply plays, a tool runs, or other audio (music, a video) plays:
            // the echo canceller (CallAudio) strips the reply, and what is left of it, room noise and the
            // phone's own sound are quieter than the user speaking close to it. So only clearly loud sound
            // goes through then (and stays open a moment so words aren't chopped); the user can still cut in.
            val now = System.currentTimeMillis()
            val strict = audioTrackManager.isAudible() || webSocketClient.isToolRunning() || otherAudioPlaying()
            if (strict && now >= bargeInOpenUntil && rms(chunk) > BARGE_IN_LEVEL) bargeInOpenUntil = now + 800
            val pass = !strict || now < bargeInOpenUntil
            webSocketClient.sendAudioChunk(if (pass) chunk else ByteArray(chunk.size))
        },
        onAmplitudeChanged = { amp -> _micAmplitude.value = amp }
    )

    // Nik's own screen is showing; the overlay appears over other apps only
    private val appVisible = MutableStateFlow(false)

    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    init {
        audioTrackManager.startPlaybackWorker(scope)
        AssistantOverlay(this).bind(scope, appVisible)

        // Restore last active session if valid
        val lastId = prefs.getString("active_session_id", null)
        if (lastId != null) {
            val sess = ConversationHistoryManager.getSession(context, lastId)
            if (sess != null && sess.messages.isNotEmpty()) {
                currentSession = sess
                _currentSessionId.value = sess.id
                _currentSessionTitle.value = sess.title
                _transcripts.value = sess.messages.toList()
            }
        }
    }

    val isActive: Boolean
        get() = _isRecording.value

    private fun buildConnectionUrl(): String {
        val raw = _serverUrl.value.trim()
        val base = if (raw.contains("?")) raw.substringBefore("?") else raw
        // setup=true: tools and instructions go in the first WebSocket message (LiveAudioWebSocketClient)
        // transcribe: the speech-to-text model writes the user's words (better Hindi than the voice model)
        return "$base?model=${_selectedModel.value}&voice=${_selectedVoice.value}&search=${_isSearchEnabled.value}" +
            "&setup=true&transcribe=true&transcribe_language=hi-IN,en-IN"
    }

    /**
     * Starts a conversation. preroll: mic audio from just before (the "Hi Nik, …" the wake word
     * detector heard), sent first so a request spoken in the same breath isn't lost.
     */
    fun start(preroll: ByteArray? = null) {
        scope.launch {
            if (_isRecording.value) return@launch
            if (_apiKey.value.isBlank()) {
                _connectionStatus.value = ConnectionStatus.ERROR
                addTranscript("system", "Add your API key in Settings (⚙) first, then tap the mic.")
                onConversationEnded?.invoke()
                return@launch
            }
            Log.i(TAG, "Conversation starting${if (preroll != null) " (wake word, ${preroll.size} bytes preroll)" else ""}")
            _connectionStatus.value = ConnectionStatus.CONNECTING
            endRequested = false
            touch()
            // The service keeps the mic and the process alive while the screen is off or the app is closed
            VoiceAssistantService.startConversation(context)
            callAudio.enter()
            preroll?.let { webSocketClient.queueAudio(it) }
            val resumeHandleToUse = currentSessionResumeHandle()
            webSocketClient.connect(buildConnectionUrl(), _apiKey.value, ::instructionsFor, resumeHandleToUse)
            audioRecordManager.startRecording(scope)
            _isRecording.value = true
            startWatchdog()
        }
    }

    /** Stops the conversation (mic button, notification): the service goes back to waiting for "Hi Nik". */
    fun stop() {
        scope.launch {
            if (!_isRecording.value) return@launch
            shutDown()
            onConversationEnded?.invoke()
        }
    }

    private fun shutDown() {
        watchdog?.cancel()
        watchdog = null
        audioRecordManager.stopRecording()
        _isRecording.value = false
        _micAmplitude.value = 0f
        audioTrackManager.stopAndFlush()
        webSocketClient.disconnect()
        callAudio.exit()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _choices.value = null
    }

    /** The conversation is over (bye or idle): stop and let the service listen for "Hi Nik" again. */
    private fun endConversation() {
        if (!_isRecording.value) return
        Log.i(TAG, "Conversation ended")
        shutDown()
        addTranscript("system", if (_isWakeWordEnabled.value) "Sleeping — say \"Hi ${_assistantName.value}\" to wake me" else "Conversation ended")
        onConversationEnded?.invoke()
    }

    /**
     * Checks if the user command matches an existing saved automation recipe, or is an explicit recording command.
     * Returns true if handled directly without sending to Gemini.
     */
    private fun handleAutomationShortcutOrRecord(prompt: String): Boolean {
        val clean = prompt.trim().lowercase()

        // Fast voice command: "new chat", "start new chat", "naya chat", "naya session", "naya chat shuru karo"
        val newChatPhrases = listOf(
            "new chat", "start new chat", "start a new chat", "naya chat", "naya session",
            "naya chat shuru karo", "new chat shuru karo", "clear chat"
        )
        if (newChatPhrases.any { clean == it || clean.startsWith("$it ") }) {
            startNewChat()
            addTranscript("assistant", "Naya chat shuru ho gaya hai. Batayein, kya karna hai?")
            return true
        }

        // 1. Check for explicit record instruction: e.g. "record task", "save task", "automation banao", "task save karo"
        val recordPrefixes = listOf(
            "record task", "save task", "record automation", "save automation",
            "automation banao", "automation save karo", "task save karo", "naya task banao"
        )
        for (prefix in recordPrefixes) {
            if (clean.startsWith(prefix)) {
                val actualInstruction = prompt.substring(prefix.length).trim().removePrefix(":").removePrefix("-").trim()
                if (actualInstruction.isNotBlank()) {
                    com.nik.voiceassistant.data.automation.AutomationManager.startRecording(actualInstruction)
                    addTranscript("system", "⏺️ Recording automation: '$actualInstruction'. AI will perform and save it.")
                    return false // Let it continue to Gemini so AI performs the actual task!
                }
            }
        }

        // 2. FAST SHORTCUT: If it matches any saved automation, execute directly!
        if (!com.nik.voiceassistant.data.automation.AutomationManager.isRecording()) {
            val matchedTask = com.nik.voiceassistant.data.automation.AutomationManager.findMatchingTask(context, prompt)
            if (matchedTask != null && matchedTask.steps.isNotEmpty()) {
                addTranscript("assistant", "Running '${matchedTask.title}'...")
                scope.launch {
                    com.nik.voiceassistant.data.automation.AutomationManager.executeTask(
                        context,
                        matchedTask,
                        toolExecutor
                    ) { stepIdx, total, desc ->
                        addTranscript("system", "[$stepIdx/$total] $desc")
                    }
                    addTranscript("assistant", "Done! '${matchedTask.title}' complete.")
                }
                return true
            }
        }

        return false
    }

    /** A typed request (or a suggestion chip): sent like speech, starting a conversation if needed. */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        addTranscript("user", t)
        noteAnswer(t)
        markTaskActivity()

        if (handleAutomationShortcutOrRecord(t)) {
            return
        }

        if (_isRecording.value && _connectionStatus.value == ConnectionStatus.CONNECTED) sendTextNow(t)
        else {
            pendingText = t
            start()
        }
    }

    private fun sendTextNow(text: String) {
        webSocketClient.sendJson(JSONObject().put("type", "text").put("text", text).toString())
    }

    fun toggle() {
        if (_isRecording.value) stop() else start()
    }

    /** Ends the conversation after a goodbye, or when nobody has said anything for a while. */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            var silentSince = 0L
            while (isActive) {
                delay(250)
                val now = System.currentTimeMillis()
                val audible = audioTrackManager.isAudible()
                silentSince = if (audible) 0L else if (silentSince == 0L) now else silentSince
                val quietFor = if (silentSince == 0L) 0L else now - silentSince
                val isBusy = audible || webSocketClient.isToolRunning() || now < taskInProgressUntil
                when {
                    // "bye" always ends, even with auto-sleep off
                    endRequested && quietFor >= END_AFTER_SILENT_MS -> { endConversation(); return@launch }
                    // Idle auto-sleep only when the toggle is on and no goal/task is currently executing
                    _autoSleep.value && !isBusy && now - lastActivity > IDLE_END_MS -> {
                        Log.i(TAG, "No one has talked and no task is active for ${IDLE_END_MS / 1000} s")
                        endConversation()
                        return@launch
                    }
                }
            }
        }
    }

    /** Stop button: the mic is muted while the reply plays, so this is how to cut it short. */
    fun interrupt() {
        webSocketClient.interrupt()
        audioTrackManager.stopAndFlush()
    }

    fun clearTranscripts() {
        startNewChat()
    }

    fun startNewChat() {
        shutDown()
        webSocketClient.clearResumeHandle()
        prefs.edit().remove("resume_handle").remove("resume_at").remove("active_session_id").apply()
        _transcripts.value = emptyList()
        currentSession = null
        _currentSessionId.value = null
        _currentSessionTitle.value = "New Chat"
        _historySessions.value = ConversationHistoryManager.getAllSessions(context)
        addTranscript("system", "New conversation started")
    }

    fun resumeChat(sessionId: String) {
        val session = ConversationHistoryManager.getSession(context, sessionId) ?: return
        shutDown()
        webSocketClient.clearResumeHandle()
        prefs.edit().putString("active_session_id", sessionId).apply()

        currentSession = session
        _currentSessionId.value = session.id
        _currentSessionTitle.value = session.title
        _transcripts.value = session.messages.toList()
        _historySessions.value = ConversationHistoryManager.getAllSessions(context)
        addTranscript("system", "Resumed: ${session.title}")
    }

    fun deleteChat(sessionId: String) {
        ConversationHistoryManager.deleteSession(context, sessionId)
        if (currentSession?.id == sessionId) {
            startNewChat()
        } else {
            _historySessions.value = ConversationHistoryManager.getAllSessions(context)
        }
    }

    fun getToolExecutor(): DeviceToolExecutor = toolExecutor

    /** The current conversation's handle while Gemini still keeps it (~2 h; we use it for 100 min). */
    private fun currentSessionResumeHandle(): String? {
        val session = currentSession ?: return null
        val at = session.resumeAt
        if (System.currentTimeMillis() - at > 100 * 60_000L) return null
        return session.resumeHandle
    }

    /**
     * Resuming: Gemini still has the conversation. Fresh: add the last lines said (from THIS active
     * transcript) so a new session still knows what was going on.
     */
    private fun instructionsFor(resuming: Boolean): String {
        val base = DEFAULT_INSTRUCTIONS.replace("{NAME}", _assistantName.value) + "\n\n" + lockPolicy()
        if (resuming) return base
        val recent = _transcripts.value.filter { it.sender == "user" || it.sender == "assistant" }.takeLast(10)
        if (recent.isEmpty()) return base
        val lines = recent.joinToString("\n") { (if (it.sender == "user") "User: " else "You: ") + it.text.take(300) }
        return "$base\n\nEarlier in this conversation (continue from here; don't repeat it back):\n$lines"
    }

    /** How to handle a locked phone, from the user's settings (never includes the PIN itself). */
    private fun lockPolicy(): String {
        val hasPin = SecurePrefs.hasPin(context)
        val auto = SecurePrefs.isAutoUnlockEnabled(context)
        return "Locked phone: if the screen is locked and you need to act on it, " + when {
            hasPin && auto -> "call unlock_phone (it uses the saved PIN on device) right away without asking, and then proceed with the requested task."
            hasPin && !auto -> "do NOT unlock the phone, and do NOT call unlock_phone. Tell the user clearly: 'Phone locked hai, kripya pehle phone unlock karein.' (Auto-unlock is OFF in Settings for security). Wait for the user to unlock the phone."
            else -> "do NOT unlock the phone. Tell the user clearly: 'Phone locked hai, kripya pehle phone unlock karein.' (No PIN is saved in Settings)."
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _isWakeWordEnabled.value = enabled
        prefs.edit().putBoolean("wake_word", enabled).apply()
        if (enabled) VoiceAssistantService.startListening(context) else if (!_isRecording.value) VoiceAssistantService.stop(context)
    }

    fun saveSettings(model: String, voice: String, searchEnabled: Boolean, url: String, key: String) {
        val wasActive = _isRecording.value
        if (wasActive) shutDown()

        _selectedModel.value = model
        _selectedVoice.value = voice
        _isSearchEnabled.value = searchEnabled
        _serverUrl.value = url.trim()
        _apiKey.value = key.trim()

        prefs.edit()
            .putString("model", model)
            .putString("voice", voice)
            .putBoolean("search_enabled", searchEnabled)
            .putString("server_url", url.trim())
            .putString("api_key", key.trim())
            .apply()

        addTranscript("system", "Settings updated: $model ($voice), Search: $searchEnabled")
        if (wasActive) start()
    }

    /** Screen control (accessibility service) is on. */
    fun isAgentEnabled(): Boolean = PhoneAgentService.isEnabled(context)

    private fun addTranscript(sender: String, text: String) {
        if (text.isBlank()) return
        scope.launch {
            val list = _transcripts.value.toMutableList()
            val last = list.lastOrNull()
            val item: TranscriptItem
            if (last != null && last.sender == sender && (sender == "assistant" || sender == "user")) {
                val needsSpace = !last.text.endsWith(" ") && !text.startsWith(" ") && !text.matches(Regex("^[.,!?:;].*"))
                val newText = if (needsSpace) last.text + " " + text else last.text + text
                item = last.copy(text = newText)
                list[list.lastIndex] = item
            } else {
                item = TranscriptItem(sender = sender, text = text)
                list.add(item)
            }
            _transcripts.value = list.takeLast(300)

            // Save to persistent conversation history session
            if (sender == "user" || sender == "assistant" || sender == "tool") {
                if (currentSession == null) {
                    val newSession = ConversationHistoryManager.createSession(context)
                    currentSession = newSession
                    _currentSessionId.value = newSession.id
                    _currentSessionTitle.value = newSession.title
                    prefs.edit().putString("active_session_id", newSession.id).apply()
                }
                currentSession?.let { sess ->
                    val sessList = sess.messages
                    val idx = sessList.indexOfFirst { it.id == item.id }
                    if (idx >= 0) {
                        sessList[idx] = item
                    } else {
                        sessList.add(item)
                    }
                    ConversationHistoryManager.saveSession(context, sess)
                    _currentSessionTitle.value = sess.title
                    _historySessions.value = ConversationHistoryManager.getAllSessions(context)
                }
            }
        }
    }
}
