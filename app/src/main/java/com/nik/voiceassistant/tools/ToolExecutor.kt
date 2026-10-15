package com.nik.voiceassistant.tools

/**
 * Interface for executing tool calls from Gemini Live / LLM transports.
 * Implemented by DeviceToolExecutor and CallAgentSession's minimal call tool runner.
 */
interface ToolExecutor {
    fun execute(toolName: String, arguments: Map<String, Any?>?, trusted: Boolean = false): Map<String, Any?>
}
