package com.nik.voiceassistant.data.trace

import android.content.Context
import android.util.Log
import com.nik.voiceassistant.security.AgentPolicy
import com.nik.voiceassistant.security.Redactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * What the agent did and how well: connections, turns, response latency, every tool call with its
 * duration and outcome, plans, warnings, errors and token use. Kept in memory for the Activity
 * screen and, when Settings > Safety & Privacy > "Save activity logs" is on, appended to a daily
 * JSON-lines file in the app's private storage (files/traces). Days older than the chosen retention
 * are deleted at start-up, once a day and when the setting changes; "Delete activity logs" removes
 * everything. Text is passed through Redactor (one-time codes, card numbers) before it is kept, and
 * PINs never reach here. These logs can contain what you said and what the agent read on screen;
 * they leave the phone only when you share them, or as traces when an OTLP endpoint is set (Tracer).
 * All file work runs on a background thread.
 */
object AgentTrace {

    private const val TAG = "AgentTrace"
    private const val MAX_IN_MEMORY = 600

    enum class Kind { SESSION, NETWORK, USER, REPLY, LATENCY, TOOL, PLAN, WARNING, ERROR, USAGE }

    data class Event(
        val time: Long,
        val kind: Kind,
        val text: String,
        // Duration of what the event measures (a tool call, a reply's first audio), if any
        val ms: Long? = null,
        // Outcome, for things that can fail
        val ok: Boolean? = null,
        // Unique within one list (live: a running number; a saved day: the line number), for list keys
        val seq: Long = 0
    )

