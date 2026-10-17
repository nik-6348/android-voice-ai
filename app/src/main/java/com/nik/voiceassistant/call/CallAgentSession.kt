package com.nik.voiceassistant.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.nik.voiceassistant.SecurePrefs
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.audio.AudioRecordManager
import com.nik.voiceassistant.audio.AudioTrackManager
import com.nik.voiceassistant.audio.CallAudio
import com.nik.voiceassistant.data.macro.MacroStore
import com.nik.voiceassistant.network.GeminiLiveClient
import com.nik.voiceassistant.network.LiveClient
import com.nik.voiceassistant.security.Redactor
import com.nik.voiceassistant.service.PhoneAgentService
import com.nik.voiceassistant.service.VoiceInCallService
import com.nik.voiceassistant.tools.CallPlacer
import com.nik.voiceassistant.tools.ToolCallRunner
import com.nik.voiceassistant.tools.ToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an AI-driven cellular phone call session (answering or placing a call, and speaking
 * on it via acoustic coupling over speakerphone).
 *
 * Mutual exclusion: Only one of VoiceSession or CallAgentSession can drive mic/audio at a time.
 * Tool surface: STRICTLY limited to `end_call` and `note_for_user`. No screen control or device actions.
 */
class CallAgentSession(
    private val context: Context,
    private val assistantName: () -> String,
    private val onNote: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "CallAgentSession"
        private const val DEFAULT_MAX_DURATION_MS = 5 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
    }

    enum class Status {
        IDLE,
        DIALING_OR_CONNECTING,
        TALKING,
        ENDED
    }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _isActive = AtomicBoolean(false)
    val isActive: Boolean
        get() = _isActive.get()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionScope: CoroutineScope? = null
    private var timeoutJob: Job? = null

    // Audio components dedicated to this call session
    private var callAudio: CallAudio? = null
    private var audioRecordManager: AudioRecordManager? = null
    private var audioTrackManager: AudioTrackManager? = null
    private var liveClient: GeminiLiveClient? = null

    // Telephony state watcher
    private var telephonyCallback: Any? = null
    private var callConnectedDeferred: CompletableDeferred<Boolean>? = null
    private var callWasActive = false
    private var activeMacroId: String? = null
    @Volatile private var activeGoal: String = ""
    @Volatile private var shouldDiscloseAi: Boolean = true

    /**
     * Minimal tool executor for live call: only end_call and note_for_user.
     */
    private val callToolExecutor = object : ToolExecutor {
        override fun execute(toolName: String, arguments: Map<String, Any?>?, trusted: Boolean): Map<String, Any?> {
            Log.i(TAG, "Call tool executed: $toolName with args: $arguments")
            return when (toolName) {
                "end_call" -> {
                    val reason = arguments?.get("reason")?.toString() ?: "Goal completed"
                    mainHandler.post {
                        stop("Call ended by agent: $reason")
                    }
                    mapOf("success" to true, "ended" to true)
                }
                "note_for_user" -> {
                    val rawText = arguments?.get("text")?.toString() ?: ""
                    if (rawText.isNotBlank()) {
                        val redacted = Redactor.forStorage(rawText)
                        mainHandler.post {
                            onNote(redacted)
                        }
                    }
                    mapOf("success" to true, "saved" to true)
                }
                else -> mapOf("error" to "Tool $toolName is not available during a phone call.")
            }
        }
    }

    private val callToolDeclarations: List<Map<String, Any>> = listOf(
        mapOf(
            "name" to "end_call",
            "description" to "Hangs up the phone call and ends this conversation. Call this when the goal is achieved, the other party wants to hang up, or the call has concluded.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "reason" to mapOf(
                        "type" to "string",
                        "description" to "Short reason why the call is ending, e.g. 'Appointment confirmed'"
                    )
                )
            )
        ),
        mapOf(
            "name" to "note_for_user",
            "description" to "Leaves a one-line summary note for your user to read after the call. Call this near the end of the call with the key outcome.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf(
                        "type" to "string",
                        "description" to "Short, clear one-sentence summary of the call outcome for the user"
                    )
                ),
                "required" to listOf("text")
            )
        )
    )

    private val liveCallback = object : LiveClient.Callback {
        override fun onConnected() {
            Log.w(TAG, "Connected to Gemini Live")
            _status.value = Status.TALKING
            enableSpeakerphone()
            // KICK OFF the AI's first spoken sentence!
            val kickoff = if (shouldDiscloseAi) {
                "The phone call is answered and connected now. Speak immediately! Introduce yourself: 'Namaste, main ${assistantName()} hoon, AI assistant', state your purpose on this call (goal: $activeGoal), and ask how you can help."
            } else {
                "The phone call is answered and connected now. Speak immediately! Greet the caller politely, state your purpose on this call (goal: $activeGoal), and ask how you can help."
            }
            mainHandler.postDelayed({
                Log.w(TAG, "Sending kickoff text to Gemini Live: $kickoff")
                liveClient?.sendText(kickoff)
            }, 300)
        }

        override fun onReconnecting(attempt: Int, reason: String) {
            Log.w(TAG, "Reconnecting attempt $attempt: $reason")
        }

        override fun onDisconnected(reason: String) {
            Log.w(TAG, "Gemini disconnected: $reason")
            mainHandler.post { stop("Gemini disconnected: $reason") }
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Gemini Live error: ${error.message}", error)
            mainHandler.post { stop("Gemini error: ${error.message}") }
        }

        override fun onUserTranscript(text: String) {
            Log.w(TAG, "Caller: $text")
        }

        override fun onUserSpeaking() {}

        override fun onModelTranscript(text: String) {
            Log.w(TAG, "AI Spoke: $text")
        }

        override fun onToolStart(toolName: String, statusText: String) {
            Log.w(TAG, "Tool started: $toolName ($statusText)")
        }

        override fun onToolExecuted(toolName: String, args: Map<String, Any?>, resultSummary: String) {
            Log.w(TAG, "Tool finished: $toolName -> $resultSummary")
        }

        override fun onTurnComplete() {}
        override fun onResumeHandle(handle: String?) {}
    }

    /**
     * Starts an AI call session.
     *
     * @param number phone number (required for outgoing, optional for incoming)
     * @param goal task to achieve on the call
     * @param discloseAi whether to require the AI to announce itself as AI on the first sentence
     * @param maxDurationMs max duration before automatic hang up
     * @param isIncoming true if answering an incoming call, false if placing a call
     * @param macroId optional macro id to update with run status
     * @return true if started successfully, false if refused or failed
     */
    fun start(
        number: String?,
        goal: String,
        discloseAi: Boolean = true,
        maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
        isIncoming: Boolean,
        macroId: String? = null
    ): Boolean {
        if (!_isActive.compareAndSet(false, true)) {
            Log.w(TAG, "CallAgentSession is already active")
            return false
        }

        val voiceSession = VoiceSession.get(context)
        if (voiceSession.isActive) {
            Log.w(TAG, "VoiceSession is currently active; refusing call session")
            _isActive.set(false)
            onNote("Call agent could not start: the voice assistant is already in another conversation.")
            return false
        }

        val apiKey = SecurePrefs.secret(context, SecurePrefs.GEMINI_API_KEY)
            ?: voiceSession.apiKey.value.ifBlank { null }
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "No Gemini API key available")
            _isActive.set(false)
            onNote("Call agent failed: No Gemini API key configured in Settings.")
            return false
        }

        activeMacroId = macroId
        activeGoal = goal
        shouldDiscloseAi = discloseAi
        callWasActive = false
        _status.value = Status.DIALING_OR_CONNECTING

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        sessionScope = scope

        scope.launch {
            try {
                callConnectedDeferred = CompletableDeferred()
                registerCallStateListener()

                if (isIncoming) {
                    val answered = answerCall()
                    if (!answered) {
                        stop("Could not answer call (permission or telecom error)")
                        return@launch
                    }
                    delay(1200)
                    enableSpeakerphone()
                } else {
                    val placeResult = CallPlacer.place(context, number, speaker = true)
                    if (!placeResult.success) {
                        stop("Failed to place call: ${placeResult.error}")
                        return@launch
                    }
                }

                // Wait for the call to reach OFFHOOK (connected)
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    callConnectedDeferred?.await()
                } ?: false

                if (!connected) {
                    stop("Call connection timed out")
                    return@launch
                }

                callWasActive = true
                startAudioAndLiveClient(apiKey, goal, discloseAi, isIncoming)

                if (maxDurationMs > 0) {
                    timeoutJob = scope.launch {
                        delay(maxDurationMs)
                        Log.i(TAG, "Call reached max duration of ${maxDurationMs / 1000}s")
                        stop("Max call duration reached")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in CallAgentSession: ${e.message}", e)
                stop("Call error: ${e.message}")
            }
        }

        return true
    }

    private fun startAudioAndLiveClient(
        apiKey: String,
        goal: String,
        discloseAi: Boolean,
        isIncoming: Boolean
    ) {
        val scope = sessionScope ?: return

        // 1. Audio setup
        val cAudio = CallAudio(context, onFocusLost = {
            Log.w(TAG, "Call audio focus lost notification (ignored during call)")
        })
        callAudio = cAudio
        cAudio.enter(allowInCall = true)

        val trackManager = AudioTrackManager(usage = AudioAttributes.USAGE_ASSISTANT)
        audioTrackManager = trackManager
        trackManager.initialize()
        trackManager.startPlaybackWorker(scope)

        // 2. Gemini Live client setup
        val toolRunner = ToolCallRunner(callToolExecutor, liveCallback)
        val client = GeminiLiveClient(
            audioTrackManager = trackManager,
            tools = toolRunner,
            declarations = { callToolDeclarations },
            listener = liveCallback
        )
        liveClient = client

        val voiceSession = VoiceSession.get(context)
        val selectedVoice = voiceSession.selectedVoice.value.ifBlank { "Zephyr" }
        val instructions = buildCallInstructions(assistantName(), goal, discloseAi, isIncoming)

        val config = LiveClient.Config(
            serverUrl = "",
            apiKey = apiKey,
            model = VoiceSession.DEFAULT_LIVE_MODEL,
            voice = selectedVoice,
            search = false,
            instructions = { instructions },
            resumeHandle = null
        )

        client.connect(config)

        // 3. Audio recording setup
        val recordManager = AudioRecordManager(
            onAudioChunkReady = { chunk ->
                if (_status.value == Status.TALKING) {
                    if (audioTrackManager?.isAudible() == true) {
                        // While the AI is speaking out loud, feed silence to Gemini Live so it does not interrupt itself
                        client.sendAudioChunk(ByteArray(chunk.size))
                    } else {
                        client.sendAudioChunk(chunk)
                    }
                }
            },
            onError = { err ->
                Log.e(TAG, "AudioRecordManager error: $err")
                stop("Microphone error: $err")
            }
        )
        audioRecordManager = recordManager
        recordManager.startRecording(scope)
    }

    private fun answerCall(): Boolean {
        if (VoiceInCallService.currentService != null && VoiceInCallService.activeCall != null) {
            return VoiceInCallService.answerActiveCall()
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(TelecomManager::class.java)
                tm?.acceptRingingCall()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept ringing call: ${e.message}", e)
            false
        }
    }

    private fun enableSpeakerphone() {
        Log.w(TAG, "Enabling speakerphone...")
        if (VoiceInCallService.currentService != null) {
            VoiceInCallService.setSpeakerphone(true)
        }
        val tm = context.getSystemService(TelecomManager::class.java)
        try {
            tm?.showInCallScreen(false)
        } catch (e: Exception) {
            Log.w(TAG, "showInCallScreen failed: ${e.message}")
        }
        mainHandler.postDelayed({
            PhoneAgentService.instance?.switchOn(listOf("Speaker", "Speakerphone", "स्पीकर", "Loudspeaker"))
        }, 500)
        val am = context.getSystemService(AudioManager::class.java)
        @Suppress("DEPRECATION")
        runCatching { am?.isSpeakerphoneOn = true }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speakerDevice = am?.availableCommunicationDevices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            speakerDevice?.let { am?.setCommunicationDevice(it) }
        }
    }

    private fun hangUpCall() {
        if (VoiceInCallService.currentService != null && VoiceInCallService.activeCall != null) {
            if (VoiceInCallService.hangUpActiveCall()) return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
            ) {
                val tm = context.getSystemService(TelecomManager::class.java)
                if (tm?.endCall() == true) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall failed: ${e.message}")
        }
        // Accessibility fallback
        PhoneAgentService.instance?.switchOn(listOf("End call", "End", "Hang up", "कॉल समाप्त करें"))
    }

    private fun registerCallStateListener() {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onTelephonyStateChanged(state)
            }
            tm.registerTelephonyCallback(context.mainExecutor, cb)
            telephonyCallback = cb
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onTelephonyStateChanged(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            telephonyCallback = listener
        }
        @Suppress("DEPRECATION")
        if (tm.callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            Log.w(TAG, "TelephonyManager is already in CALL_STATE_OFFHOOK")
            enableSpeakerphone()
            callConnectedDeferred?.complete(true)
        }
    }

    private fun unregisterCallStateListener() {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val cb = telephonyCallback ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
                tm.unregisterTelephonyCallback(cb)
            } else if (cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister call state listener: ${e.message}")
        }
        telephonyCallback = null
    }

    private fun onTelephonyStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.w(TAG, "Telephony state: OFFHOOK (call connected)")
                enableSpeakerphone()
                callConnectedDeferred?.complete(true)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.w(TAG, "Telephony state: IDLE")
                if (callWasActive) {
                    mainHandler.post { stop("Remote party ended the call") }
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.w(TAG, "Telephony state: RINGING")
            }
        }
    }

    fun stop(reason: String? = null) {
        if (!_isActive.compareAndSet(true, false)) return

        Log.w(TAG, "Stopping CallAgentSession: ${reason ?: "No reason"}")
        _status.value = Status.ENDED

        timeoutJob?.cancel()
        timeoutJob = null

        unregisterCallStateListener()

        // Hang up the active call if needed
        if (callWasActive) {
            hangUpCall()
        }

        // Clean up audio
        try {
            audioRecordManager?.stopRecording()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecordManager: ${e.message}")
        }
        audioRecordManager = null

        try {
            audioTrackManager?.stopAndFlush()
            audioTrackManager?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audioTrackManager: ${e.message}")
        }
        audioTrackManager = null

        try {
            callAudio?.exit()
        } catch (e: Exception) {
            Log.w(TAG, "Error exiting callAudio: ${e.message}")
        }
        callAudio = null

        try {
            liveClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting liveClient: ${e.message}")
        }
        liveClient = null

        // Update macro stats if triggered by a macro
        activeMacroId?.let { id ->
            val success = reason?.contains("Goal completed", ignoreCase = true) == true ||
                reason?.contains("ended by agent", ignoreCase = true) == true
            MacroStore.update(context, id) {
                it.copy(lastRunAt = System.currentTimeMillis(), runCount = it.runCount + 1, lastRunOk = success)
            }
            activeMacroId = null
        }

        sessionScope?.cancel()
        sessionScope = null

        reason?.let {
            onNote("📞 Call ended: $it")
        }

        _status.value = Status.IDLE
    }

    private fun buildCallInstructions(
        name: String,
        goal: String,
        discloseAi: Boolean,
        isIncoming: Boolean
    ): String {
        val direction = if (isIncoming) "answering" else "placing"
        val disclosure = if (discloseAi) {
            """
Your very first sentence, before anything else, must be: "Namaste, main $name hoon, AI assistant." Translate or adapt naturally to the conversation language, but never skip this disclosure.
""".trimIndent()
        } else ""

        return """
You are $name, an AI assistant $direction a phone call on behalf of your user.
Your goal for this call: $goal

$disclosure

Speak naturally, concisely, and listen actively. Work directly toward achieving the call goal.
If the other party asks to speak with the real person, or the conversation cannot be continued usefully, state this honestly and call end_call.
When the goal is reached, or there is nothing further to discuss, say a brief polite goodbye and call end_call.

Strict Constraints:
- You have no screen access and cannot control the device beyond talking.
- You cannot make payments, transfer money, or access sensitive apps.
- Before ending the call, call note_for_user with a concise one-sentence summary of the call outcome for your user.
- Always call end_call when finished.
""".trimIndent()
    }
}
