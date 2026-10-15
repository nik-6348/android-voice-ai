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
        // After the assistant's turn: this long with no voice and no tool before the mic reopens
        private const val MIC_REOPEN_QUIET_MS = 700L

        @Volatile private var instance: VoiceSession? = null

        fun get(context: Context): VoiceSession =
            instance ?: synchronized(this) { instance ?: VoiceSession(context.applicationContext).also { instance = it } }

        // {NAME} is replaced with the assistant's name from settings, {DEVICE} with this phone's
        // live profile (instructionsFor). No app- or phone-specific procedure lives here: the model
        // reads the screen, researches what it doesn't know, and plans for the phone it is on.
        val DEFAULT_INSTRUCTIONS = """
            You are {NAME}, an autonomous assistant that operates the user's Android phone for them. You see the screen (read_screen, look_at_screen) and act on it (tap, type_text, scroll, swipe, navigate), open apps, intents and websites, and look things up (research, Google Search). Never say you can't use the phone.
            This phone, read live: {DEVICE}. Menus, names and settings differ between phone makers and Android versions, so never assume a path: confirm it on the screen or research it for this phone.

            HOW YOU WORK ON A GOAL (think, then act; loop until done):
            1. Understand: what end state does the user want? Note it.
            2. Plan: for anything over 2 steps call update_plan with the goal and short steps. If you aren't sure of the path, call research (or use Google Search) FIRST instead of guessing.
            3. Act one step at a time, and after every action check the returned screen: did it do what you expected?
            4. Decide from what you see:
               - progress -> next step (update_plan current_step as you go);
               - the screen didn't change, the item isn't there, an error, a popup -> don't repeat the same action. Switch to the next route: look_at_screen; the app's own search field; scroll with until_text; launch_intent straight to the page; research the exact path; back / home and another route; the web version. Call update_plan status blocked with what failed and what you'll try.
               - a permission prompt or dialog -> handle it if it serves the goal.
            5. Verify the end state on the screen before saying it's done (update_plan status done).
            Never give up after one failure: try every reasonable route. Only when several different routes have failed, tell the user exactly what is blocking you and what they could do.

            SCROLLING: scroll's result says moved=true/false. moved=false means the end of that list: stop, change direction or approach. To find an item in a long list use scroll with until_text, or the app's search.

            SPEAKING (think out loud, briefly): the user can't see your reasoning, so tell them what you're thinking as you work, in short natural lines, while you keep acting (speak, then call the tool right away; never wait in silence):
               - at the start: what you understood and your plan ("Theek hai, pehle Settings kholta hoon, phir About phone mein Build number dhundta hoon");
               - at each decision point: what you see and what you'll do next ("Yahan Build number nahi dikh raha, search karke dekhta hoon");
               - when something fails: what went wrong and the new approach;
               - at the end: the verified result in one line.
              Keep each line under ~12 words, one line per decision, not per tap; don't repeat yourself or read out element numbers. Your mic is off while you work and opens for the user by itself when you finish, so finish your turn when the task is done or you need their answer. Reply in the user's language (Hindi, Hinglish or English).

            ASK FIRST only before an irreversible payment / money transfer or permanently deleting data. Never ask the user to scroll, look, or tap for you.

            ENDING: keep going until the goal is reached. Call end_conversation only when the user clearly says they're done (bye, bas, band karo, that's all); a thank-you or ok is not a goodbye.
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

    // Mic muted: the room isn't heard, so it can't cut the assistant off. With auto-mute (default)
    // the mic closes only while the assistant is working on a request (thinking, tools, speaking
    // its reply) and opens again by itself once that is finished; a tap opens it anytime.
    private val _isMicMuted = MutableStateFlow(true)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _autoMute = MutableStateFlow(prefs.getBoolean("auto_mute", true))
    val autoMute: StateFlow<Boolean> = _autoMute.asStateFlow()

    fun setAutoMute(enabled: Boolean) {
        _autoMute.value = enabled
        prefs.edit().putBoolean("auto_mute", enabled).apply()
        if (!enabled && _isRecording.value) openMic()
    }

    // The mic was opened for the user and they haven't said anything yet: a reply doesn't close it
    @Volatile private var awaitingUser = false
    // The user opened it themselves (to interrupt or add something): nothing closes it until they speak
    @Volatile private var openedByUser = false
    // The assistant finished its turn: the mic reopens once its voice and tools have gone quiet
    @Volatile private var reopenWhenDone = false
    @Volatile private var lastToolEnd = 0L

    fun toggleMicMute() = setMicMuted(!_isMicMuted.value)

    fun setMicMuted(muted: Boolean) {
        if (muted) {
            awaitingUser = false
            openedByUser = false
            _isMicMuted.value = true
        } else {
            openMic()
            openedByUser = true
        }
    }

    private fun openMic() {
        awaitingUser = true
        openedByUser = false
        reopenWhenDone = false
        bargeInOpenUntil = 0L
        _isMicMuted.value = false
    }

    /**
     * The assistant is working on a request: close the mic until it's done (auto-mute). A reply only
     * closes it after the user has spoken; a tool call (the agent acting) closes it in any case,
     * unless the user opened it themselves.
     */
    private fun autoMuteForWork(tool: Boolean) {
        reopenWhenDone = false
        if (!_autoMute.value || _isMicMuted.value || openedByUser) return
        if (tool || !awaitingUser) {
            awaitingUser = false
            _isMicMuted.value = true
        }
    }

    // Active tool status for real-time animated display
    private val _activeToolStatus = MutableStateFlow<String?>(null)
    val activeToolStatus: StateFlow<String?> = _activeToolStatus.asStateFlow()

    // The assistant's plan for the goal in progress (update_plan), shown as live progress
    private val _plan = MutableStateFlow<com.nik.voiceassistant.tools.TaskPlan?>(null)
    val plan: StateFlow<com.nik.voiceassistant.tools.TaskPlan?> = _plan.asStateFlow()

    // The assistant's reply in the current turn (cleared when the user speaks); shown over other apps
    private val _liveReply = MutableStateFlow("")
    val liveReply: StateFlow<String> = _liveReply.asStateFlow()
    @Volatile private var replyTurnOpen = false

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
            // The user has to answer: let them be heard
            openMic()
        },
        takeConfirmation = ::takeConfirmation,
        research = ::research,
        onPlan = { _plan.value = it }
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
    private fun describeImage(jpeg: ByteArray, prompt: String): String? = chat(prompt, jpeg, "Screen description")

    /**
     * An answer from the web for the model's research tool: the gateway's Gemini web backend (it
     * searches Google), then its /api/search as a fallback. Blocking; null on any failure.
     */
    private fun research(prompt: String): String? {
        chat(prompt, null, "Research")?.let { return it }
        val question = prompt.lineSequence().firstOrNull { it.trim().startsWith("Question:") }?.substringAfter("Question:")?.trim() ?: prompt
        val request = Request.Builder()
            .url("${gatewayBase()}/api/search")
            .header("Authorization", "Bearer ${_apiKey.value}")
            .post(JSONObject().put("query", question).put("maxResults", 5).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val out = JSONObject(response.body?.string().orEmpty())
                val results = out.optJSONArray("results") ?: JSONArray()
                buildString {
                    out.optString("answer").takeIf { it.isNotBlank() }?.let { appendLine(it) }
                    for (i in 0 until minOf(5, results.length())) {
                        val r = results.optJSONObject(i) ?: continue
                        appendLine("- ${r.optString("title")}: ${r.optString("snippet", r.optString("content"))}".take(300))
                    }
                }.trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Research search failed: ${e.message}")
            null
        }
    }

    /** The gateway's HTTP base from the WebSocket URL in settings. */
    private fun gatewayBase(): String = _serverUrl.value.trim().substringBefore("?")
        .replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        .substringBefore("/ws/").substringBefore("/v1/")

    /** One reply from the gateway's /v1/chat/completions, with an optional JPEG attached. */
    private fun chat(prompt: String, jpeg: ByteArray?, what: String): String? {
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("stream", false)
        if (jpeg != null) {
            body.put("file_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                .put("file_name", "screen.jpg")
                .put("file_mime", "image/jpeg")
        }
        val request = Request.Builder()
            .url("${gatewayBase()}/v1/chat/completions")
            .header("Authorization", "Bearer ${_apiKey.value}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "$what failed: HTTP ${response.code}")
                    return null
                }
                JSONObject(response.body?.string().orEmpty()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
            null
        }
    }

    val audioTrackManager = AudioTrackManager(
        onPlaybackAmplitudeChanged = { amp ->
            _aiAmplitude.value = amp
            if (amp > 0.02f) touch()
        },
        // The server learns when the reply is audible (it uses this for barge-in with barge_in=words)
        onPlaybackStateChanged = { playing ->
            sendPlayback(playing)
            if (playing) autoMuteForWork(tool = false)
        }
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
                // The user has had their say; the mic closes again once the reply starts
                awaitingUser = false
                openedByUser = false
                replyTurnOpen = false
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
                autoMuteForWork(tool = false)
                addTranscript("assistant", text)
                _liveReply.value = if (replyTurnOpen) joinSpoken(_liveReply.value, text) else text.trim()
                replyTurnOpen = true

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

            override fun onToolStart(toolName: String, statusText: String) {
                touch()
                markTaskActivity()
                autoMuteForWork(tool = true)
                _activeToolStatus.value = statusText
            }

            override fun onTurnComplete() {
                replyTurnOpen = false
                // The watchdog opens the mic once the reply has played and no tool is running
                reopenWhenDone = true
            }

            override fun onToolExecuted(toolName: String, args: Map<String, Any?>, resultSummary: String) {
                _activeToolStatus.value = null
                lastToolEnd = System.currentTimeMillis()
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
            if (_isMicMuted.value) {
                // Mic is MUTED: send silent zeroed frames so Gemini server stays alive without hearing room speech
                webSocketClient.sendAudioChunk(ByteArray(chunk.size))
                return@AudioRecordManager
            }
            val now = System.currentTimeMillis()
            val chunkRms = rms(chunk)
            val strict = audioTrackManager.isAudible() || webSocketClient.isToolRunning() || otherAudioPlaying()
            if (strict && now >= bargeInOpenUntil && chunkRms > BARGE_IN_LEVEL) bargeInOpenUntil = now + 800

            // Noise gate filter: background whispers & ambient noise (< 0.012 RMS) are filtered
            val pass = if (strict) {
                now < bargeInOpenUntil
            } else {
                chunkRms > 0.012 || now < bargeInOpenUntil
            }
            webSocketClient.sendAudioChunk(if (pass) chunk else ByteArray(chunk.size))
        },
        onAmplitudeChanged = { amp -> _micAmplitude.value = if (_isMicMuted.value) 0f else amp }
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
            // A new conversation: the user is about to speak (or has, in the preroll); no old plan
            toolExecutor.resetPlan()
            _liveReply.value = ""
            replyTurnOpen = false
            openMic()
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
        _activeToolStatus.value = null
        _isMicMuted.value = true
        awaitingUser = false
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
                // The assistant is done (turn over, voice quiet, no tool for a moment): the user's turn
                if (reopenWhenDone && _isMicMuted.value && _autoMute.value && quietFor >= MIC_REOPEN_QUIET_MS &&
                    !webSocketClient.isToolRunning() && now - lastToolEnd >= MIC_REOPEN_QUIET_MS && !endRequested
                ) openMic()
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
        val base = DEFAULT_INSTRUCTIONS.replace("{NAME}", _assistantName.value).replace("{DEVICE}", toolExecutor.deviceProfile()) +
            "\n\n" + lockPolicy()
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

    /** Streamed transcript pieces joined with a space where the words need one. */
    private fun joinSpoken(a: String, b: String): String =
        if (a.isEmpty() || a.endsWith(" ") || b.startsWith(" ") || b.matches(Regex("^[.,!?:;।].*"))) a + b else "$a $b"

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
