package com.nik.voiceassistant.tools

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * Data structures for tool calling JSON messages between AI Server and Client.
 */
data class ToolCallRequest(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: Map<String, Any?>? = emptyMap()
)

enum class StepStatus { PENDING, RUNNING, VERIFYING, DONE, FAILED, SKIPPED }

data class PlanStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val expect: String = "",
    val status: StepStatus = StepStatus.PENDING,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val attempts: Int = 0,
    val evidence: String? = null,
    val thumbId: String? = null,
    val background: Boolean = false
)

data class PlanEvent(
    val at: Long,
    val kind: String,
    val text: String
)

/** The model's plan for a multi-step goal (update_plan), shown as live progress. */
data class TaskPlan(
    val goal: String,
    val steps: List<PlanStep>,
    val status: String,
    val revision: Int = 0,
    val note: String? = null,
    val events: List<PlanEvent> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val tried: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()),
    val blockedCount: Int = 0
) {
    /** Index (1-based) of the currently running step, for backward-compat display code. */
    val currentStep: Int
        get() {
            val idx = steps.indexOfFirst { it.status == StepStatus.RUNNING || it.status == StepStatus.VERIFYING }
            return if (idx < 0) steps.indexOfFirst { it.status == StepStatus.PENDING }.let { if (it < 0) steps.size else it + 1 } else idx + 1
        }
}

/**
 * Gson deserializer that handles both old (steps: List<String>) and new (steps: List<PlanStep>) wire formats.
 * Old format: {"steps":["Open Settings","Tap Wi-Fi"],"currentStep":1,...}
 * New format: {"steps":[{"id":"s1","title":"Open Settings","status":"PENDING"}],...}
 */
class TaskPlanDeserializer : JsonDeserializer<TaskPlan> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): TaskPlan {
        val obj = json.asJsonObject
        val goal = obj.optString("goal")
        val status = obj.optString("status", "working")
        val note = obj.optStringOrNull("note")
        val revision = obj.optInt("revision", 0)
        val blockedCount = obj.optInt("blockedCount", 0)

        val steps: List<PlanStep> = parseSteps(obj, context)

        return TaskPlan(
            goal = goal,
            steps = steps,
            status = status,
            revision = revision,
            note = note,
            blockedCount = blockedCount
        )
    }

    private fun parseSteps(obj: JsonObject, context: JsonDeserializationContext): List<PlanStep> {
        val stepsElem = obj.get("steps") ?: return emptyList()
        if (!stepsElem.isJsonArray) return emptyList()
        val arr = stepsElem.asJsonArray
        if (arr.size() == 0) return emptyList()

        val first = arr.get(0)
        return if (first.isJsonObject) {
            // New format: [{id, title, status, ...}]
            arr.mapIndexed { idx, elem ->
                val s = elem.asJsonObject
                PlanStep(
                    id = s.optString("id", "s${idx + 1}"),
                    title = s.optString("title"),
                    detail = s.optString("detail", ""),
                    expect = s.optString("expect", ""),
                    status = runCatching { StepStatus.valueOf(s.optString("status", "PENDING").uppercase()) }.getOrDefault(StepStatus.PENDING),
                    attempts = s.optInt("attempts", 0),
                    evidence = s.optStringOrNull("evidence"),
                    background = s.optBoolean("background", false)
                )
            }
        } else {
            // Old format: ["Open Settings", "Tap Wi-Fi"] with optional currentStep
            val currentStep = obj.optInt("currentStep", 1)
            arr.mapIndexed { idx, elem ->
                val stepNum = idx + 1
                val st = when {
                    stepNum < currentStep -> StepStatus.DONE
                    stepNum == currentStep -> StepStatus.RUNNING
                    else -> StepStatus.PENDING
                }
                PlanStep(id = "s$stepNum", title = elem.asString.trim(), status = st)
            }
        }
    }

    private fun JsonObject.optString(key: String, default: String = "") =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: default

    private fun JsonObject.optStringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.optInt(key: String, default: Int = 0) =
        get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default

    private fun JsonObject.optBoolean(key: String, default: Boolean = false) =
        get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default
}

data class ToolCallResponse(
    @SerializedName("type") val type: String = "tool_response",
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String,
    @SerializedName("result") val result: Any?
)
