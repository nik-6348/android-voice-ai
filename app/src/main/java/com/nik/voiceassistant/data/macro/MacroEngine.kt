package com.nik.voiceassistant.data.macro

import android.content.Context
import android.util.Log
import com.nik.voiceassistant.tools.DeviceToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Matches triggers to saved macros and replays a macro's recorded steps. This is the "replay" half of
 * the record-once/replay-after design (see MacroRecorder): it never talks to the AI itself. The
 * caller (VoiceSession for phrases, the notification/call listeners for those triggers) is what falls
 * back to a fresh AI run — via MacroRecorder.start(instruction, replacingId = macro.id) and sending the
 * instruction to the AI the normal way — when replay fails, so the macro heals itself for next time.
 */
object MacroEngine {
    private const val TAG = "MacroEngine"

    data class ReplayResult(val ok: Boolean, val failedAtStep: Int = -1, val total: Int = 0, val message: String)

    /**
     * The saved macro this spoken/typed request means: its phrase trigger, give or take a greeting in
     * front and a couple of words after ("Subah ka alarm laga do" matches "Subah ka alarm"). Disabled
     * macros, and ones with no steps to replay, are skipped (nothing to run).
     */
    fun findByPhrase(context: Context, query: String): Macro? {
        val clean = normalize(query).replace(Regex("^(hi|hey|ok|hello)\\s+\\S+\\s+"), "")
        if (clean.isBlank()) return null
        fun matches(phrase: String): Boolean {
            val p = normalize(phrase)
            if (p.isBlank() || !clean.startsWith(p)) return false
            val rest = clean.removePrefix(p).trim()
            return rest.isEmpty() || rest.split(" ").size <= 2
        }
        val candidates = MacroStore.getAll(context).filter { it.enabled && it.action is Action.Replay && (it.action as Action.Replay).steps.isNotEmpty() }
        val byTrigger = candidates.firstOrNull { m -> m.triggers.any { it is Trigger.Phrase && matches(it.phrase) } }
        return byTrigger ?: candidates.firstOrNull { matches(it.title) }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Every enabled macro with at least one trigger the predicate accepts (for sensor-style triggers: notification, call, charger…). */
    fun matching(context: Context, predicate: (Trigger) -> Boolean): List<Macro> =
        MacroStore.getAll(context).filter { it.enabled && it.triggers.any(predicate) }

    /**
     * Replays a macro's recorded steps literally, one at a time, exactly like the AI performed them
     * originally. Stops at the first step that fails (an error, or `needs_confirmation` — a replay
     * never has the user's fresh yes, so that always needs a live AI run instead) or that the AI
     * itself flagged as risky and already asked about; see tools/DeviceToolExecutor's gate.
     * `trusted = true`: the user (or a trigger they set up) started this, which is enough for a call,
     * send or delete to go ahead without asking again; a payment or wipe step still stops for a yes.
     */
    suspend fun replay(
        context: Context,
        macro: Macro,
        executor: DeviceToolExecutor,
        onProgress: (step: Int, total: Int, description: String) -> Unit = { _, _, _ -> }
    ): ReplayResult = withContext(Dispatchers.IO) {
        val steps = (macro.action as? Action.Replay)?.steps.orEmpty()
        if (steps.isEmpty()) return@withContext ReplayResult(false, message = "Nothing recorded yet for \"${macro.title}\".")
        val total = steps.size
        for ((index, step) in steps.withIndex()) {
            withContext(Dispatchers.Main) { onProgress(index + 1, total, step.description.ifBlank { "Running ${step.toolName}…" }) }
            val result = try {
                executor.execute(step.toolName, step.args, trusted = true)
            } catch (e: Exception) {
                mapOf("error" to (e.message ?: "failed"))
            }
            if (result["needs_confirmation"] != null) {
                recordRun(context, macro.id, ok = false)
                return@withContext ReplayResult(false, index, total, "Step ${index + 1} (${step.description}) needs your OK; asking the AI to do it instead.")
            }
            val error = result["error"]?.toString()
            if (error != null || result["success"] == false) {
                Log.w(TAG, "Replay step ${index + 1}/${total} (${step.toolName}) failed for macro ${macro.id}")
                recordRun(context, macro.id, ok = false)
                return@withContext ReplayResult(false, index, total, "Step ${index + 1} (${step.description}) didn't work (the screen may have changed); asking the AI to do it instead.")
            }
            if (step.delayMs > 0) delay(step.delayMs)
        }
        recordRun(context, macro.id, ok = true)
        ReplayResult(true, total = total, message = "Done: \"${macro.title}\".")
    }

    private fun recordRun(context: Context, macroId: String, ok: Boolean) {
        MacroStore.update(context, macroId) { it.copy(lastRunAt = System.currentTimeMillis(), runCount = it.runCount + 1, lastRunOk = ok) }
    }

    /** Replaces a macro's action with a freshly recorded one (self-heal) or adds a brand-new macro. */
    fun saveRecorded(context: Context, recorded: Macro, existing: Macro? = null) {
        val merged = if (existing != null) existing.copy(action = recorded.action, instruction = recorded.instruction) else recorded
        MacroStore.save(context, merged)
    }
}
