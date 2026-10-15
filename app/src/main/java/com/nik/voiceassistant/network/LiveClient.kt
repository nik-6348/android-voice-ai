package com.nik.voiceassistant.network

/**
 * A live voice connection to the model. Two transports implement it: GeminiLiveClient talks to the
 * Gemini Live API directly from the phone (lowest latency), LiveAudioWebSocketClient goes through
 * the gateway server. VoiceSession picks one from settings and uses only this interface.
 */
interface LiveClient {

    interface Callback {
        fun onConnected()
        fun onReconnecting(attempt: Int, reason: String)
        fun onDisconnected(reason: String)
        fun onError(error: Throwable)
        fun onUserTranscript(text: String)
        fun onUserSpeaking()
        fun onModelTranscript(text: String)
        /** The first audio of a reply arrived (for the response-latency metric). */
        fun onModelAudioStart() {}
        fun onToolStart(toolName: String, statusText: String) {}
        fun onToolExecuted(toolName: String, args: Map<String, Any?>, resultSummary: String)
        /** A tool ran, but its result couldn't reach the model (the connection dropped meanwhile). */
        fun onToolResultLost(toolName: String, resultSummary: String) {}
        /** The model finished its reply for this turn. */
        fun onTurnComplete() {}
        /** The latest handle to continue this conversation later (kept across conversations). */
        fun onResumeHandle(handle: String?)
    }

    /** Everything a connection needs; each transport uses the parts it has a use for. */
    data class Config(
        // Gateway WebSocket URL (server transport)
        val serverUrl: String,
        // Gemini API key (direct) or gateway key (server)
        val apiKey: String,
        val model: String,
        val voice: String,
        val search: Boolean,
        // (resuming) -> system instructions; a fresh session also gets the recent conversation
        val instructions: (Boolean) -> String,
        val resumeHandle: String?
    )

    /** Whether screen images can be sent to the model on this transport. */
    val supportsVision: Boolean

    fun connect(config: Config)
    fun disconnect()
    fun isConnected(): Boolean
    fun isToolRunning(): Boolean
    fun sendAudioChunk(chunk: ByteArray)
    /** Keeps audio to send as soon as the connection is up ("Hi Nik, YouTube kholo" in one breath). */
    fun queueAudio(chunk: ByteArray)
    /** Text from the user (typed, a picked option, or a note from the app). */
    fun sendText(text: String)
    /** Whether the reply is audible on the phone (the gateway uses it for barge-in). */
    fun sendPlayback(playing: Boolean) {}
    /** Stops the current reply; audio already on its way is dropped. */
    fun interrupt()
    /** A JPEG of the screen; returns whether it was sent. */
    fun sendImage(jpeg: ByteArray): Boolean = false
    fun clearResumeHandle()
}
