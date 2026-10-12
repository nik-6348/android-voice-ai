package com.nik.voiceassistant.data.automation

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    private val defaultAutomations = listOf(
        AutomationTask(
            id = "default_alarm_morning",
            title = "Morning 5:30 AM Alarm",
            description = "Directly sets a recurring morning alarm at 5:30 AM.",
            triggerPhrase = "Subah ka alarm",
            iconName = "alarm",
            isFavorite = true,
            steps = listOf(
                AutomationStep(
                    toolName = "launch_intent",
                    args = mapOf(
                        "action" to "android.intent.action.SET_ALARM",
                        "extras" to mapOf(
                            "android.intent.extra.alarm.HOUR" to 5,
                            "android.intent.extra.alarm.MINUTES" to 30,
                            "android.intent.extra.alarm.MESSAGE" to "Subah ka alarm",
                            "android.intent.extra.alarm.SKIP_UI" to true
                        )
                    ),
                    description = "Set 5:30 AM alarm with auto-confirm",
                    delayMs = 1000L
                )
            )
        ),
        AutomationTask(
            id = "default_youtube_lofi",
            title = "Play Relaxing Music",
            description = "Opens YouTube and searches for peaceful Lofi music.",
            triggerPhrase = "Play music",
            iconName = "music",
            isFavorite = true,
            steps = listOf(
                AutomationStep(
                    toolName = "open_app",
                    args = mapOf("app_name" to "YouTube"),
                    description = "Launch YouTube",
                    delayMs = 1500L
                ),
                AutomationStep(
                    toolName = "tap",
                    args = mapOf("text" to "Search"),
                    description = "Tap search icon",
                    delayMs = 800L
                ),
                AutomationStep(
                    toolName = "type_text",
                    args = mapOf("text" to "Lofi hip hop radio", "submit" to true),
                    description = "Type and search Lofi stream",
                    delayMs = 1200L
                )
            )
        ),
        AutomationTask(
            id = "default_device_check",
            title = "Quick Device Status",
            description = "Checks battery percentage, Wi-Fi, and active network.",
            triggerPhrase = "Phone status",
            iconName = "bolt",
            isFavorite = false,
            steps = listOf(
                AutomationStep(
                    toolName = "device_status",
                    args = emptyMap(),
                    description = "Read battery and network state",
                    delayMs = 500L
                )
            )
        )
    )

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun getAllTasks(context: Context): List<AutomationTask> {
        val file = getFile(context)
        if (!file.exists()) {
            saveAll(context, defaultAutomations)
            return defaultAutomations
        }
        return try {
            val type = object : TypeToken<List<AutomationTask>>() {}.type
            val list: List<AutomationTask> = gson.fromJson(file.readText(), type) ?: emptyList()
            if (list.isEmpty()) {
                saveAll(context, defaultAutomations)
                defaultAutomations
            } else {
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load automations: ${e.message}")
            defaultAutomations
        }
    }

    @Synchronized
    private fun saveAll(context: Context, tasks: List<AutomationTask>) {
        try {
            getFile(context).writeText(gson.toJson(tasks))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write automations file: ${e.message}")
        }
    }

    @Synchronized
    fun saveTask(context: Context, task: AutomationTask) {
        val current = getAllTasks(context).toMutableList()
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
        val current = getAllTasks(context).filterNot { it.id == id }
        saveAll(context, current)
    }

    @Synchronized
    fun toggleFavorite(context: Context, id: String) {
        val current = getAllTasks(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = current[index]
            current[index] = item.copy(isFavorite = !item.isFavorite)
            saveAll(context, current)
        }
    }

    /**
     * Executes an automation step by step via DeviceToolExecutor.
     */
    suspend fun executeTask(
        context: Context,
        task: AutomationTask,
        executor: DeviceToolExecutor,
        onStepProgress: (stepIndex: Int, total: Int, desc: String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (task.steps.isEmpty()) return@withContext Pair(false, "No steps defined in this automation.")

        val total = task.steps.size
        for ((index, step) in task.steps.withIndex()) {
            withContext(Dispatchers.Main) {
                onStepProgress(index + 1, total, step.description.ifBlank { "Executing ${step.toolName}..." })
            }
            try {
                val result = executor.execute(step.toolName, step.args)
                Log.i(TAG, "Executed step ${step.toolName}: $result")
                val error = result["error"]?.toString()
                if (error != null && !error.contains("already", ignoreCase = true)) {
                    Log.w(TAG, "Step ${step.toolName} reported warning/error: $error")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step ${step.toolName}: ${e.message}")
            }
            if (step.delayMs > 0) {
                kotlinx.coroutines.delay(step.delayMs)
            }
        }

        // Update last run time & count
        task.lastRunAt = System.currentTimeMillis()
        task.runCount += 1
        saveTask(context, task)

        Pair(true, "Automation '${task.title}' completed successfully!")
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
        Log.i(TAG, "Started recording automation session: '$cleanTitle' from instruction: '$instruction'")
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

        val step = AutomationStep(
            toolName = toolName,
            args = args,
            description = desc,
            delayMs = delay
        )
        session.steps.add(step)
        Log.i(TAG, "Recorded automation step #${session.steps.size}: $desc ($toolName)")
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
        Log.i(TAG, "Auto-saved automation task '${task.title}' with ${task.steps.size} steps!")
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
     * Checks if a user's prompt matches an existing saved automation.
     */
    fun findMatchingTask(context: Context, query: String): AutomationTask? {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return null
        val all = getAllTasks(context)
        return all.firstOrNull {
            it.triggerPhrase.isNotBlank() && clean.contains(it.triggerPhrase.lowercase())
        } ?: all.firstOrNull {
            clean.contains(it.title.lowercase())
        }
    }
}
