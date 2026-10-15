package com.nik.voiceassistant.data.macro

/**
 * Turns one live run of the AI into a saved macro. The AI performs the task as it normally would
 * (the same tool calls as any conversation); this only watches and keeps a copy of what it did.
 *
 * Earlier versions stopped recording at the AI's first spoken line ("Theek hai, pehle YouTube kholta
 * hoon...") said before it had done anything, saving zero steps every time. Recording now runs until
 * the goal reaches a clear end: update_plan reports "done" or "failed" (note(status)), or the
 * conversation ends on its own (finishOnConversationEnd()). A safety cap stops a run that never
 * reports either.
 */
object MacroRecorder {

    private const val MAX_STEPS = 40

    data class Session(
        val instruction: String,
        var title: String,
        val startedAt: Long = System.currentTimeMillis(),
        val steps: MutableList<RecordedStep> = mutableListOf(),
        // Re-recording an existing macro that failed to replay: its id is kept so this replaces it
        val replacingId: String? = null
    )

    @Volatile private var active: Session? = null

    fun isRecording(): Boolean = active != null

    fun activeInstruction(): String? = active?.instruction

    @Synchronized
    fun start(instruction: String, title: String? = null, replacingId: String? = null) {
        active = Session(instruction = instruction, title = title?.takeIf { it.isNotBlank() } ?: titleFrom(instruction), replacingId = replacingId)
    }

    @Synchronized
    fun cancel() {
        active = null
    }

    /** One tool call the AI made while recording (skips the purely informational ones). */
    @Synchronized
    fun recordStep(toolName: String, args: Map<String, Any?>, resultSummary: String) {
        val session = active ?: return
        if (toolName in SKIPPED_TOOLS) return
        if (session.steps.size >= MAX_STEPS) return // kept running so the goal can still finish; just stops recording more
        session.steps.add(
            RecordedStep(
                toolName = toolName,
                args = replayableArgs(toolName, args, resultSummary),
                description = describe(toolName, args),
                delayMs = delayFor(toolName)
            )
        )
    }

    /**
     * The model's plan reached a terminal status. Returns the finished macro on "done" with at least
     * one recorded step (nothing to save on "failed", or on an empty "done"), and clears the session
     * either way. Null means: not finished yet, keep recording.
     */
    @Synchronized
    fun note(status: String?): Macro? {
        if (active == null || status !in setOf("done", "failed")) return null
        return finish(success = status == "done")
    }

    /** The conversation ended (bye, idle, error) while still recording: finish with whatever ran. */
    @Synchronized
    fun finishOnConversationEnd(): Macro? = if (active != null) finish(success = true) else null

    private fun finish(success: Boolean): Macro? {
        val session = active ?: return null
        active = null
        if (!success || session.steps.isEmpty()) return null
        return Macro(
            id = session.replacingId ?: java.util.UUID.randomUUID().toString(),
            title = session.title,
            description = "Learned from: \"${session.instruction}\"",
            triggers = listOf(Trigger.Phrase(session.instruction)),
            instruction = session.instruction,
            action = Action.Replay(session.steps.toList())
        )
    }

    // ---- Helpers (no hardcoded per-task-type logic: every tool call is recorded the same way) ----

    // Pure information gathering: replaying these again on their own achieves nothing
    private val SKIPPED_TOOLS = setOf("read_screen", "look_at_screen", "show_choices", "update_plan", "research", "list_apps", "find_contact", "wait")

    private fun titleFrom(instruction: String): String {
        val words = instruction.trim().split(Regex("\\s+"))
        val clean = if (words.size <= 5) instruction.trim() else words.take(5).joinToString(" ")
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /** Args as replayed later: an element_id only means something on the screen it came from (see MacroEngine.replay). */
    private fun replayableArgs(toolName: String, args: Map<String, Any?>, resultSummary: String): Map<String, Any?> {
        if (toolName != "tap") return args.filterKeys { it != "element_id" || args["text"] != null }
        if (args["text"] != null) return args.filterKeys { it != "element_id" }
        // Fall back to the label MacroEngine.replay can parse from the summary line it already has
        return args
    }

    private fun describe(toolName: String, args: Map<String, Any?>): String = when (toolName) {
        "open_app" -> "Open ${args["app_name"] ?: "app"}"
        "tap" -> "Tap ${args["text"]?.let { "'$it'" } ?: "screen element"}"
        "type_text" -> "Type \"${args["text"] ?: ""}\""
        "scroll" -> "Scroll ${args["direction"] ?: ""}"
        "swipe" -> "Swipe ${args["direction"] ?: ""}"
        "set_volume" -> "Set volume"
        "launch_intent" -> "Open ${args["action"]?.toString()?.substringAfterLast('.') ?: args["uri"] ?: "system screen"}"
        "navigate" -> "Navigate ${args["action"] ?: ""}"
        "make_call" -> "Call ${args["number"] ?: ""}"
        "open_website" -> "Open ${args["url_or_search"] ?: "website"}"
        "toggle_flashlight" -> "Toggle flashlight"
        "toggle_bluetooth" -> "Toggle Bluetooth"
        "media_control" -> "Media: ${args["action"] ?: ""}"
        else -> "Run $toolName"
    }

    private fun delayFor(toolName: String): Long = when (toolName) {
        "open_app", "launch_intent", "open_website" -> 1600L
        "type_text" -> 1000L
        "tap", "scroll", "swipe" -> 800L
        else -> 600L
    }
}
