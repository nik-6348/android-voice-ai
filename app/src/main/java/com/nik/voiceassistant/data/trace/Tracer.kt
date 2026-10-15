package com.nik.voiceassistant.data.trace

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OpenTelemetry-style tracing of the agent: every user request (spoken or typed) is one trace. Its
 * root span covers the whole request until the goal is reached or the next request; child spans are
 * the model's reply (time to first audio, reply text, tokens) and every tool call (full arguments and
 * result, duration, status). Plans, decisions, warnings, reconnects and errors are span events.
 * Attribute names follow the OpenTelemetry GenAI conventions (gen_ai.*).
 *
 * Finished traces are saved on the phone as OTLP/JSON lines (files/traces/otel/<day>.jsonl), so they
 * can be opened later, shared, or imported into Jaeger / Grafana Tempo; with an OTLP endpoint set,
 * each finished trace is also sent there (OTLP/HTTP JSON, POST <endpoint>/v1/traces).
 */
object Tracer {

    private const val TAG = "Tracer"
    private const val SERVICE = "nik-voice-agent"
    private const val MAX_TRACES = 60
    // Big values (screen listings) are cut to this many characters
    private const val MAX_VALUE = 16_000

    enum class Kind { REQUEST, LLM, TOOL, SESSION }
    enum class Status { UNSET, OK, ERROR }

    data class SpanEvent(val time: Long, val name: String, val attributes: Map<String, String> = emptyMap())

    /** A span as shown and saved (immutable snapshot). */
    data class SpanData(
        val traceId: String,
        val spanId: String,
        val parentId: String?,
        val name: String,
        val kind: Kind,
        val start: Long,
        val end: Long?,
        val status: Status,
        val statusMessage: String?,
        val attributes: Map<String, String>,
        val events: List<SpanEvent>
    ) {
        val durationMs: Long? get() = end?.let { it - start }
    }

    /** One request's trace: its root span and all spans under it. */
    data class TraceData(val traceId: String, val spans: List<SpanData>) {
        val root: SpanData get() = spans.first { it.parentId == null }
        val start: Long get() = spans.minOf { it.start }
        val end: Long? get() = if (spans.any { it.end == null }) null else spans.maxOf { it.end!! }
        val toolCount: Int get() = spans.count { it.kind == Kind.TOOL }
        val errorCount: Int get() = spans.count { it.status == Status.ERROR }
    }

    /** A live, still-changing span. */
    class Span internal constructor(
        val traceId: String,
        val spanId: String,
        val parentId: String?,
        @Volatile var name: String,
        val kind: Kind,
        val start: Long
    ) {
        @Volatile internal var end: Long? = null
        @Volatile internal var status = Status.UNSET
        @Volatile internal var statusMessage: String? = null
        internal val attributes = java.util.Collections.synchronizedMap(linkedMapOf<String, String>())
        internal val events = java.util.Collections.synchronizedList(mutableListOf<SpanEvent>())

        internal fun snapshot() = SpanData(
            traceId, spanId, parentId, name, kind, start, end, status, statusMessage,
            synchronized(attributes) { LinkedHashMap(attributes) }, synchronized(events) { events.toList() }
        )
    }

    private val random = SecureRandom()
    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    // All spans of the traces kept in memory, by trace id (newest last)
    private val live = linkedMapOf<String, MutableList<Span>>()
    private val _traces = MutableStateFlow<List<TraceData>>(emptyList())
    /** Recent traces, newest first, updating live. */
    val traces: StateFlow<List<TraceData>> = _traces.asStateFlow()

    // The request in progress, and the conversation's own trace (connection events between requests)
    @Volatile private var request: Span? = null
    @Volatile private var session: Span? = null

    private val io = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    @Volatile private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var resource: JSONArray = JSONArray()

