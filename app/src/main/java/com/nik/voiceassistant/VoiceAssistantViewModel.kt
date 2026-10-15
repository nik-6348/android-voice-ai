package com.nik.voiceassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    val availableModels = listOf(
        ModelOption(
            id = "gemini-2.5-flash-native-audio-latest",
            displayName = "Gemini 2.5 Flash Native",
            badge = "1M TPM • Fast",
            description = "Official Live Audio model. Low latency, supports real-time Google search grounding."
        ),
        ModelOption(
            id = "gemini-3.1-flash-live-preview",
            displayName = "Gemini 3 Flash Live",
            badge = "65K TPM • Preview",
            description = "Next-generation Gemini 3 Live preview audio model."
        ),
        ModelOption(
            id = "gemini-3.8-live",
            displayName = "Gemini 3.8 Live",
            badge = "65K TPM • High Quality",
            description = "High fidelity real-time conversational audio model."
        )
    )

    val availableVoices = listOf("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Aoede")

    val selectedModel: StateFlow<String> = session.selectedModel
    val selectedVoice: StateFlow<String> = session.selectedVoice
    val isSearchEnabled: StateFlow<Boolean> = session.isSearchEnabled
    val serverUrl: StateFlow<String> = session.serverUrl
    val apiKey: StateFlow<String> = session.apiKey
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

    fun saveSettings(model: String, voice: String, searchEnabled: Boolean, url: String, key: String) =
        session.saveSettings(model, voice, searchEnabled, url, key)

    // ---- History Management ----
    val historySessions: StateFlow<List<com.nik.voiceassistant.data.history.ConversationSession>> = session.historySessions
    val currentSessionId: StateFlow<String?> = session.currentSessionId
    val currentSessionTitle: StateFlow<String> = session.currentSessionTitle

    fun startNewChat() = session.startNewChat()

    fun resumeChat(sessionId: String) = session.resumeChat(sessionId)

    fun deleteChat(sessionId: String) = session.deleteChat(sessionId)

    // ---- Automations Management ----
    private val _automations = MutableStateFlow<List<com.nik.voiceassistant.data.automation.AutomationTask>>(
        com.nik.voiceassistant.data.automation.AutomationManager.getAllTasks(getApplication())
    )
    val automations: StateFlow<List<com.nik.voiceassistant.data.automation.AutomationTask>> = _automations.asStateFlow()

    fun refreshAutomations() {
        _automations.value = com.nik.voiceassistant.data.automation.AutomationManager.getAllTasks(getApplication())
    }

    fun saveAutomation(task: com.nik.voiceassistant.data.automation.AutomationTask) {
        com.nik.voiceassistant.data.automation.AutomationManager.saveTask(getApplication(), task)
        refreshAutomations()
    }

    fun deleteAutomation(id: String) {
        com.nik.voiceassistant.data.automation.AutomationManager.deleteTask(getApplication(), id)
        refreshAutomations()
    }

    fun toggleFavoriteAutomation(id: String) {
        com.nik.voiceassistant.data.automation.AutomationManager.toggleFavorite(getApplication(), id)
        refreshAutomations()
    }

    fun executeAutomation(
        task: com.nik.voiceassistant.data.automation.AutomationTask,
        onProgress: (stepIndex: Int, total: Int, desc: String) -> Unit,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            val result = com.nik.voiceassistant.data.automation.AutomationManager.executeTask(
                getApplication(),
                task,
                session.getToolExecutor(),
                onProgress
            )
            refreshAutomations()
            onComplete(result.first, result.second)
        }
    }

    fun recordAndExecuteAutomation(instruction: String) {
        val clean = instruction.trim()
        if (clean.isBlank()) return
        com.nik.voiceassistant.data.automation.AutomationManager.startRecording(clean)
        session.sendText(clean)
    }
}
