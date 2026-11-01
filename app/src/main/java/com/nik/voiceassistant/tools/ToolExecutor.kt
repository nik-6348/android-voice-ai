package com.nik.voiceassistant.tools

/**
 * Interface for executing tool calls from Gemini Live / LLM transports.
 * Implemented by DeviceToolExecutor.
 */
interface ToolExecutor {
    fun execute(toolName: String, arguments: Map<String, Any?>?, trusted: Boolean = false): Map<String, Any?>
}
