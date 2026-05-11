package com.hermes.intellij.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*


/**
 * Chat request for the Hermes API.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("tools")
    val tools: List<ToolDefinition>? = null
    // NOTE: tool_choice is intentionally omitted. Qwen3.5 and some other models
    // don't support this parameter. Tools are suggested but model decides whether to use them.
)

/**
 * Tool definition for function calling.
 * Follows OpenAI's tool declaration format.
 */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement
)
