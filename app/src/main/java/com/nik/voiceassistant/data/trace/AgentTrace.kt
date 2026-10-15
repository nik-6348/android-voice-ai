package com.nik.voiceassistant.data.trace

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the agent did and how well: connections, turns, response latency, every tool call with its
 * duration and outcome, plans, warnings, errors and token use. Kept in memory for the Activity
 * screen and appended to a daily JSON-lines file in the app's private storage (files/traces), so a
 * run can be looked at and shared after the fact. Old days are deleted automatically (default:
 * kept 7 days; Activity screen: on/off and how many days), or by hand, one day or all.
 * Never holds secrets: callers pass summaries, and PINs are masked.
 */
object AgentTrace {

    private const val TAG = "AgentTrace"
    private const val MAX_IN_MEMORY = 600
    private const val PREFS = "agent_trace"
    const val DEFAULT_KEEP_DAYS = 7

    enum class Kind { SESSION, NETWORK, USER, REPLY, LATENCY, TOOL, PLAN, WARNING, ERROR, USAGE }

    data class Event(
        val time: Long,
        val kind: Kind,
        val text: String,
        // Duration of what the event measures (a tool call, a reply's first audio), if any
        val ms: Long? = null,
        // Outcome, for things that can fail
        val ok: Boolean? = null
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

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Trace files deleted automatically after keepDays (on by default). */
    data class Retention(val autoDelete: Boolean, val keepDays: Int)

    private val _retention = MutableStateFlow(Retention(true, DEFAULT_KEEP_DAYS))
    val retention: StateFlow<Retention> = _retention.asStateFlow()

    /** One day's trace file. */
    data class TraceFile(val name: String, val day: String, val bytes: Long)

    private val _files = MutableStateFlow<List<TraceFile>>(emptyList())
    val files: StateFlow<List<TraceFile>> = _files.asStateFlow()

    fun init(context: Context) {
        if (dir != null) return
        Tracer.init(context)
        dir = File(context.filesDir, "traces").apply { mkdirs() }
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            _retention.value = Retention(it.getBoolean("auto_delete", true), it.getInt("keep_days", DEFAULT_KEEP_DAYS))
        }
        writer.execute { prune(); refreshFiles() }
    }

    fun setRetention(autoDelete: Boolean, keepDays: Int) {
        val r = Retention(autoDelete, keepDays.coerceIn(1, 90))
        _retention.value = r
        prefs?.edit()?.putBoolean("auto_delete", r.autoDelete)?.putInt("keep_days", r.keepDays)?.apply()
        writer.execute { prune(); refreshFiles() }
    }

    /** Deletes days older than the retention (when auto-delete is on). Runs on the writer thread. */
    private fun prune() {
        val r = _retention.value
        if (!r.autoDelete) return
        val cutoff = System.currentTimeMillis() - r.keepDays * 86_400_000L
        dir?.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        Tracer.prune(r.keepDays)
    }

    private fun refreshFiles() {
        _files.value = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }?.sortedByDescending { it.name }
            ?.map { TraceFile(it.name, it.name.removeSuffix(".jsonl"), it.length()) }.orEmpty()
    }

    /** The events saved in one day's file (newest last). */
    fun readDay(name: String): List<Event> {
        val file = dir?.let { File(it, name) }?.takeIf { it.exists() } ?: return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Event(
                    time = o.getLong("t"),
                    kind = Kind.valueOf(o.getString("kind").uppercase()),
                    text = o.getString("text"),
                    ms = if (o.has("ms")) o.getLong("ms") else null,
                    ok = if (o.has("ok")) o.getBoolean("ok") else null
                )
            }.getOrNull()
        }
    }

    fun deleteDay(name: String) {
        writer.execute {
            dir?.let { File(it, name) }?.delete()
            Tracer.deleteDay(name)
            refreshFiles()
        }
    }

    /** Deletes every saved trace file and clears the live list. */
    fun deleteAll() {
        clear()
        Tracer.deleteAll()
        writer.execute {
            dir?.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            refreshFiles()
        }
    }

    fun log(kind: Kind, text: String, ms: Long? = null, ok: Boolean? = null) {
        val event = Event(System.currentTimeMillis(), kind, text.take(500), ms, ok)
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
        writer.execute {
            try {
                val line = JSONObject()
                    .put("t", event.time).put("kind", kind.name.lowercase()).put("text", event.text)
                    .apply { ms?.let { put("ms", it) }; ok?.let { put("ok", it) } }
                val file = File(folder, "${day.format(Date(event.time))}.jsonl")
                val isNew = !file.exists()
                file.appendText(line.toString() + "\n")
                if (isNew) { prune(); refreshFiles() }
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

    /** Events as readable text, to share (default: the ones in memory). */
    fun export(events: List<Event> = _events.value): String {
        val time = SimpleDateFormat("dd MMM HH:mm:ss.SSS", Locale.US)
        val m = metrics(events)
        val header = "Agent trace · ${m.toolCalls} tool calls (${m.toolFailures} failed), avg tool ${m.avgToolMs ?: "-"} ms, " +
            "avg reply latency ${m.avgLatencyMs ?: "-"} ms, ${m.reconnects} reconnects, ${m.errors} errors, ${m.tokens} tokens\n\n"
        return header + events.joinToString("\n") { e ->
            "${time.format(Date(e.time))}  ${e.kind.name.padEnd(8)} ${e.ms?.let { "[$it ms] " } ?: ""}" +
                "${if (e.ok == false) "FAILED " else ""}${e.text}"
        }
    }

    fun clear() {
        synchronized(this) { _events.value = emptyList() }
    }
}