    private val _otlpEndpoint = MutableStateFlow("")
    /** Optional OTLP/HTTP collector base URL (e.g. https://collector:4318); blank = keep on the phone only. */
    val otlpEndpoint: StateFlow<String> = _otlpEndpoint.asStateFlow()

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "traces/otel").apply { mkdirs() }
        prefs = context.getSharedPreferences("agent_trace", Context.MODE_PRIVATE).also { _otlpEndpoint.value = it.getString("otlp_endpoint", "").orEmpty() }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        resource = JSONArray()
            .put(attr("service.name", SERVICE))
            .put(attr("service.version", version ?: "unknown"))
            .put(attr("device.manufacturer", Build.MANUFACTURER))
            .put(attr("device.model.name", Build.MODEL))
            .put(attr("os.type", "android"))
            .put(attr("os.version", Build.VERSION.RELEASE))
    }

    fun setOtlpEndpoint(url: String) {
        _otlpEndpoint.value = url.trim()
        prefs?.edit()?.putString("otlp_endpoint", url.trim())?.apply()
    }

    // ---- Recording ----

    /** A conversation began: its own trace holds connection events that happen between requests. */
    fun startSession(attributes: Map<String, Any?>): Span = synchronized(this) {
        session?.let { end(it, Status.OK) }
        newSpan("conversation", Kind.SESSION, null, attributes).also { session = it }
    }

    fun endSession(reason: String) = synchronized(this) {
        request?.let { endRequest(Status.OK, "conversation ended") }
        session?.let {
            it.attributes["session.end_reason"] = reason
            end(it, Status.OK)
        }
        session = null
    }

    /** A new request from the user: ends the previous one, starts this one's trace. */
    fun startRequest(input: String, mode: String, attributes: Map<String, Any?> = emptyMap()): Span = synchronized(this) {
        request?.let { endRequest(Status.OK, "superseded by the next request") }
        val title = input.trim().ifEmpty { "(no words)" }
        newSpan("request: ${title.take(60)}", Kind.REQUEST, null, mapOf("user.input" to title, "user.input.mode" to mode) + attributes)
            .also { request = it }
    }

    /** More words of the same request (speech-to-text sends a long sentence in parts). */
    fun appendToRequest(input: String) = synchronized(this) {
        val r = request ?: return@synchronized
        val text = listOfNotNull(r.attributes["user.input"], input.trim()).joinToString(" ")
        r.attributes["user.input"] = clip(text)
        r.name = "request: ${text.take(60)}"
        publish()
    }

    /** Tokens the model used, added up on the request in progress. */
    fun addTokens(tokens: Long) {
        val r = request ?: return
        r.attributes["gen_ai.usage.total_tokens"] = ((r.attributes["gen_ai.usage.total_tokens"]?.toLongOrNull() ?: 0L) + tokens).toString()
        publish()
    }

    /** The request in progress, starting one if the agent acts without one (e.g. a saved automation). */
    fun currentRequest(): Span = request ?: startRequest("(agent-initiated)", "system")

    fun hasOpenRequest(): Boolean = request != null

    /** Ends the request in progress: the goal is reached, failed, or the conversation is over. */
    fun endRequest(status: Status = Status.OK, message: String? = null) = synchronized(this) {
        val root = request ?: return@synchronized
        request = null
        // Close anything left open under it (a reply cut off by the end)
        live[root.traceId]?.filter { it.end == null && it !== root }?.forEach { end(it, Status.UNSET) }
        val children = live[root.traceId].orEmpty()
        root.attributes["agent.tool_calls"] = children.count { it.kind == Kind.TOOL }.toString()
        root.attributes["agent.errors"] = children.count { it.status == Status.ERROR }.toString()
        end(root, status, message)
        persist(root.traceId)
    }

    /** A child span of the request in progress. */
    fun startSpan(name: String, kind: Kind, attributes: Map<String, Any?> = emptyMap()): Span = synchronized(this) {
        val parent = currentRequest()
        newSpan(name, kind, parent, attributes)
    }

    fun setAttribute(span: Span?, key: String, value: Any?) {
        span ?: return
        value?.let { span.attributes[key] = clip(it.toString()) }
        publish()
    }

    /** Sets an attribute on the request in progress (e.g. its goal). */
    fun setRequestAttribute(key: String, value: Any?) = setAttribute(request, key, value)

    fun end(span: Span, status: Status = Status.OK, message: String? = null, attributes: Map<String, Any?> = emptyMap()) {
        attributes.forEach { (k, v) -> v?.let { span.attributes[k] = clip(it.toString()) } }
        if (span.end == null) span.end = System.currentTimeMillis()
        span.status = status
        span.statusMessage = message
        publish()
    }

    /** An event on a span, else on the request in progress, else on the conversation's trace. */
    fun event(name: String, attributes: Map<String, Any?> = emptyMap(), span: Span? = null) {
        val target = span ?: request ?: session ?: return
        target.events += SpanEvent(System.currentTimeMillis(), name, attributes.mapNotNull { (k, v) -> v?.let { k to clip(it.toString()) } }.toMap())
        publish()
    }

    private fun newSpan(name: String, kind: Kind, parent: Span?, attributes: Map<String, Any?>): Span {
        val traceId = parent?.traceId ?: hex(16)
        val span = Span(traceId, hex(8), parent?.spanId, name, kind, System.currentTimeMillis())
        attributes.forEach { (k, v) -> v?.let { span.attributes[k] = clip(it.toString()) } }
        live.getOrPut(traceId) { mutableListOf() } += span
        while (live.size > MAX_TRACES) live.remove(live.keys.first())
        publish()
        return span
    }

    private fun clip(s: String) = if (s.length > MAX_VALUE) s.take(MAX_VALUE) + "… (${s.length - MAX_VALUE} more chars)" else s

    private fun publish() {
        val snapshot = synchronized(this) {
            live.values.mapNotNull { spans -> spans.toList().takeIf { it.isNotEmpty() }?.map { it.snapshot() } }
        }
        _traces.value = snapshot.filter { spans -> spans.any { it.parentId == null } }
            .map { TraceData(it.first().traceId, it) }
            .sortedByDescending { it.start }
    }

    // ---- Saving and exporting (OTLP/JSON) ----

    private fun persist(traceId: String) {
        val spans = synchronized(this) { live[traceId]?.map { it.snapshot() } } ?: return
        val folder = dir ?: return
        val endpoint = _otlpEndpoint.value
        io.execute {
            val payload = otlp(spans)
            try {
                File(folder, "${day.format(Date(spans.minOf { it.start }))}.jsonl").appendText(payload.toString() + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Trace not saved: ${e.message}")
            }
            if (endpoint.isNotBlank()) export(endpoint, payload)
        }
    }

    private fun export(endpoint: String, payload: JSONObject) {
        val url = endpoint.trimEnd('/').let { if (it.endsWith("/v1/traces")) it else "$it/v1/traces" }
        try {
            http.newCall(Request.Builder().url(url).post(payload.toString().toRequestBody("application/json".toMediaType())).build())
                .execute().use { if (!it.isSuccessful) Log.w(TAG, "OTLP export: HTTP ${it.code}") }
        } catch (e: Exception) {
            Log.w(TAG, "OTLP export failed: ${e.message}")
        }
    }

    private fun attr(key: String, value: String) = JSONObject().put("key", key).put("value", JSONObject().put("stringValue", value))

    /** Spans as an OTLP/JSON ExportTraceServiceRequest. */
    fun otlp(spans: List<SpanData>): JSONObject {
        val out = JSONArray()
        spans.forEach { s ->
            out.put(JSONObject().apply {
                put("traceId", s.traceId)
                put("spanId", s.spanId)
                s.parentId?.let { put("parentSpanId", it) }
                put("name", s.name)
                // INTERNAL = 1, CLIENT = 3 (calls out to the model)
                put("kind", if (s.kind == Kind.LLM) 3 else 1)
                put("startTimeUnixNano", (s.start * 1_000_000).toString())
                put("endTimeUnixNano", ((s.end ?: s.start) * 1_000_000).toString())
                put("attributes", JSONArray().apply {
                    put(attr("agent.span.kind", s.kind.name.lowercase()))
                    s.attributes.forEach { (k, v) -> put(attr(k, v)) }
                })
                put("events", JSONArray().apply {
                    s.events.forEach { e ->
                        put(JSONObject().put("timeUnixNano", (e.time * 1_000_000).toString()).put("name", e.name)
                            .put("attributes", JSONArray().apply { e.attributes.forEach { (k, v) -> put(attr(k, v)) } }))
                    }
                })
                put("status", JSONObject().put("code", when (s.status) { Status.OK -> 1; Status.ERROR -> 2; else -> 0 })
                    .apply { s.statusMessage?.let { put("message", it) } })
            })
        }
        return JSONObject().put(
            "resourceSpans", JSONArray().put(
                JSONObject()
                    .put("resource", JSONObject().put("attributes", resource))
                    .put("scopeSpans", JSONArray().put(JSONObject().put("scope", JSONObject().put("name", "com.nik.voiceassistant.agent").put("version", "1")).put("spans", out)))
            )
        )
    }

    // ---- Saved days ----

    /** Days with saved traces (file names), newest first. */
    fun savedDays(): List<String> = dir?.listFiles()?.filter { it.name.endsWith(".jsonl") }?.map { it.name }?.sortedDescending().orEmpty()

    /** The traces saved on a day, newest first. */
    fun readDay(name: String): List<TraceData> {
        val file = dir?.let { File(it, name) }?.takeIf { it.exists() } ?: return emptyList()
        return file.readLines().mapNotNull { line -> runCatching { parse(JSONObject(line)) }.getOrNull() }.sortedByDescending { it.start }
    }

    private fun parse(o: JSONObject): TraceData? {
        val spans = o.getJSONArray("resourceSpans").getJSONObject(0).getJSONArray("scopeSpans").getJSONObject(0).getJSONArray("spans")
        fun attrs(a: JSONArray?): Map<String, String> = (0 until (a?.length() ?: 0)).associate { i ->
            val kv = a!!.getJSONObject(i)
            kv.getString("key") to kv.getJSONObject("value").optString("stringValue")
        }
        val list = (0 until spans.length()).map { i ->
            val s = spans.getJSONObject(i)
            val a = attrs(s.optJSONArray("attributes"))
            val events = s.optJSONArray("events")
            SpanData(
                traceId = s.getString("traceId"),
                spanId = s.getString("spanId"),
                parentId = s.optString("parentSpanId").ifBlank { null },
                name = s.getString("name"),
                kind = runCatching { Kind.valueOf(a["agent.span.kind"].orEmpty().uppercase()) }.getOrDefault(Kind.TOOL),
                start = s.getString("startTimeUnixNano").toLong() / 1_000_000,
                end = s.getString("endTimeUnixNano").toLong() / 1_000_000,
                status = when (s.optJSONObject("status")?.optInt("code")) { 1 -> Status.OK; 2 -> Status.ERROR; else -> Status.UNSET },
                statusMessage = s.optJSONObject("status")?.optString("message")?.ifBlank { null },
                attributes = a - "agent.span.kind",
                events = (0 until (events?.length() ?: 0)).map { j ->
                    val e = events!!.getJSONObject(j)
                    SpanEvent(e.getString("timeUnixNano").toLong() / 1_000_000, e.getString("name"), attrs(e.optJSONArray("attributes")))
                }
            )
        }
        return list.takeIf { l -> l.any { it.parentId == null } }?.let { TraceData(it.first().traceId, it) }
    }

    /** A saved day's file, to share as is (OTLP/JSON lines). */
    fun dayFile(name: String): File? = dir?.let { File(it, name) }?.takeIf { it.exists() }

    fun deleteDay(name: String) { io.execute { dir?.let { File(it, name) }?.delete() } }

    fun deleteAll() {
        synchronized(this) { live.clear(); request = null }
        publish()
        io.execute { dir?.listFiles()?.forEach { it.delete() } }
    }

    /** Deletes saved days older than keepDays. */
    fun prune(keepDays: Int) {
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        io.execute { dir?.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() } }
    }
}
