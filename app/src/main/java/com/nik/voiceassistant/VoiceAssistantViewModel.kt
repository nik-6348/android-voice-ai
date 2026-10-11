package com.nik.voiceassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun saveSettings(model: String, voice: String, searchEnabled: Boolean, url: String, key: String) =
        session.saveSettings(model, voice, searchEnabled, url, key)
}
