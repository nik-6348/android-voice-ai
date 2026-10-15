package com.nik.voiceassistant.tools

import android.util.Log
import com.google.gson.GsonBuilder
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.data.trace.Tracer
import com.nik.voiceassistant.network.LiveClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the model's tool calls for either transport: one at a time on their own thread (screen
 * actions wait for the UI to settle, which must not stall the socket), with live status for the UI,
 * a trace of each call, and an optional step just before the result goes back (a screen image).
 */
class ToolCallRunner(
    private val executor: DeviceToolExecutor,
    private val listener: LiveClient.Callback,
    // (tool name, result) -> called on the tool thread right before the result is sent
    private val beforeRespond: (String, Map<String, Any?>) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "ToolCallRunner"
    }

    private val thread = Executors.newSingleThreadExecutor()
    private val running = AtomicInteger(0)
    private val json = GsonBuilder().disableHtmlEscaping().create()

    /** The span of the call running now (screen images sent for it are recorded on it). */
    @Volatile var currentSpan: Tracer.Span? = null
        private set

    fun isRunning(): Boolean = running.get() > 0

    /** Runs one call; respond(result) is called with what to send back to the model. */
    fun submit(name: String, args: Map<String, Any?>, callId: String? = null, respond: (Map<String, Any?>) -> Unit) {
        running.incrementAndGet()
        thread.execute {
            // Never record a PIN
            val shownArgs = args.mapValues { (k, v) -> if (k == "pin") "***" else v }
            val span = Tracer.startSpan(
                "execute_tool $name", Tracer.Kind.TOOL,
                mapOf(
                    "gen_ai.operation.name" to "execute_tool",
                    "gen_ai.tool.name" to name,
                    "gen_ai.tool.call.id" to callId,
                    "gen_ai.tool.call.arguments" to json.toJson(shownArgs)
                )
            )
            currentSpan = span
            try {
                listener.onToolStart(name, statusText(name, args))
                val started = System.currentTimeMillis()
                val result = try {
                    executor.execute(name, args)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Execution failed"))
                }
                val ms = System.currentTimeMillis() - started
                // The screen listing is for the model; the transcript and trace get a one-line summary
                val summary = summary(result)
                val failed = result["error"] != null || result["success"] == false
                AgentTrace.log(AgentTrace.Kind.TOOL, "$name $shownArgs → $summary", ms, ok = !failed)
                (result["progress_warning"] as? String)?.let {
                    AgentTrace.log(AgentTrace.Kind.WARNING, it)
                    Tracer.event("progress_warning", mapOf("message" to it, "untried_approaches" to result["untried_approaches"]), span)
                }
                listener.onToolExecuted(name, args, summary)
                runCatching { beforeRespond(name, result) }.onFailure { Log.w(TAG, "Before-respond step failed: ${it.message}") }
                Tracer.end(
                    span, if (failed) Tracer.Status.ERROR else Tracer.Status.OK, (result["error"] as? String),
                    mapOf("gen_ai.tool.call.result" to json.toJson(result), "agent.tool.duration_ms" to ms)
                )
                respond(result)
            } catch (e: Exception) {
                Tracer.end(span, Tracer.Status.ERROR, e.message)
                throw e
            } finally {
                currentSpan = null
                running.decrementAndGet()
            }
        }
    }

    fun summary(result: Map<String, Any?>): String =
        (result["error"] ?: result["done"] ?: result["opened"] ?: result["launched"] ?: result["found"]?.let { "found $it" }
            ?: result["app"]?.let { "screen of $it" } ?: result.filterKeys { it != "screen" && it != "apps" }).toString().take(160)

    /** Short "what I'm doing" line for the UI, from the call itself. */
    fun statusText(name: String, args: Map<String, Any?>): String = when (name) {
        "scroll" -> args["until_text"]?.let { "Looking for \"$it\"…" } ?: "Scrolling ${args["direction"] ?: "down"}…"
        "tap" -> "Tapping ${args["text"]?.let { "\"$it\"" } ?: "on screen"}…"
        "type_text" -> "Typing \"${args["text"] ?: ""}\"…"
        "read_screen" -> "Reading the screen…"
        "look_at_screen" -> "Looking at the screen…"
        "open_app" -> "Opening ${args["app_name"] ?: "app"}…"
        "launch_intent" -> "Opening ${args["uri"] ?: args["action"]?.toString()?.substringAfterLast('.')?.lowercase()?.replace('_', ' ') ?: args["package"] ?: "page"}…"
        "open_website" -> "Opening ${args["url_or_search"] ?: "website"}…"
        "navigate" -> "Going ${args["action"]?.toString()?.replace('_', ' ') ?: "back"}…"
        "find_contact" -> "Finding ${args["name"] ?: "contact"}…"
        "make_call" -> "Calling…"
        "research" -> "Researching: ${args["question"] ?: ""}…"
        "update_plan" -> "Planning: ${args["goal"] ?: ""}"
        "wait" -> "Waiting for the screen…"
        else -> "${name.replace('_', ' ').replaceFirstChar { it.uppercase() }}…"
    }

    /** A call's JSON arguments as plain values (arrays and objects stay JSON text). */
    fun parseArgs(element: com.google.gson.JsonElement?): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return out
        for (key in obj.keySet()) {
            val e = obj.get(key)
            out[key] = when {
                e.isJsonNull -> null
                e.isJsonPrimitive && e.asJsonPrimitive.isBoolean -> e.asBoolean
                e.isJsonPrimitive && e.asJsonPrimitive.isNumber -> e.asNumber
                e.isJsonPrimitive && e.asJsonPrimitive.isString -> e.asString
                else -> e.toString()
            }
        }
        return out
    }
}
