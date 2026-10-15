package com.nik.voiceassistant.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nik.voiceassistant.audio.AudioTrackManager
import com.nik.voiceassistant.audio.WakeWordManager
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.tools.ToolCallResponse
import com.nik.voiceassistant.tools.ToolCallRunner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live voice through the gateway server (wss://…/ws/live): it holds the Gemini session, and adds its
 * own speech-to-text (better Hindi) and word-level barge-in. Streams 16 kHz PCM mic audio up and
 * 24 kHz PCM speaker audio down, with JSON for transcripts, interruptions and tool calls.
 * The other transport, GeminiLiveClient, talks to Gemini directly.
 */
class LiveAudioWebSocketClient(
    private val audioTrackManager: AudioTrackManager,
    private val tools: ToolCallRunner,
    // Function declarations sent in the setup message
    private val declarations: () -> List<Map<String, Any>>,
    private val listener: LiveClient.Callback
) : LiveClient {

    override val supportsVision = false

    companion object {
        private const val TAG = "LiveAudioWebSocket"
        // Tries in a row without getting a session before giving up
        private const val MAX_RECONNECTS = 8
        // Mic audio kept while (re)connecting, sent once the connection is up: ~8 s of 16 kHz PCM
        private const val MAX_QUEUED_BYTES = 16000 * 2 * 8
    }

    private val gson = Gson()

    private val reconnector = Executors.newSingleThreadScheduledExecutor()
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)

    // Reconnecting: the host closes WebSockets after a few minutes (Vercel: 300-800 s) and Gemini
    // resets sessions about every 10; a new connection with the latest resume handle continues
    // the same conversation, so the call just carries on
    private var wsUrl = ""
    private var apiKey = ""
    // (resuming) -> system instructions; a fresh session (no handle) also gets the recent conversation
    private var instructions: (Boolean) -> String = { "" }
    @Volatile private var resumeHandle: String? = null
    @Volatile private var userClosed = true
    @Volatile private var attempts = 0

    // Reply audio still in flight after a stop is dropped until then
    @Volatile private var dropAudioUntil = 0L

    // Audio from before the connection was up ("Hi Nik, YouTube kholo" said in one breath)
    private val queued = ArrayDeque<ByteArray>()
    private var queuedBytes = 0

    // The first reply audio of this turn has arrived (for the latency metric)
    @Volatile private var replyStarted = false

    override fun isToolRunning(): Boolean = tools.isRunning()

    // Pings find a dead connection (phone switched networks, host dropped it) within ~1 min;
    // 15 s was too tight through the host's proxy (a late pong dropped working calls)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Connect with ?setup=true in wsUrl: the setup message below (tools + instructions) goes first,
     * and the server opens the Gemini session with it.
     */
    override fun connect(config: LiveClient.Config) {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        val raw = config.serverUrl.trim()
        val base = if (raw.contains("?")) raw.substringBefore("?") else raw
        // setup=true: tools and instructions go in the first message; transcribe: the server's
        // speech-to-text writes the user's words (better Hindi than the voice model's)
        this.wsUrl = "$base?model=${config.model}&voice=${config.voice}&search=${config.search}" +
            "&setup=true&transcribe=true&transcribe_language=hi-IN,en-IN"
        this.apiKey = config.apiKey
        this.instructions = config.instructions
        // Continue the last conversation (Gemini keeps it for a while) instead of starting from nothing
        resumeHandle = config.resumeHandle
        dropAudioUntil = 0L
        attempts = 0
        userClosed = false
        open()
    }

    private fun open() {
        val handle = resumeHandle
        val url = if (handle != null) "$wsUrl&resume=${URLEncoder.encode(handle, "UTF-8")}" else wsUrl
        Log.i(TAG, "Connecting to WebSocket: $wsUrl${if (handle != null) " (resuming)" else ""}")
        val request = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = mapOf(
                    "type" to "setup",
                    "tools" to declarations(),
                    "instructions" to instructions(resumeHandle != null)
                )
                webSocket.send(gson.toJson(setup))
                // The server queues audio until its Gemini session is open
                // (in ~1 s frames: the server holds at most 200 frames, and mic chunks are 32 ms)
                synchronized(queued) {
                    val all = java.io.ByteArrayOutputStream(queuedBytes)
                    while (queued.isNotEmpty()) all.write(queued.removeFirst())
                    queuedBytes = 0
                    val bytes = all.toByteArray()
                    for (i in bytes.indices step 32000) webSocket.send(bytes.toByteString(i, minOf(32000, bytes.size - i)))
                }
                isConnected.set(true)
                Log.i(TAG, "WebSocket connected successfully: ${response.code}")
                if (attempts == 0) WakeWordManager.playWakeChime()
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Incoming binary frame is 24kHz 16-bit PCM Audio from Gemini Live
                if (System.currentTimeMillis() < dropAudioUntil) return
                if (!replyStarted) {
                    replyStarted = true
                    listener.onModelAudioStart()
                }
                audioTrackManager.enqueueAudioChunk(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleJsonMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@LiveAudioWebSocketClient.webSocket) return // an old connection
                isConnected.set(false)
                Log.i(TAG, "WebSocket closed: $code / $reason")
                if (userClosed) listener.onDisconnected(reason) else scheduleReconnect("closed: ${reason.ifBlank { code.toString() }}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@LiveAudioWebSocketClient.webSocket) return
                isConnected.set(false)
                Log.e(TAG, "WebSocket failure: ${t.message}, response: ${response?.code}", t)
                if (!userClosed) scheduleReconnect(t.message ?: "connection failed")
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        attempts++
        if (attempts > MAX_RECONNECTS) {
            userClosed = true
            listener.onError(RuntimeException("Connection lost ($reason); gave up after $MAX_RECONNECTS tries"))
            return
        }
        // A resume that keeps failing (e.g. an expired handle): start a fresh conversation instead
        // (it still gets the recent conversation through the instructions)
        if (attempts > 3 && resumeHandle != null) {
            resumeHandle = null
            listener.onResumeHandle(null)
        }
        val delayMs = minOf(300L shl (attempts - 1), 8000L)
        AgentTrace.log(AgentTrace.Kind.NETWORK, "Reconnecting in $delayMs ms (attempt $attempts): $reason")
        listener.onReconnecting(attempts, reason)
        reconnector.schedule({ if (!userClosed) open() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Sends raw 16kHz PCM audio chunk to the server.
     */
    override fun sendAudioChunk(chunk: ByteArray) {
        if (!isConnected.get()) {
            if (!userClosed) queueAudio(chunk) // still connecting: keep it for when we're up
            return
        }
        val byteString = chunk.toByteString(0, chunk.size)
        webSocket?.send(byteString)
    }

    /** Keeps audio to send as soon as the connection opens (the oldest is dropped past ~8 s). */
    override fun queueAudio(chunk: ByteArray) {
        synchronized(queued) {
            queued.addLast(chunk)
            queuedBytes += chunk.size
            while (queuedBytes > MAX_QUEUED_BYTES && queued.size > 1) queuedBytes -= queued.removeFirst().size
        }
    }

    /**
     * Send client JSON control or tool response.
     */
    fun sendJson(jsonString: String) {
        if (!isConnected.get()) return
        webSocket?.send(jsonString)
    }

    override fun sendText(text: String) = sendJson(com.google.gson.JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }.toString())

    override fun sendPlayback(playing: Boolean) = sendJson("""{"type":"playback","playing":$playing}""")

    /** Stops the current reply on the server; audio already on its way is dropped. */
    override fun interrupt() {
        dropAudioUntil = System.currentTimeMillis() + 1000
        sendJson("""{"type":"interrupt"}""")
    }

    override fun clearResumeHandle() {
        resumeHandle = null
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnecting WebSocket")
        userClosed = true
        isConnected.set(false)
        resumeHandle = null
        synchronized(queued) { queued.clear(); queuedBytes = 0 }
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing webSocket: ${e.message}")
        }
        webSocket = null
    }

    override fun isConnected(): Boolean = isConnected.get()

    private fun handleJsonMessage(jsonStr: String) {
        if (userClosed) {
            return
        }
        try {
            val jsonObject = JsonParser.parseString(jsonStr).asJsonObject
            val type = jsonObject.get("type")?.asString

            when (type) {
                "user_transcript" -> {
                    val text = jsonObject.get("text")?.asString.orEmpty()
                    listener.onUserTranscript(text)
                    if (WakeWordManager.isWakeWord(text)) {
                        WakeWordManager.playWakeChime()
                    }
                }

                "user_transcript_interim" -> {
                    listener.onUserSpeaking()
                }

                "model_transcript" -> {
                    val text = jsonObject.get("text")?.asString.orEmpty()
                    if (text.isNotBlank()) listener.onModelTranscript(text)
                }

                "session", "listening" -> {
                    if (type == "session") attempts = 0 // a working session: reset the retry count
                    Log.i(TAG, "Listening: transcriber=${jsonObject.get("transcriber")}")
                }

                "resume_handle" -> {
                    resumeHandle = jsonObject.get("handle")?.asString
                    listener.onResumeHandle(resumeHandle)
                }

                "go_away" -> {
                    Log.i(TAG, "Server will close soon (${jsonObject.get("time_left")}); will reconnect")
                }

                "turn_complete" -> {
                    replyStarted = false
                    listener.onTurnComplete()
                }

                "interrupted" -> {
                    Log.i(TAG, "AI model signaled barge-in interruption")
                    audioTrackManager.stopAndFlush()
                }

                // Gemini's function call, relayed by the server (?tools=device)
                "tool_call" -> {
                    val name = jsonObject.get("name")?.asString ?: return
                    val callId = jsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                    tools.submit(name, tools.parseArgs(jsonObject.get("args") ?: jsonObject.get("arguments")), callId) { result ->
                        // Always answer: Gemini waits for the tool response before it speaks again
                        if (isConnected.get()) sendJson(gson.toJson(ToolCallResponse(id = callId, name = name, result = result)))
                        else listener.onToolResultLost(name, tools.summary(result))
                    }
                }

                "tool_call_cancelled" -> {
                    Log.i(TAG, "Tool calls cancelled by the model: ${jsonObject.get("ids")}")
                }

                "error" -> {
                    AgentTrace.log(AgentTrace.Kind.ERROR, "Server error: ${jsonObject.get("message")?.asString}")
                }

                else -> {
                    Log.d(TAG, "Received other JSON: $jsonStr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming JSON message: ${e.message}", e)
        }
    }
}
