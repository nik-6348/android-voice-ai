package com.nik.voiceassistant.data.automation

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nik.voiceassistant.tools.DeviceToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class AutomationStep(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val args: Map<String, Any?> = emptyMap(),
    val description: String = "",
    val delayMs: Long = 800L
)

data class AutomationTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val triggerPhrase: String,
    val iconName: String = "bolt", // bolt, alarm, music, phone, check
    val createdAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long? = null,
    var runCount: Int = 0,
    var isFavorite: Boolean = false,
    val steps: List<AutomationStep> = emptyList()
)

object AutomationManager {
    private const val TAG = "AutomationManager"
    private const val FILE_NAME = "automations.json"
    private val gson = Gson()

    // The built-in examples older versions shipped (and saved to the file): removed on load
    private const val DEFAULT_ID_PREFIX = "default_"

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The saved automations (empty if none, or if the file can't be read). */
    @Synchronized
    fun getAllTasks(context: Context): List<AutomationTask> = load(context).orEmpty()

    /** The saved automations, or null if the file exists but can't be read (then nothing may overwrite it). */
    @Synchronized
    private fun load(context: Context): List<AutomationTask>? {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return try {
            // An array type, not a TypeToken: its generic type survives R8 in release builds
            val loaded: List<AutomationTask> = gson.fromJson(file.readText(), Array<AutomationTask>::class.java)?.toList() ?: emptyList()
            val list = loaded.filterNot { it.id.startsWith(DEFAULT_ID_PREFIX) }
            if (list.size != loaded.size) saveAll(context, list)
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load automations: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    private fun saveAll(context: Context, tasks: List<AutomationTask>) {
        try {
            // Written to a temp file and renamed over the old one: a crash mid-write can't lose them
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(tasks))
            if (!tmp.renameTo(getFile(context))) {
                getFile(context).writeText(gson.toJson(tasks))
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write automations file: ${e.message}")
        }
    }

    @Synchronized
    fun saveTask(context: Context, task: AutomationTask) {
        val current = load(context)?.toMutableList() ?: return
        val index = current.indexOfFirst { it.id == task.id }
        if (index >= 0) {
            current[index] = task
        } else {
            current.add(0, task)
        }
        saveAll(context, current)
    }

    @Synchronized
    fun deleteTask(context: Context, id: String) {
        val current = load(context)?.filterNot { it.id == id } ?: return
        saveAll(context, current)
    }

    @Synchronized
    fun toggleFavorite(context: Context, id: String) {
        val current = load(context)?.toMutableList() ?: return
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = current[index]
            current[index] = item.copy(isFavorite = !item.isFavorite)
            saveAll(context, current)
        }
    }

    /**
     * Runs an automation step by step via DeviceToolExecutor. The user started it, which counts as
     * asking for its calls / messages; a payment or wipe step still stops for their yes. Stops at the
     * first step that fails and says which.
     */
    suspend fun executeTask(
        context: Context,
        task: AutomationTask,
        executor: DeviceToolExecutor,
        onStepProgress: (stepIndex: Int, total: Int, desc: String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (task.steps.isEmpty()) return@withContext Pair(false, "No steps defined in this automation.")

        val total = task.steps.size
        var failure: String? = null
        for ((index, step) in task.steps.withIndex()) {
            val desc = step.description.ifBlank { "Executing ${step.toolName}..." }
            withContext(Dispatchers.Main) { onStepProgress(index + 1, total, desc) }
            val result = try {
                executor.execute(step.toolName, step.args, trusted = true)
            } catch (e: Exception) {
                mapOf("error" to (e.message ?: "failed"))
            }
            val error = result["error"]?.toString()
            if (result["needs_confirmation"] != null) {
                failure = "Step ${index + 1} ($desc) needs your OK: say yes, then run it again."
                break
            }
            if (error != null || result["success"] == false) {
                Log.w(TAG, "Step ${index + 1} (${step.toolName}) failed")
                failure = "Step ${index + 1} ($desc) failed: ${error ?: "it didn't work"}"
                break
            }
            if (step.delayMs > 0) kotlinx.coroutines.delay(step.delayMs)
        }

        // Update last run time & count
        task.lastRunAt = System.currentTimeMillis()
        task.runCount += 1
        saveTask(context, task)

        if (failure != null) Pair(false, failure) else Pair(true, "Automation '${task.title}' completed successfully!")
    }

    // ==========================================
    // ACTION RECORDER: Dynamically learns multi-step tasks from 1 instruction
    // ==========================================

    data class RecordingSession(
        val instruction: String,
        val triggerPhrase: String,
        val title: String,
        val startTime: Long = System.currentTimeMillis(),
        val steps: MutableList<AutomationStep> = mutableListOf()
    )

    @Volatile
    private var activeRecording: RecordingSession? = null

    @Synchronized
    fun startRecording(instruction: String, triggerPhrase: String? = null, title: String? = null) {
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: generateTitle(instruction)
        val trigger = triggerPhrase?.takeIf { it.isNotBlank() } ?: instruction
        activeRecording = RecordingSession(
            instruction = instruction,
            triggerPhrase = trigger,
            title = cleanTitle
        )
        Log.i(TAG, "Started recording an automation")
    }

    fun isRecording(): Boolean = activeRecording != null

    fun getActiveRecording(): RecordingSession? = activeRecording

    fun cancelRecording() {
        activeRecording = null
        Log.i(TAG, "Cancelled automation recording")
    }

    @Synchronized
    fun recordStep(toolName: String, args: Map<String, Any?>, resultSummary: String = "") {
        val session = activeRecording ?: return

        // Skip internal non-action tools
        if (toolName == "read_screen" || toolName == "show_choices") return

        val desc = when (toolName) {
            "open_app" -> "Open ${args["app_name"] ?: "App"}"
            "tap" -> "Tap ${args["text"]?.let { "'$it'" } ?: "screen element"}"
            "type_text" -> "Type \"${args["text"] ?: ""}\""
            "set_volume" -> "Set volume to ${args["level"] ?: "level"}"
            "set_wifi" -> "${if (args["enabled"] == true) "Turn on" else "Turn off"} Wi-Fi"
            "set_bluetooth" -> "${if (args["enabled"] == true) "Turn on" else "Turn off"} Bluetooth"
            "launch_intent" -> "Launch ${args["action"]?.toString()?.substringAfterLast('.') ?: "system intent"}"
            "press_key" -> "Press ${args["key"] ?: "key"}"
            "navigate" -> "Navigate ${args["action"] ?: "action"}"
            "device_status" -> "Check device battery & network"
            "make_call" -> "Call ${args["contact_name"] ?: args["phone_number"] ?: "contact"}"
            else -> "Execute $toolName"
        }

        val delay = when (toolName) {
            "open_app", "launch_intent" -> 1600L
            "type_text" -> 1000L
            "tap" -> 800L
            else -> 600L
        }

        // An element number only means something on the screen it was read from: replay by the
        // label that was tapped instead (from the summary: tapped "Search"), else the focused field
        val label = Regex("^(?:long-pressed|tapped) \"([^\"]+)\"").find(resultSummary)?.groupValues?.get(1)
        val replayArgs = args.filterKeys { it != "element_id" }.let { a ->
            if (toolName == "tap" && a["text"] == null && label != null) a + ("text" to label) else a
        }
        // A tap with neither left can't be replayed
        if (toolName == "tap" && replayArgs["text"] == null) return
        // Hidden password text can't be replayed either
        if (replayArgs["text"]?.toString()?.startsWith("***") == true) return

        val step = AutomationStep(
            toolName = toolName,
            args = replayArgs,
            description = desc,
            delayMs = delay
        )
        session.steps.add(step)
        Log.i(TAG, "Recorded automation step #${session.steps.size} ($toolName)")
    }

    @Synchronized
    fun finishRecording(context: Context, success: Boolean = true): AutomationTask? {
        val session = activeRecording ?: return null
        activeRecording = null

        if (!success || session.steps.isEmpty()) {
            Log.w(TAG, "Recording discarded (success=$success, steps=${session.steps.size})")
            return null
        }

        val icon = determineIcon(session.steps)
        val task = AutomationTask(
            title = session.title,
            description = "Auto-recorded from: \"${session.instruction}\"",
            triggerPhrase = session.triggerPhrase,
            iconName = icon,
            steps = session.steps.toList()
        )
        saveTask(context, task)
        Log.i(TAG, "Saved a recorded automation with ${task.steps.size} steps")
        return task
    }

    private fun determineIcon(steps: List<AutomationStep>): String {
        val names = steps.map { it.toolName }
        val allValues = steps.flatMap { it.args.values.map { v -> v.toString().lowercase() } }
        return when {
            names.contains("set_volume") || allValues.any { it.contains("music") || it.contains("youtube") || it.contains("spotify") || it.contains("song") } -> "music"
            allValues.any { it.contains("alarm") || it.contains("clock") || it.contains("timer") } -> "alarm"
            names.contains("make_call") || names.contains("find_contact") -> "phone"
            else -> "bolt"
        }
    }

    private fun generateTitle(instruction: String): String {
        val words = instruction.trim().split("\\s+".toRegex())
        val clean = if (words.size <= 4) instruction.trim() else words.take(4).joinToString(" ")
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * The saved automation this request asks for: the request is its trigger phrase (or title), give
     * or take a greeting in front and a couple of words after ("Subah ka alarm laga do"). A longer
     * request that merely contains it ("play music by Arijit on Spotify") goes to the assistant.
     */
    fun findMatchingTask(context: Context, query: String): AutomationTask? {
        val clean = normalize(query).replace(Regex("^(hi|hey|ok|hello)\\s+\\S+\\s+"), "")
        if (clean.isBlank()) return null
        fun matches(phrase: String): Boolean {
            val p = normalize(phrase)
            if (p.isBlank() || !clean.startsWith(p)) return false
            val rest = clean.removePrefix(p).trim()
            return rest.isEmpty() || rest.split(" ").size <= 2
        }
        val all = getAllTasks(context)
        return all.firstOrNull { matches(it.triggerPhrase) } ?: all.firstOrNull { matches(it.title) }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
}
