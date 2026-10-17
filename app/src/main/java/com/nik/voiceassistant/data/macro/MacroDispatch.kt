package com.nik.voiceassistant.data.macro

import android.content.Context
import android.util.Log
import com.nik.voiceassistant.tools.DeviceToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs a macro a sensor trigger matched (notification, call, charger, Wi-Fi…), shared by every
 * listener so none of them duplicates this. A Replay macro runs off the AI entirely (fast, offline).
 * One with nothing recorded yet (AlwaysAsk, or an empty Replay) needs a live AI turn, which only
 * VoiceSession can drive (it owns the model connection and the UI); onAgentGoalRequested is how a
 * background listener hands that off without depending on VoiceSession directly.
 */
object MacroDispatch {
    private const val TAG = "MacroDispatch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set once by VoiceSession. Runs `instruction` live (the same as the user having said it). */
    @Volatile var onAgentGoalRequested: ((instruction: String) -> Unit)? = null

    /** Set once by VoiceSession. Starts an outgoing call for a HandleCall macro triggered by a sensor/timer. */
    @Volatile var onCallGoalRequested: ((macro: Macro) -> Unit)? = null

    // A burst of events for the same macro (several notifications from one app, a flaky call-state
    // callback firing twice) only runs it once
    private val lastRunAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val DEBOUNCE_MS = 10_000L

    /** True once per macro per DEBOUNCE_MS window; false (and does nothing else) the rest of the time. */
    private fun shouldRun(macroId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastRunAt.put(macroId, now) ?: 0L
        return now - last >= DEBOUNCE_MS
    }

    fun run(context: Context, macro: Macro, executor: DeviceToolExecutor, triggerData: Map<String, String> = emptyMap()) {
        if (!shouldRun(macro.id)) return
        val instruction = if (triggerData.isEmpty()) macro.instruction else buildString {
            appendLine(macro.instruction)
            appendLine()
            appendLine("--- Trigger context ---")
            triggerData.forEach { (k, v) -> appendLine("$k: $v") }
        }
        when (val action = macro.action) {
            is Action.Replay -> if (action.steps.isNotEmpty()) {
                scope.launch {
                    val result = MacroEngine.replay(context, macro, executor)
                    if (!result.ok) {
                        Log.i(TAG, "Replay of a triggered macro failed; asking the AI to do it live")
                        askAi(instruction)
                    }
                }
            } else askAi(instruction)
            Action.AlwaysAsk -> askAi(instruction)
            // A call macro isn't run through a tool executor at all: the call subsystem handles it directly
            is Action.HandleCall -> {
                val handler = onCallGoalRequested
                if (handler != null) {
                    handler(macro)
                } else {
                    Log.i(TAG, "HandleCall macro matched outside a call context and no handler registered; ignoring")
                }
            }
        }
    }

    private fun askAi(instruction: String) {
        val handler = onAgentGoalRequested
        if (handler == null) {
            Log.w(TAG, "A macro needs a live AI run but nothing is listening (app not running)")
            return
        }
        handler(instruction)
    }
}
