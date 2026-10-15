package com.nik.voiceassistant.network

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nik.voiceassistant.audio.AudioTrackManager
import com.nik.voiceassistant.audio.WakeWordManager
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.tools.ToolCallRunner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Gemini Live API straight from the phone (no server in between, so the reply starts sooner).
 * Protocol: https://ai.google.dev/api/live — setup first, then realtimeInput audio (16 kHz PCM) and
 * screen images (JPEG), clientContent text and toolResponse; the server streams 24 kHz PCM audio,
 * transcriptions, tool calls, resumption handles and a goAway before it closes the connection.
 *
 * Staying connected: every connection lasts ~10 min, so on goAway it hands over to a new connection
 * that resumes the same session (sessionResumption handle, valid 2 h) as soon as no tool is running;
 * drops reconnect with backoff the same way. Context window compression keeps long and image-heavy
 * sessions going past the 15 min (2 min with images) limit. Mic audio is queued while connecting.
 */
class GeminiLiveClient(
    private val audioTrackManager: AudioTrackManager,
    private val tools: ToolCallRunner,
    // Function declarations (JSON Schema parameters), read at each connection
    private val declarations: () -> List<Map<String, Any>>,
    private val listener: LiveClient.Callback
) : LiveClient {

    companion object {
        private const val TAG = "GeminiLive"
        private const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_RECONNECTS = 8
        // Working sessions that may drop within the window before giving up (planned handovers don't count)
        private const val MAX_DROPS = 6
        private const val DROP_WINDOW_MS = 2 * 60_000L
        // ~8 s of 16 kHz PCM kept while (re)connecting
        private const val MAX_QUEUED_BYTES = 16000 * 2 * 8
        private const val AUDIO_MIME = "audio/pcm;rate=16000"
    }

    override val supportsVision = true

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val reconnector = Executors.newSingleThreadScheduledExecutor()

    // Guards replacing the socket (connect, reconnects, handovers, disconnect run on different threads)
    private val lock = Any()
    @Volatile private var socket: WebSocket? = null
    // Bumped by connect() and disconnect(): a reconnect scheduled for an earlier conversation does nothing
    @Volatile private var generation = 0
    // setupComplete received on the current socket: audio and messages may flow
    private val ready = AtomicBoolean(false)
    @Volatile private var config: LiveClient.Config? = null
    @Volatile private var resumeHandle: String? = null
    @Volatile private var userClosed = true
    @Volatile private var attempts = 0
    @Volatile private var everConnected = false
    @Volatile private var handingOver = false
    // Whether the current socket got as far as setupComplete, and why the last one was refused before that
    @Volatile private var socketReady = false
    @Volatile private var lastRefusal: String? = null
    // Google Search was refused for this key (e.g. no grounding quota): the conversation goes on without it
    @Volatile private var searchOff = false
    // When working sessions dropped lately: attempts resets with each session, so a server that takes
    // the setup and then closes would otherwise be retried every 250 ms forever
    private val drops = ArrayDeque<Long>()

    // The user's words arrive in pieces; sent on as one line when the model starts answering
    private val userWords = StringBuilder()
    // Reply audio after the user stopped it is dropped until the turn ends
    @Volatile private var dropReply = false
    @Volatile private var replyStarted = false
    @Volatile private var turnTokens = 0L

    private val queued = ArrayDeque<ByteArray>()
    private var queuedBytes = 0

    override fun connect(config: LiveClient.Config) {
        val gen = synchronized(lock) {
            if (!userClosed) return
            this.config = config
            resumeHandle = config.resumeHandle
            attempts = 0
            everConnected = false
            handingOver = false
            lastRefusal = null
            searchOff = false
            synchronized(drops) { drops.clear() }
            userClosed = false
            ++generation
        }
        open(gen)
    }

    /** Whether this is still the live connection (checked under the lock: a callback can race open()). */
    private fun isCurrent(webSocket: WebSocket): Boolean = synchronized(lock) { webSocket === socket }

    /** A pasted key often carries a newline, space or zero-width character, which OkHttp rejects in a header. */
    private fun cleanKey(key: String): String =
        key.trim().filterNot { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    /** Opens a new connection for conversation [gen]; never throws (it also runs on the reconnector thread). */
    private fun open(gen: Int) {
        synchronized(lock) {
            val cfg = config
            if (gen != generation || userClosed || cfg == null) return
            ready.set(false)
            socketReady = false
            // The previous connection (before a handover, or one a retry replaces) must not linger
            socket?.let { old -> runCatching { old.close(1000, "replaced") } }
            socket = null
            Log.i(TAG, "Connecting to Gemini Live (${cfg.model})${if (resumeHandle != null) ", resuming" else ""}")
            val request = try {
                // The key goes in a header, never in the URL (URLs end up in logs)
                Request.Builder().url(ENDPOINT).header("x-goog-api-key", cleanKey(cfg.apiKey)).build()
            } catch (e: IllegalArgumentException) {
                // (OkHttp's message would quote the key: not shown)
                userClosed = true
                handingOver = false
                null
            }
            if (request != null) {
                socket = http.newWebSocket(request, socketListener(cfg))
                return
            }
        }
        listener.onError(IllegalArgumentException("Invalid server URL or API key: the Gemini API key has characters that can't be sent; paste it again in Settings"))
    }

    private fun socketListener(cfg: LiveClient.Config) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return // replaced while it was opening
            webSocket.send(gson.toJson(mapOf("setup" to setup(cfg))))
        }

        // The Live API sends its JSON in binary frames
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === socket) handle(webSocket, bytes.utf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket === socket) handle(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return // an old connection after a handover
            ready.set(false)
            // A handover connection that ended before its session started: what follows is an ordinary
            // reconnect (onReconnecting, then onConnected), so the UI doesn't stay at "connecting"
            handingOver = false
            Log.i(TAG, "Closed: $code $reason")
            // Refused before the session even started, twice for the same reason (billing, quota,
            // key, model…): retrying can't fix it, so stop and show Gemini's own message
            val refusedAgain = !socketReady && reason.isNotBlank() && reason == lastRefusal
            if (!socketReady && reason.isNotBlank()) lastRefusal = reason
            // A refusal before setup with Google Search on: the key may lack Search quota while the
            // rest works, so try once more without it rather than failing the conversation
            val dropSearch = !userClosed && !socketReady && config?.search == true && !searchOff && reason.isNotBlank()
            when {
                userClosed -> listener.onDisconnected(reason)
                dropSearch -> {
                    // The same refusal again without Search means it wasn't Search: it then stops at once
                    searchOff = true
                    AgentTrace.log(AgentTrace.Kind.WARNING, "Gemini refused the session with Google Search on ($reason); continuing without Search (the research tool still works)")
                    val gen = generation
                    reconnector.execute { open(gen) }
                }
                isFatal(code, null) || refusedAgain -> fail("Gemini refused the session ($code): $reason")
                else -> scheduleReconnect("closed ($code ${reason.take(120)})")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            ready.set(false)
            handingOver = false
            Log.w(TAG, "Failure: ${t.message} (HTTP ${response?.code})")
            if (userClosed) return
            if (isFatal(null, response?.code)) fail("Gemini refused the connection (HTTP ${response?.code}): check the Gemini API key and model in Settings")
            else scheduleReconnect(t.message ?: "connection failed")
        }
    }

    /** Errors no retry can fix: a wrong key or model, or a request Gemini rejects. */
    private fun isFatal(code: Int?, http: Int?): Boolean =
        code == 1007 || code == 1008 || http == 400 || http == 401 || http == 403 || http == 404

    private fun fail(message: String) {
        userClosed = true
        // (recorded in the trace by the session's onError)
        listener.onError(RuntimeException(message))
    }

    private fun setup(cfg: LiveClient.Config): Map<String, Any> {
        val resuming = resumeHandle != null
        val functions = declarations().map { d ->
            // Phone actions must run in order on the current screen: blocking calls (3.x defaults to async)
            buildMap {
                put("name", d["name"]!!)
                d["description"]?.let { put("description", it) }
                (d["parameters"] as? Map<*, *>)?.let { put("parameters", geminiSchema(it)) }
                put("behavior", "BLOCKING")
            }
        }
        val toolList = buildList {
            if (cfg.search && !searchOff) add(mapOf("googleSearch" to emptyMap<String, Any>()))
            if (functions.isNotEmpty()) add(mapOf("functionDeclarations" to functions))
        }
        val generation = buildMap<String, Any> {
            put("responseModalities", listOf("AUDIO"))
            put("speechConfig", mapOf("voiceConfig" to mapOf("prebuiltVoiceConfig" to mapOf("voiceName" to cfg.voice))))
            // 2.5 native audio thinks by default, which delays the reply; 3.x live models reject thinking settings
            if (cfg.model.contains("2.5")) put("thinkingConfig", mapOf("thinkingBudget" to 0))
        }
        return buildMap {
            put("model", if (cfg.model.startsWith("models/")) cfg.model else "models/${cfg.model}")
            put("generationConfig", generation)
            put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to cfg.instructions(resuming)))))
            if (toolList.isNotEmpty()) put("tools", toolList)
            put(
                "realtimeInputConfig", mapOf(
                    "automaticActivityDetection" to mapOf(
                        "startOfSpeechSensitivity" to "START_SENSITIVITY_HIGH",
                        "endOfSpeechSensitivity" to "END_SENSITIVITY_HIGH",
                        "prefixPaddingMs" to 100,
                        "silenceDurationMs" to 600
                    )
                )
            )
            // The languages to expect (the phone's own, plus Hindi and Indian English); without them
            // room noise was heard as other languages and answered in them
            val languages = (android.os.LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } } + listOf("hi-IN", "en-IN"))
                .distinct().take(4)
            put("inputAudioTranscription", mapOf("languageCodes" to languages))
            put("outputAudioTranscription", emptyMap<String, Any>())
            put("contextWindowCompression", mapOf("slidingWindow" to emptyMap<String, Any>()))
            put("sessionResumption", resumeHandle?.let { mapOf("handle" to it) } ?: emptyMap<String, Any>())
        }
    }

    /** JSON Schema (lowercase types) -> Gemini Schema (UPPERCASE types), recursively. */
    private fun geminiSchema(s: Map<*, *>): Map<String, Any> = buildMap {
        (s["type"] as? String)?.let { put("type", it.uppercase()) }
        (s["description"] as? String)?.let { put("description", it) }
        (s["enum"] as? List<*>)?.let { put("enum", it.map(Any?::toString)) }
        (s["items"] as? Map<*, *>)?.let { put("items", geminiSchema(it)) }
        (s["properties"] as? Map<*, *>)?.let { props ->
            put("properties", props.entries.associate { (k, v) -> k.toString() to geminiSchema(v as? Map<*, *> ?: emptyMap<String, Any>()) })
        }
        (s["required"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { put("required", it.map(Any?::toString)) }
    }

    // Gson getters throw on a missing, null or differently typed field; these return null instead
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun handle(webSocket: WebSocket, json: String) {
        if (userClosed) return
        // An exception here would fail the socket and start a reconnect: one odd message is skipped instead
        try {
            val msg = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            if (msg == null) {
                Log.w(TAG, "Unreadable message (${json.length} chars)")
                return
            }
            // Usage comes with many messages; the latest count is logged once per turn
            msg.obj("usageMetadata")?.long("totalTokenCount")?.let { turnTokens = it }
            when {
                msg.has("setupComplete") -> onReady(webSocket)
                msg.has("serverContent") -> msg.obj("serverContent")?.let { serverContent(it) }
                msg.has("toolCall") -> msg.obj("toolCall")?.let { toolCall(it) }
                msg.has("toolCallCancellation") -> Log.i(TAG, "Tool calls cancelled: ${msg.get("toolCallCancellation")}")
                msg.has("sessionResumptionUpdate") -> msg.obj("sessionResumptionUpdate")?.let { update ->
                    val handle = update.str("newHandle")
                    if (update.bool("resumable") == true && !handle.isNullOrBlank()) {
                        resumeHandle = handle
                        listener.onResumeHandle(handle)
                    }
                }
                msg.has("goAway") -> {
                    val left = msg.obj("goAway")?.str("timeLeft")
                    AgentTrace.log(AgentTrace.Kind.NETWORK, "Gemini will close this connection (${left ?: "soon"}): handing over")
                    handOver(left)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skipped a message (${json.length} chars): ${e.javaClass.simpleName}")
            AgentTrace.log(AgentTrace.Kind.WARNING, "Skipped a Gemini message that couldn't be handled (${e.javaClass.simpleName})")
        }
    }

    private fun onReady(webSocket: WebSocket) {
        synchronized(lock) {
            // Replaced or disconnected meanwhile: this session isn't the live one any more
            if (webSocket !== socket || userClosed) return
            socketReady = true
            lastRefusal = null
            attempts = 0
            // Audio from before the connection was up, in ~1 s messages. ready flips inside the same lock
            // sendAudioChunk queues under, so live mic audio can't overtake it or be left in the queue
            synchronized(queued) {
                val all = java.io.ByteArrayOutputStream(queuedBytes)
                while (queued.isNotEmpty()) all.write(queued.removeFirst())
                queuedBytes = 0
                val bytes = all.toByteArray()
                for (i in bytes.indices step 32000) webSocket.send(audioMessage(bytes, i, minOf(32000, bytes.size - i)))
                ready.set(true)
            }
        }
        val wasHandover = handingOver
        handingOver = false
        if (!everConnected) WakeWordManager.playWakeChime()
        everConnected = true
        if (!wasHandover) listener.onConnected()
    }

    private fun serverContent(content: JsonObject) {
        content.obj("inputTranscription")?.str("text")?.let { words ->
            synchronized(userWords) { userWords.append(words) }
            listener.onUserSpeaking()
        }
        content.obj("modelTurn")?.arr("parts")?.forEach { part ->
            val inline = part.takeIf { it.isJsonObject }?.asJsonObject?.obj("inlineData") ?: return@forEach
            if (inline.str("mimeType")?.startsWith("audio/") != true) return@forEach
            val data = inline.str("data") ?: return@forEach
            flushUserWords()
            if (dropReply) return@forEach
            if (!replyStarted) {
                replyStarted = true
                listener.onModelAudioStart()
            }
            audioTrackManager.enqueueAudioChunk(Base64.decode(data, Base64.DEFAULT))
        }
        content.obj("outputTranscription")?.str("text")?.let { text ->
            flushUserWords()
            if (!dropReply && text.isNotBlank()) listener.onModelTranscript(text)
        }
        if (content.bool("interrupted") == true) {
            // The user talked over the reply: stop it here too
            audioTrackManager.stopAndFlush()
            AgentTrace.log(AgentTrace.Kind.REPLY, "Reply interrupted by the user")
        }
        if (content.bool("turnComplete") == true) {
            flushUserWords()
            dropReply = false
            replyStarted = false
            if (turnTokens > 0) AgentTrace.log(AgentTrace.Kind.USAGE, "Tokens used this turn", ms = turnTokens)
            turnTokens = 0
            listener.onTurnComplete()
        }
    }

    private fun flushUserWords() {
        val text = synchronized(userWords) { userWords.toString().trim().also { userWords.clear() } }
        if (text.isNotEmpty()) listener.onUserTranscript(text)
    }

    private fun toolCall(call: JsonObject) {
        flushUserWords()
        call.arr("functionCalls")?.forEach { element ->
            val fc = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = fc.str("name") ?: return@forEach
            val id = fc.str("id")
            tools.submit(name, tools.parseArgs(fc.get("args")), id) { result ->
                val response = mapOf(
                    "toolResponse" to mapOf(
                        "functionResponses" to listOf(buildMap {
                            id?.let { put("id", it) }
                            put("name", name)
                            put("response", result)
                        })
                    )
                )
                // A handover may have replaced the socket meanwhile: answer on the current one
                val current = socket
                if (ready.get() && current != null && current.send(gson.toJson(response))) return@submit
                listener.onToolResultLost(name, tools.summary(result))
            }
        }
    }

    /**
     * Moves to a fresh connection that resumes this session, before Gemini closes the current one.
     * Waits for a running tool to answer first (its result belongs to this session's turn).
     */
    private fun handOver(timeLeft: String?) {
        val leftMs = timeLeft?.removeSuffix("s")?.toDoubleOrNull()?.times(1000)?.toLong() ?: 5000L
        val gen = generation
        val from = socket
        reconnector.execute {
            val deadline = System.currentTimeMillis() + maxOf(0L, leftMs - 1500)
            while (tools.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(150)
            // Gemini may have closed it meanwhile and a reconnect taken over: a handover now would
            // replace that connection and, being "silent", leave the UI at "reconnecting"
            if (userClosed || gen != generation || socket !== from) return@execute
            handingOver = true
            // (open closes the old connection)
            open(gen)
        }
    }

    private fun scheduleReconnect(reason: String) {
        // Only drops of a session that had started count here (failed tries are capped by attempts)
        if (socketReady) {
            val now = SystemClock.elapsedRealtime()
            val tooMany = synchronized(drops) {
                drops.addLast(now)
                while (now - drops.first() > DROP_WINDOW_MS) drops.removeFirst()
                drops.size > MAX_DROPS
            }
            if (tooMany) {
                fail("Connection keeps dropping ($reason): it dropped more than $MAX_DROPS times in 2 min, so stopped retrying. Check the network and try again")
                return
            }
        }
        attempts++
        if (attempts > MAX_RECONNECTS) {
            fail("Connection lost ($reason); gave up after $MAX_RECONNECTS tries")
            return
        }
        // A resume that keeps failing (e.g. an expired handle): start fresh; the instructions carry the recent conversation
        if (attempts > 3 && resumeHandle != null) {
            resumeHandle = null
            listener.onResumeHandle(null)
        }
        val delayMs = minOf(250L shl (attempts - 1), 6000L)
        AgentTrace.log(AgentTrace.Kind.NETWORK, "Reconnecting in $delayMs ms (attempt $attempts): $reason")
        listener.onReconnecting(attempts, reason)
        val gen = generation
        reconnector.schedule({ open(gen) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun audioMessage(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        val data = Base64.encodeToString(bytes, offset, length, Base64.NO_WRAP)
        return """{"realtimeInput":{"audio":{"mimeType":"$AUDIO_MIME","data":"$data"}}}"""
    }

    override fun sendAudioChunk(chunk: ByteArray) {
        // The socket is read before ready: open() clears ready before it swaps the socket
        val ws = socket
        if (ws != null && ready.get()) {
            ws.send(audioMessage(chunk))
            return
        }
        synchronized(queued) {
            // Checked again under the lock: onReady may have just sent the queue and flipped ready
            val now = socket
            if (now == null || !ready.get()) {
                if (!userClosed) queueAudio(chunk)
                return
            }
            now.send(audioMessage(chunk))
        }
    }

    override fun queueAudio(chunk: ByteArray) {
        synchronized(queued) {
            queued.addLast(chunk)
            queuedBytes += chunk.size
            while (queuedBytes > MAX_QUEUED_BYTES && queued.size > 1) queuedBytes -= queued.removeFirst().size
        }
    }

    override fun sendText(text: String) {
        if (!ready.get()) return
        val message = mapOf(
            "clientContent" to mapOf(
                "turns" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to text)))),
                "turnComplete" to true
            )
        )
        socket?.send(gson.toJson(message))
    }

    override fun sendImage(jpeg: ByteArray): Boolean {
        val ws = socket
        if (!ready.get() || ws == null) return false
        val data = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return ws.send("""{"realtimeInput":{"video":{"mimeType":"image/jpeg","data":"$data"}}}""")
    }

    override fun interrupt() {
        // The Live API has no "stop" message: drop the rest of this reply here
        dropReply = true
        audioTrackManager.stopAndFlush()
    }

    override fun isConnected(): Boolean = ready.get()

    override fun isToolRunning(): Boolean = tools.isRunning()

    override fun clearResumeHandle() {
        resumeHandle = null
    }

    override fun disconnect() {
        synchronized(lock) {
            generation++
            userClosed = true
            handingOver = false
            ready.set(false)
            resumeHandle = null
            synchronized(queued) { queued.clear(); queuedBytes = 0 }
            synchronized(userWords) { userWords.clear() }
            runCatching { socket?.close(1000, "User disconnected") }
            socket = null
        }
    }
}