    /** Numbers over the events in memory, for the Activity screen. */
    data class Metrics(
        val toolCalls: Int,
        val toolFailures: Int,
        val avgToolMs: Long?,
        val avgLatencyMs: Long?,
        val lastLatencyMs: Long?,
        val reconnects: Int,
        val errors: Int,
        val tokens: Long
    )

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val nextSeq = AtomicLong(1)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "agent-trace-io").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var dir: File? = null
    // Only touched on the writer thread
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var lastPrunedDay: String? = null

    /** One day's trace file. */
    data class TraceFile(val name: String, val day: String, val bytes: Long)

    private val _files = MutableStateFlow<List<TraceFile>>(emptyList())
    val files: StateFlow<List<TraceFile>> = _files.asStateFlow()

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val app = context.applicationContext
            AgentPolicy.init(app)
            Tracer.init(app)
            dir = File(app.filesDir, "traces")
        }
        writer.execute {
            dir?.mkdirs()
            prune()
            refreshFiles()
        }
        // A shorter retention applies right away
        scope.launch {
            AgentPolicy.state.map { it.logRetentionDays }.distinctUntilChanged().drop(1).collect {
                writer.execute { prune(); refreshFiles() }
            }
        }
    }

    /** Deletes days older than the retention (0 = keep). Runs on the writer thread. */
    private fun prune() {
        val keepDays = AgentPolicy.current.logRetentionDays
        lastPrunedDay = day.format(Date())
        if (keepDays > 0) {
            val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
            dir?.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
        Tracer.prune(keepDays)
    }

    private fun refreshFiles() {
        _files.value = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }?.sortedByDescending { it.name }
            ?.map { TraceFile(it.name, it.name.removeSuffix(".jsonl"), it.length()) }.orEmpty()
    }

    /** The events saved in one day's file (newest last). Reads the disk: call it off the main thread. */
    fun readDay(name: String): List<Event> {
        val file = dir?.let { File(it, name) }?.takeIf { it.exists() } ?: return emptyList()
        return runCatching { file.readLines() }.getOrDefault(emptyList()).mapIndexedNotNull { i, line ->
            runCatching {
                val o = JSONObject(line)
                Event(
                    time = o.getLong("t"),
                    kind = Kind.valueOf(o.getString("kind").uppercase()),
                    text = o.getString("text"),
                    ms = if (o.has("ms")) o.getLong("ms") else null,
                    ok = if (o.has("ok")) o.getBoolean("ok") else null,
                    seq = i.toLong()
                )
            }.getOrNull()
        }
    }

    /** readDay on a background thread. */
    suspend fun loadDay(name: String): List<Event> = withContext(Dispatchers.IO) { readDay(name) }

    fun deleteDay(name: String) {
        writer.execute {
            dir?.let { File(it, name) }?.delete()
            Tracer.deleteDay(name)
            refreshFiles()
        }
    }

    /** Deletes every saved log and trace file and clears what is in memory. */
    fun clearAll() {
        clear()
        Tracer.clearAll()
        writer.execute {
            dir?.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            refreshFiles()
        }
    }

    /** Same as clearAll. */
    fun deleteAll() = clearAll()

    fun log(kind: Kind, text: String, ms: Long? = null, ok: Boolean? = null) {
        val event = Event(System.currentTimeMillis(), kind, Redactor.forStorage(text.take(500)), ms, ok, nextSeq.getAndIncrement())
        synchronized(this) { _events.value = (_events.value + event).takeLast(MAX_IN_MEMORY) }
        if (kind == Kind.ERROR || kind == Kind.WARNING) Log.w(TAG, "$kind: ${event.text}")
        // The same moments as events on the trace of the request in progress (tools, words and the
        // conversation's start and end have spans of their own)
        when (kind) {
            Kind.USAGE -> ms?.let(Tracer::addTokens)
            Kind.NETWORK, Kind.WARNING, Kind.ERROR, Kind.PLAN, Kind.LATENCY ->
                Tracer.event(kind.name.lowercase(), mapOf("message" to event.text, "duration_ms" to ms))
            else -> {}
        }
        val folder = dir ?: return
        if (!AgentPolicy.current.saveActivityLogs) return
        writer.execute {
            try {
                val line = JSONObject()
                    .put("t", event.time).put("kind", kind.name.lowercase()).put("text", event.text)
                    .apply { ms?.let { put("ms", it) }; ok?.let { put("ok", it) } }
                val today = day.format(Date(event.time))
                val file = File(folder, "$today.jsonl")
                val isNew = !file.exists()
                file.appendText(line.toString() + "\n")
                // Once a day (and with each new day's file): drop days past the retention
                if (isNew || today != lastPrunedDay) prune()
                if (isNew) refreshFiles()
            } catch (e: Exception) {
                Log.w(TAG, "Trace not written: ${e.message}")
            }
        }
    }

    fun metrics(events: List<Event> = _events.value): Metrics {
        val tools = events.filter { it.kind == Kind.TOOL && it.ok != null }
        val latencies = events.filter { it.kind == Kind.LATENCY }.mapNotNull { it.ms }
        return Metrics(
            toolCalls = tools.size,
            toolFailures = tools.count { it.ok == false },
            avgToolMs = tools.mapNotNull { it.ms }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            avgLatencyMs = latencies.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            lastLatencyMs = latencies.lastOrNull(),
            reconnects = events.count { it.kind == Kind.NETWORK && it.text.startsWith("Reconnect") },
            errors = events.count { it.kind == Kind.ERROR },
            tokens = events.filter { it.kind == Kind.USAGE }.sumOf { it.ms ?: 0L }
        )
    }

    /** Events as readable text, to share (default: the ones in memory). Already redacted. */
    fun export(events: List<Event> = _events.value): String {
        val time = SimpleDateFormat("dd MMM HH:mm:ss.SSS", Locale.US)
        val m = metrics(events)
        val header = "Agent trace · ${m.toolCalls} tool calls (${m.toolFailures} failed), avg tool ${m.avgToolMs ?: "-"} ms, " +
            "avg reply latency ${m.avgLatencyMs ?: "-"} ms, ${m.reconnects} reconnects, ${m.errors} errors, ${m.tokens} tokens\n\n"
        return header + events.joinToString("\n") { e ->
            "${time.format(Date(e.time))}  ${e.kind.name.padEnd(8)} ${e.ms?.let { "[$it ms] " } ?: ""}" +
                "${if (e.ok == false) "FAILED " else ""}${Redactor.forStorage(e.text)}"
        }
    }

    fun clear() {
        synchronized(this) { _events.value = emptyList() }
    }
}
