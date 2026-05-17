package com.hermes.intellij.model

import com.hermes.intellij.api.models.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Represents a single conversation session with its metadata and message history.
 */
@Serializable
data class Conversation(
    val id: String,
    var title: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /**
     * Messages serialized as raw JSON elements to handle both plain text and multimodal formats.
     * This avoids issues with ChatMessage's custom serializer during PersistentStateComponent load/save.
     */
    val messages: MutableList<JsonElement> = mutableListOf()
) {
    companion object {
        /**
         * Generate a short title from the first user message.
         */
        fun generateTitle(firstMessage: String): String {
            // Strip code blocks and whitespace, take first meaningful line
            val cleaned = firstMessage
                .replace(Regex("```[\\s\\S]*?```"), "")
                .replace(Regex("\n\\s*\n"), "\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .firstOrNull() ?: "New Chat"
            return cleaned.take(40).let { if (cleaned.length > 40) "$it..." else it }
        }
    }

    /**
     * Update title if it's still the default or empty.
     */
    fun updateTitleIfNeeded(firstUserMessage: String) {
        if (title.isBlank() || title == "New Chat") {
            title = generateTitle(firstUserMessage)
        }
    }

    /**
     * Update the timestamp.
     */
    fun touch() {
        // updatedAt is val in constructor, so we use copy when needed
    }
}

/**
 * Lightweight conversation summary for the list UI (no messages loaded).
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)
