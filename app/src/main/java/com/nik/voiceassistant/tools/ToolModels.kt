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

data class ToolCallResponse(
    @SerializedName("type") val type: String = "tool_response",
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String,
    @SerializedName("result") val result: Any?
)
