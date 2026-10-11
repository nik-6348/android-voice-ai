package com.nik.voiceassistant.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nik.voiceassistant.audio.AudioTrackManager
import com.nik.voiceassistant.audio.WakeWordManager
import com.nik.voiceassistant.tools.DeviceToolExecutor
import com.nik.voiceassistant.tools.ToolCallResponse
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
 * Full-duplex WebSocket client connecting to the Gemini Live Audio backend.
 * Streams 16kHz PCM mic audio up, streams 24kHz PCM speaker audio down,
 * and intercepts real-time transcripts, search queries, interruptions, and tool calls.
 */
class LiveAudioWebSocketClient(
    private val audioTrackManager: AudioTrackManager,
    private val toolExecutor: DeviceToolExecutor? = null,
    private val listener: Callback
) {
    interface Callback {
        fun onConnected()
        fun onReconnecting(attempt: Int, reason: String)
        fun onDisconnected(reason: String)
        fun onError(error: Throwable)
        fun onUserTranscript(text: String)
        fun onUserSpeaking()
        fun onModelTranscript(text: String)
        fun onToolExecuted(toolName: String, resultSummary: String)
        /** The latest handle to continue this conversation later (kept across conversations). */
        fun onResumeHandle(handle: String?)
    }

    companion object {
        private const val TAG = "LiveAudioWebSocket"
        // Tries in a row without getting a session before giving up
        private const val MAX_RECONNECTS = 8
        // Mic audio kept while (re)connecting, sent once the connection is up: ~8 s of 16 kHz PCM
        private const val MAX_QUEUED_BYTES = 16000 * 2 * 8
    }

    private val gson = Gson()

    // Tool calls run here, one at a time: screen actions wait for the UI to settle, and doing that
    // on OkHttp's reader thread would stall incoming audio and pings
    private val toolThread = Executors.newSingleThreadExecutor()
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

    // Tool calls being run; a conversation doesn't time out in the middle of one
    private val toolsRunning = java.util.concurrent.atomic.AtomicInteger(0)

    fun isToolRunning(): Boolean = toolsRunning.get() > 0

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
    fun connect(wsUrl: String, apiKey: String, instructions: (resuming: Boolean) -> String, resume: String? = null) {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        this.wsUrl = wsUrl
        this.apiKey = apiKey
        this.instructions = instructions
        // Continue the last conversation (Gemini keeps it for a while) instead of starting from nothing
        resumeHandle = resume
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
                    "tools" to (toolExecutor?.declarations ?: emptyList()),
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
        Log.i(TAG, "Reconnecting in $delayMs ms (attempt $attempts): $reason")
        listener.onReconnecting(attempts, reason)
        reconnector.schedule({ if (!userClosed) open() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Sends raw 16kHz PCM audio chunk to the server.
     */
    fun sendAudioChunk(chunk: ByteArray) {
        if (!isConnected.get()) {
            if (!userClosed) queueAudio(chunk) // still connecting: keep it for when we're up
            return
        }
        val byteString = chunk.toByteString(0, chunk.size)
        webSocket?.send(byteString)
    }

    /** Keeps audio to send as soon as the connection opens (the oldest is dropped past ~8 s). */
    fun queueAudio(chunk: ByteArray) {
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

    /** Stops the current reply on the server; audio already on its way is dropped. */
    fun interrupt() {
        dropAudioUntil = System.currentTimeMillis() + 1000
        sendJson("""{"type":"interrupt"}""")
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting WebSocket")
        userClosed = true
        isConnected.set(false)
        synchronized(queued) { queued.clear(); queuedBytes = 0 }
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing webSocket: ${e.message}")
        }
    }

    fun isConnected(): Boolean = isConnected.get()

    private fun handleJsonMessage(jsonStr: String) {
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

                "user_transcript_interim" -> listener.onUserSpeaking()

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

                "interrupted" -> {
                    Log.i(TAG, "AI model signaled barge-in interruption")
                    audioTrackManager.stopAndFlush()
                }

                // Gemini's function call, relayed by the server (?tools=device)
                "tool_call" -> {
                    toolsRunning.incrementAndGet()
                    toolThread.execute {
                        try { executeToolCall(jsonObject) } finally { toolsRunning.decrementAndGet() }
                    }
                }

                "tool_call_cancelled" -> {
                    Log.i(TAG, "Tool calls cancelled by the model: ${jsonObject.get("ids")}")
                }

                "error" -> {
                    Log.e(TAG, "Server error: ${jsonObject.get("message")?.asString}")
                }

                else -> {
                    Log.d(TAG, "Received other JSON: $jsonStr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming JSON message: ${e.message}", e)
        }
    }

    private fun executeToolCall(jsonObj: JsonObject) {
        val toolName = jsonObj.get("name")?.asString ?: return
        val callId = jsonObj.get("id")?.asString

        val argsMap = mutableMapOf<String, Any?>()
        val argsElement = jsonObj.get("args") ?: jsonObj.get("arguments")
        if (argsElement != null && argsElement.isJsonObject) {
            val obj = argsElement.asJsonObject
            for (key in obj.keySet()) {
                val elem = obj.get(key)
                when {
                    elem.isJsonPrimitive && elem.asJsonPrimitive.isBoolean -> argsMap[key] = elem.asBoolean
                    elem.isJsonPrimitive && elem.asJsonPrimitive.isNumber -> argsMap[key] = elem.asNumber
                    elem.isJsonPrimitive && elem.asJsonPrimitive.isString -> argsMap[key] = elem.asString
                    else -> argsMap[key] = elem.toString()
                }
            }
        }

        val result = toolExecutor?.execute(toolName, argsMap)
            ?: mapOf("error" to "Device tools are not available")
        // The screen listing is for the model; the transcript gets a one-line summary
        val summary = result["error"] ?: result["done"] ?: result["opened"] ?: result["launched"]
            ?: result["app"]?.let { "screen of $it" } ?: result.filterKeys { it != "screen" && it != "apps" }
        listener.onToolExecuted("$toolName $argsMap", summary.toString().take(160))

        // Always answer: Gemini waits for the tool response before it speaks again
        val response = ToolCallResponse(
            id = callId,
            name = toolName,
            result = result
        )
        val responseJson = gson.toJson(response)
        sendJson(responseJson)
        Log.i(TAG, "Sent tool response back to AI: $responseJson")
    }
}
