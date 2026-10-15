package com.nik.voiceassistant.tools

import com.google.gson.annotations.SerializedName

/**
 * Data structures for tool calling JSON messages between AI Server and Client.
 */
data class ToolCallRequest(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: Map<String, Any?>? = emptyMap()
)

/** The model's plan for a multi-step goal (update_plan), shown as live progress. */
data class TaskPlan(
    val goal: String,
    val steps: List<String>,
    val currentStep: Int,
    // working, blocked, done or failed
    val status: String,
    val note: String?,
    // Tools used since the goal started; the approaches not tried yet are suggested when blocked
    val tried: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()),
    val blockedCount: Int = 0
)

data class ToolCallResponse(
    @SerializedName("type") val type: String = "tool_response",
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String,
    @SerializedName("result") val result: Any?
)
