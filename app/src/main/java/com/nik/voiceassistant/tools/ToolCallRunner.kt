package com.nik.voiceassistant.tools

import android.util.Log
import com.google.gson.GsonBuilder
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.data.trace.Tracer
import com.nik.voiceassistant.network.LiveClient
import com.nik.voiceassistant.security.AgentPolicy
import com.nik.voiceassistant.security.Redactor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the model's tool calls for either transport: one at a time on their own thread (screen
 * actions wait for the UI to settle, which must not stall the socket), with live status for the UI,
 * a trace of each call, and an optional step just before the result goes back (a screen image).
 */
class ToolCallRunner(
    private val executor: ToolExecutor,
    private val listener: LiveClient.Callback,
    // (tool name, result) -> called on the tool thread right before the result is sent
    private val beforeRespond: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    // blocks the tool thread until the assistant has finished speaking (before acting tools)
    private val waitForAudio: () -> Unit = {}
) {
    companion object {
        private const val TAG = "ToolCallRunner"
        // Result parts that hold what was on screen or in the phone's lists
        private val SCREEN_KEYS = setOf("screen", "elements", "apps", "contacts", "fields", "matches", "description")
        // Tools that physically act on the phone: wait for the current speech to finish first so the
        // voice isn't still talking when the UI already moved.
        private val ACTING = setOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "navigate",
            "open_app", "launch_intent", "open_website", "navigate_back",
            "unlock_phone", "make_call", "send_sms", "toggle_flashlight",
            "toggle_bluetooth", "media_control", "system_action", "search_settings"
        )
    }

    private val thread = Executors.newSingleThreadExecutor()
    private val running = AtomicInteger(0)
    private val json = GsonBuilder().disableHtmlEscaping().create()

    /** The span of the call running now (screen images sent for it are recorded on it). */
    @Volatile var currentSpan: Tracer.Span? = null
        private set

    fun isRunning(): Boolean = running.get() > 0

    /**
     * Runs one call; respond(result) is called with what to send back to the model, always exactly
     * once (the model waits for every tool response before it speaks again).
     */
    fun submit(name: String, args: Map<String, Any?>, callId: String? = null, respond: (Map<String, Any?>) -> Unit) {
        running.incrementAndGet()
        thread.execute {
            val span = Tracer.startSpan(
                "execute_tool $name", Tracer.Kind.TOOL,
                mapOf(
                    "gen_ai.operation.name" to "execute_tool",
                    "gen_ai.tool.name" to name,
                    "gen_ai.tool.call.id" to callId,
                    "gen_ai.tool.call.arguments" to json.toJson(Redactor.args(args))
                )
            )
            currentSpan = span
            var result: Map<String, Any?> = mapOf("error" to "Execution failed")
            try {
                // Wait for speech to finish before physically acting on the screen/phone, so the
                // voice doesn't keep talking while the UI has already moved to the next screen.
                if (name in ACTING) waitForAudio()
                listener.onToolStart(name, statusText(name, args))
                val started = System.currentTimeMillis()
                result = try {
                    executor.execute(name, args)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Execution failed"))
                }
                val ms = System.currentTimeMillis() - started
                // What is stored (logs, history, traces, automations): no PIN, no codes, no password text
                val storedArgs = Redactor.args(args, sensitive = result["sensitive"] == true)
                // The screen listing is for the model; the transcript and trace get a one-line summary
                val summary = Redactor.forStorage(summary(result))
                val failed = result["error"] != null || result["success"] == false
                AgentTrace.log(AgentTrace.Kind.TOOL, "$name $storedArgs → $summary", ms, ok = !failed)
                (result["progress_warning"] as? String)?.let {
                    AgentTrace.log(AgentTrace.Kind.WARNING, it)
                    Tracer.event("progress_warning", mapOf("message" to it, "untried_approaches" to result["untried_approaches"]), span)
                }
                listener.onToolExecuted(name, storedArgs, summary)
                runCatching { beforeRespond(name, result) }.onFailure { Log.w(TAG, "Before-respond step failed: ${it.message}") }
                Tracer.end(
                    span, if (failed) Tracer.Status.ERROR else Tracer.Status.OK, (result["error"] as? String),
                    mapOf("gen_ai.tool.call.result" to Redactor.forStorage(json.toJson(storable(result))), "agent.tool.duration_ms" to ms)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Tool $name failed around its execution: ${e.javaClass.simpleName}")
                Tracer.end(span, Tracer.Status.ERROR, e.message)
            } finally {
                currentSpan = null
                running.decrementAndGet()
                try {
                    respond(result)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't send the result of $name: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** A result as it may be kept in traces: the screen contents only when the user allows it. */
    private fun storable(result: Map<String, Any?>): Map<String, Any?> =
        if (AgentPolicy.current.logScreenContents) result
        else result.filterKeys { it !in SCREEN_KEYS } + (if (result.keys.any { it in SCREEN_KEYS }) mapOf("screen" to "(not kept: Settings > Logs)") else emptyMap())

    fun summary(result: Map<String, Any?>): String =
        (result["error"] ?: result["done"] ?: result["opened"] ?: result["launched"] ?: result["found"]?.let { "found $it" }
            ?: result["app"]?.let { "screen of $it" } ?: result.filterKeys { it != "screen" && it != "apps" }).toString().take(160)

    /** Short "what I'm doing" line for the UI, from the call itself. */
    fun statusText(name: String, args: Map<String, Any?>): String = when (name) {
        "scroll" -> args["until_text"]?.let { "Looking for \"$it\"…" } ?: "Scrolling ${args["direction"] ?: "down"}…"
        "tap" -> "Tapping ${args["text"]?.let { "\"$it\"" } ?: "on screen"}…"
        "type_text" -> "Typing \"${Redactor.mask(args["text"]?.toString().orEmpty()).take(40)}\"…"
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
        "search_settings" -> "Searching Settings for \"${args["query"] ?: ""}\"…"
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
