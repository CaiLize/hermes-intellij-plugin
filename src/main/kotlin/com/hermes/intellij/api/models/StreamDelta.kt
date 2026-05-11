package com.hermes.intellij.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamDelta(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall? = null,
    // For incremental tool call parsing
    val index: Int? = null
)

@Serializable
data class FunctionCall(
    val name: String = "",
    val arguments: String = ""
)
