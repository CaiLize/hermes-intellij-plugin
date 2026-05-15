package com.hermes.intellij.api

import com.hermes.intellij.api.models.Delta
import com.hermes.intellij.api.models.FunctionCall
import com.hermes.intellij.api.models.StreamDelta
import com.hermes.intellij.api.models.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Parses Server-Sent Events (SSE) text/event-stream lines into StreamDelta objects.
 * Handles: data lines, [DONE] signal, comment lines (including tool calls), blank lines.
 * Also supports Hermes-specific event: hermes.tool.progress for tool call lifecycle.
 */
object SseStreamParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transforms a flow of raw SSE lines into a flow of StreamDelta objects.
     * Handles: data lines, [DONE] signal, comment lines, blank lines.
     * Also parses tool_call events from SSE comment lines (format: ": tool_call {...}")
     * and Hermes-specific hermes.tool.progress events (format: "event: hermes.tool.progress").
     * 
     * FIX (BUG-004): Added explicit state cleanup in finally block for better code clarity
     * and to handle edge cases where the flow might be cancelled unexpectedly.
     */
    fun parse(lines: Flow<String>): Flow<StreamDelta> = flow {
        val dataBuffer = StringBuilder()
        var currentEventType: String? = null
        // Track pending tool calls from hermes.tool.progress events (per-stream state)
        val pendingHermesToolCalls = mutableMapOf<String, String>() // toolCallId -> toolName

        try {
            lines.collect { line ->
                when {
                    line.startsWith("data: [DONE]") || line.startsWith("data:[DONE]") -> {
                        return@collect
                    }

                    // Track SSE event type for Hermes-specific events
                    line.startsWith("event: ") -> {
                        currentEventType = line.removePrefix("event: ").trim()
                    }

                    // Parse tool_call events from SSE comment lines (legacy format)
                    line.startsWith(": tool_call ") -> {
                        try {
                            val toolCallJson = line.removePrefix(": tool_call ").trim()
                            val toolCallData = json.decodeFromString<ToolCallData>(toolCallJson)
                            // Emit a StreamDelta with tool_calls
                            val toolCall = ToolCall(
                                id = toolCallData.id ?: "",
                                type = "function",
                                function = FunctionCall(
                                    name = toolCallData.name,
                                    arguments = toolCallData.arguments ?: ""
                                ),
                                index = toolCallData.index
                            )
                            emit(StreamDelta(
                                id = "",
                                choices = listOf(com.hermes.intellij.api.models.StreamChoice(
                                    index = toolCallData.index ?: 0,
                                    delta = Delta(toolCalls = listOf(toolCall))
                                ))
                            ))
                        } catch (e: Exception) {
                            // Skip malformed tool_call events
                        }
                    }

                    // Parse Hermes-specific hermes.tool.progress events
                    currentEventType == "hermes.tool.progress" && line.startsWith("data: ") -> {
                        try {
                            val payload = line.removePrefix("data: ").trim()
                            val progressData = json.decodeFromString<HermesToolProgressData>(payload)
                            
                            // Only emit tool call delta on "running" status
                            if (progressData.status == "running" && progressData.tool.isNotEmpty()) {
                                // Track the tool call for later completion
                                if (progressData.toolCallId.isNotEmpty()) {
                                    pendingHermesToolCalls[progressData.toolCallId] = progressData.tool
                                }
                                
                                // Emit a StreamDelta with tool_calls to trigger UI
                                val toolCall = ToolCall(
                                    id = progressData.toolCallId,
                                    type = "function",
                                    function = FunctionCall(
                                        name = progressData.tool,
                                        arguments = ""
                                    ),
                                    index = null
                                )
                                emit(StreamDelta(
                                    id = "",
                                    choices = listOf(com.hermes.intellij.api.models.StreamChoice(
                                        index = 0,
                                        delta = Delta(toolCalls = listOf(toolCall))
                                    ))
                                ))
                            } else if (progressData.status == "completed" && progressData.toolCallId.isNotEmpty()) {
                                // Tool completed - remove from pending
                                pendingHermesToolCalls.remove(progressData.toolCallId)
                            }
                        } catch (e: Exception) {
                            // Skip malformed hermes.tool.progress events
                        }
                        // Reset event type after processing
                        currentEventType = null
                    }

                    line.startsWith("data: ") -> {
                        val payload = line.removePrefix("data: ").trim()
                        if (dataBuffer.isNotEmpty()) {
                            dataBuffer.append(payload)
                        } else {
                            dataBuffer.append(payload)
                        }
                    }

                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        dataBuffer.append(payload)
                    }

                    line.isBlank() -> {
                        // Blank line signals end of an event
                        if (dataBuffer.isNotEmpty()) {
                            val data = dataBuffer.toString()
                            dataBuffer.clear()
                            try {
                                val delta = json.decodeFromString<StreamDelta>(data)
                                emit(delta)
                            } catch (_: Exception) {
                                // Skip malformed data, continue streaming
                            }
                        }
                        // Reset event type after blank line
                        currentEventType = null
                    }

                    line.startsWith(":") -> {
                        // SSE comment, ignore (unless it's a tool_call which is handled above)
                    }

                    // Ignore event:, id:, retry: lines (event: is handled above)
                    else -> {}
                }
            }

            // Flush any remaining buffered data
            if (dataBuffer.isNotEmpty()) {
                val data = dataBuffer.toString()
                try {
                    val delta = json.decodeFromString<StreamDelta>(data)
                    emit(delta)
                } catch (_: Exception) {
                    // Skip malformed trailing data
                }
            }
        } finally {
            // FIX (BUG-004): Explicit state cleanup for better code clarity
            // and to handle edge cases where the flow might be cancelled unexpectedly.
            pendingHermesToolCalls.clear()
            dataBuffer.clear()
            currentEventType = null
        }
    }
}

/**
 * Data class for parsing tool_call events from SSE comment lines.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("tool_call")
data class ToolCallData(
    val id: String? = null,
    val name: String,
    val arguments: String? = null,
    val index: Int? = null
)

/**
 * Data class for parsing Hermes-specific hermes.tool.progress events.
 * Format: {"tool": "web_search", "emoji": "🔍", "label": "...", "toolCallId": "...", "status": "running"}
 */
@kotlinx.serialization.Serializable
data class HermesToolProgressData(
    val tool: String = "",
    val emoji: String? = null,
    val label: String? = null,
    val toolCallId: String = "",
    val status: String = "" // "running" or "completed"
)
