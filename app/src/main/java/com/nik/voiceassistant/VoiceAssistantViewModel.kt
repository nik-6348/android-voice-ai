package com.nik.voiceassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen's view of the conversation. The conversation itself lives in VoiceSession, so it
 * keeps going (and "Hi Nik" can start one) while this screen is closed.
 */
class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val session = VoiceSession.get(application)

    data class ModelOption(
        val id: String,
        val displayName: String,
        val badge: String,
        val description: String
    )

    // Live models: the recommended ones, plus whatever Google lists for this key (refreshLiveModels)
    private val knownModels = listOf(
        ModelOption(VoiceSession.DEFAULT_LIVE_MODEL, "Gemini 3.8 Live", "Recommended • Fastest", "Google's default Live model: low latency voice, tools and Google Search."),
        ModelOption("gemini-3.8-live-extended-thinking", "Gemini 3.8 Live Thinking", "Smarter • Slower", "Reasons more before answering: better for long multi-step tasks, a little more delay."),
        ModelOption("gemini-2.5-flash-native-audio-preview-12-2025", "Gemini 2.5 Flash Native Audio", "Older", "Previous native-audio model (only for keys that used 2.5 before).")
    )
    private val _availableModels = MutableStateFlow(knownModels)
    val availableModels: StateFlow<List<ModelOption>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    /** Reads the Live models this Gemini key can use from Google; returns false if that failed. */
    fun refreshLiveModels(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _modelsLoading.value = true
            val ids = session.fetchLiveModels()
            _modelsLoading.value = false
            if (ids != null) {
                val extra = ids.filter { id -> knownModels.none { it.id == id } }
                    .map { ModelOption(it, it, "From Google", "Live model available to your key.") }
                _availableModels.value = knownModels.filter { k -> ids.isEmpty() || k.id in ids } + extra
            }
            onDone(ids != null)
        }
    }

    val availableVoices = listOf("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Aoede")

    val selectedModel: StateFlow<String> = session.selectedModel
    val selectedVoice: StateFlow<String> = session.selectedVoice
    val isSearchEnabled: StateFlow<Boolean> = session.isSearchEnabled
    val serverUrl: StateFlow<String> = session.serverUrl
    val apiKey: StateFlow<String> = session.apiKey
    val hasGeminiKey: StateFlow<Boolean> = session.hasGeminiKey
    val connectionMode: StateFlow<String> = session.connectionMode
    val llmBaseUrl: StateFlow<String> = session.llmBaseUrl
    val visionFrames: StateFlow<Boolean> = session.visionFrames
    val fastActionMode: StateFlow<Boolean> = session.fastActionMode
    fun setFastActionMode(enabled: Boolean) = session.setFastActionMode(enabled)
    fun hasCredentials(): Boolean = session.hasCredentials()
    fun clearKey(name: String) = session.clearKey(name)
    val isWakeWordEnabled: StateFlow<Boolean> = session.isWakeWordEnabled
    val connectionStatus: StateFlow<ConnectionStatus> = session.connectionStatus
    val isRecording: StateFlow<Boolean> = session.isRecording
    val micAmplitude: StateFlow<Float> = session.micAmplitude
    val aiAmplitude: StateFlow<Float> = session.aiAmplitude
    val transcripts: StateFlow<List<TranscriptItem>> = session.transcripts

    // Screen control (accessibility service) is on; checked again whenever the app comes back
    private val _isAgentEnabled = MutableStateFlow(session.isAgentEnabled())
    val isAgentEnabled: StateFlow<Boolean> = _isAgentEnabled.asStateFlow()

    fun refreshAgentStatus() {
        _isAgentEnabled.value = session.isAgentEnabled()
    }

    val isMicMuted: StateFlow<Boolean> = session.isMicMuted
    fun toggleMicMute() = session.toggleMicMute()
    fun setMicMuted(muted: Boolean) = session.setMicMuted(muted)
    val autoMute: StateFlow<Boolean> = session.autoMute
    fun setAutoMute(enabled: Boolean) = session.setAutoMute(enabled)

    val activeToolStatus: StateFlow<String?> = session.activeToolStatus
    val plan: StateFlow<com.nik.voiceassistant.tools.TaskPlan?> = session.plan

    fun startSession() = session.start()

    fun stopSession() = session.stop()

    fun toggleSession() = session.toggle()

    /** Stop button: the mic is muted while the reply plays, so this is how to cut it short. */
    fun interruptAssistant() = session.interrupt()

    fun clearTranscripts() = session.clearTranscripts()

    fun sendText(text: String) = session.sendText(text)

    val choices: StateFlow<Choices?> = session.choices

    fun pickChoice(option: String) = session.pickChoice(option)

    fun setWakeWordEnabled(enabled: Boolean) = session.setWakeWordEnabled(enabled)

    val assistantName: StateFlow<String> = session.assistantName
    fun setAssistantName(name: String) = session.setAssistantName(name)

    val autoSleep: StateFlow<Boolean> = session.autoSleep
    fun setAutoSleep(enabled: Boolean) = session.setAutoSleep(enabled)

    // Device PIN for auto-unlock: saved encrypted on the phone, never sent anywhere
    fun hasPin(): Boolean = SecurePrefs.hasPin(getApplication())
    fun savePin(pin: String): Boolean = SecurePrefs.setPin(getApplication(), pin)
    fun isAutoUnlockEnabled(): Boolean = SecurePrefs.isAutoUnlockEnabled(getApplication())
    fun setAutoUnlock(enabled: Boolean) = SecurePrefs.setAutoUnlock(getApplication(), enabled)
    fun lockType(): SecurePrefs.LockType = SecurePrefs.lockType(getApplication())
    fun setLockType(type: SecurePrefs.LockType) = SecurePrefs.setLockType(getApplication(), type)
    fun hasPattern(): Boolean = SecurePrefs.hasPattern(getApplication())
    fun savePattern(cells: List<Int>): Boolean = SecurePrefs.setPattern(getApplication(), cells)

    fun saveSettings(
        model: String, voice: String, searchEnabled: Boolean, url: String, mode: String,
        geminiKey: String?, llmBaseUrl: String, llmKey: String?, vision: Boolean
    ): VoiceSession.SaveResult = session.saveSettings(model, voice, searchEnabled, url, mode, geminiKey, llmBaseUrl, llmKey, vision)

    // ---- Safety & privacy (Settings) ----
    val policy: StateFlow<com.nik.voiceassistant.security.AgentPolicy.Settings> = com.nik.voiceassistant.security.AgentPolicy.state

    fun updatePolicy(transform: (com.nik.voiceassistant.security.AgentPolicy.Settings) -> com.nik.voiceassistant.security.AgentPolicy.Settings) =
        session.updatePolicy(transform)

    /** Deletes every saved conversation (History). */
    fun clearAllHistory() = session.clearAllHistory()

    // ---- History Management ----
    val historySessions: StateFlow<List<com.nik.voiceassistant.data.history.ConversationSession>> = session.historySessions
    val currentSessionId: StateFlow<String?> = session.currentSessionId
    val currentSessionTitle: StateFlow<String> = session.currentSessionTitle

    fun startNewChat() = session.startNewChat()

    fun resumeChat(sessionId: String) = session.resumeChat(sessionId)

    fun deleteChat(sessionId: String) = session.deleteChat(sessionId)

    // ---- Automations (macros) ----
    private val _automations = MutableStateFlow<List<com.nik.voiceassistant.data.macro.Macro>>(emptyList())
    val automations: StateFlow<List<com.nik.voiceassistant.data.macro.Macro>> = _automations.asStateFlow()

    init {
        refreshAutomations()
    }

    fun refreshAutomations() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val all = com.nik.voiceassistant.data.macro.MacroStore.getAll(getApplication())
            withContext(kotlinx.coroutines.Dispatchers.Main) { _automations.value = all }
        }
    }

    fun saveAutomation(macro: com.nik.voiceassistant.data.macro.Macro) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.nik.voiceassistant.data.macro.MacroStore.save(getApplication(), macro)
            if (macro.enabled) com.nik.voiceassistant.service.ScheduledTriggerMonitor.scheduleOne(getApplication(), macro)
            refreshAutomations()
        }
    }

    fun deleteAutomation(id: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.nik.voiceassistant.data.macro.MacroStore.delete(getApplication(), id)
            com.nik.voiceassistant.service.ScheduledTriggerMonitor.cancel(getApplication(), id)
            refreshAutomations()
        }
    }

    fun toggleFavoriteAutomation(id: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.nik.voiceassistant.data.macro.MacroStore.update(getApplication(), id) { it.copy(isFavorite = !it.isFavorite) }
            refreshAutomations()
        }
    }

    fun setAutomationEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var updated: com.nik.voiceassistant.data.macro.Macro? = null
            com.nik.voiceassistant.data.macro.MacroStore.update(getApplication(), id) { it.copy(enabled = enabled).also { m -> updated = m } }
            if (enabled) updated?.let { com.nik.voiceassistant.service.ScheduledTriggerMonitor.scheduleOne(getApplication(), it) }
            else com.nik.voiceassistant.service.ScheduledTriggerMonitor.cancel(getApplication(), id)
            refreshAutomations()
        }
    }

    /** Replays a macro's recorded steps. On failure, the caller should offer to re-record it live. */
    fun runAutomation(
        macro: com.nik.voiceassistant.data.macro.Macro,
        onProgress: (stepIndex: Int, total: Int, desc: String) -> Unit,
        onComplete: (result: com.nik.voiceassistant.data.macro.MacroEngine.ReplayResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = com.nik.voiceassistant.data.macro.MacroEngine.replay(getApplication(), macro, session.getToolExecutor(), onProgress)
            refreshAutomations()
            onComplete(result)
        }
    }

    /** Starts a brand-new macro: the AI performs `instruction` live now, and it's saved once the goal is done. */
    fun recordAndExecuteAutomation(instruction: String) {
        val clean = instruction.trim()
        if (clean.isBlank()) return
        com.nik.voiceassistant.data.macro.MacroRecorder.start(clean)
        session.sendText(clean)
    }
}
