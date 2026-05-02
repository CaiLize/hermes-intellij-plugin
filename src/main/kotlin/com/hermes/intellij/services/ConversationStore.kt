package com.hermes.intellij.services

import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.model.Conversation
import com.hermes.intellij.model.ConversationSummary
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persistent storage for conversation list metadata.
 * Message content is serialized as raw JsonElement to support multimodal messages.
 */
@State(
    name = "HermesConversations",
    storages = [Storage("hermes-conversations.xml")]
)
@Service(Service.Level.PROJECT)
class ConversationStore(private val project: Project) : PersistentStateComponent<ConversationStore.State> {

    private val LOG = Logger.getInstance(ConversationStore::class.java)
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    data class ConversationEntry(
        var id: String = "",
        var title: String = "New Chat",
        var createdAt: Long = 0L,
        var updatedAt: Long = 0L,
        var messages: MutableList<String> = mutableListOf(),  // JSON strings of each message
        var hermesSessionId: String? = null  // Hermes server session ID (format: 20260501_130153_xxxxxx)
    )

    data class State(
        var conversations: MutableList<ConversationEntry> = mutableListOf(),
        var activeConversationId: String = ""
    )

    private var myState = State()

    // ============================================================
    // Conversation list management
    // ============================================================

    fun getConversationSummaries(): List<ConversationSummary> {
        return myState.conversations.map { entry ->
            ConversationSummary(
                id = entry.id,
                title = entry.title,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                messageCount = entry.messages.size
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun getActiveConversationId(): String? = myState.activeConversationId.ifBlank { null }

    fun createConversation(id: String): Conversation {
        val entry = ConversationEntry(
            id = id,
            title = "New Chat",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        myState.conversations.add(entry)
        myState.activeConversationId = id
        return entry.toConversation()
    }

    fun switchConversation(id: String): Conversation? {
        val entry = myState.conversations.find { it.id == id } ?: return null
        myState.activeConversationId = id
        return entry.toConversation()
    }

    fun deleteConversation(id: String): String? {
        val entry = myState.conversations.find { it.id == id }
        val hermesSessionId = entry?.hermesSessionId
        myState.conversations.removeIf { it.id == id }
        ImageStore.deleteConversationImages(project, id)
        if (myState.activeConversationId == id) {
            myState.activeConversationId = myState.conversations.firstOrNull()?.id ?: ""
        }
        return hermesSessionId
    }

    /**
     * Set the Hermes session ID for a conversation.
     */
    fun setHermesSessionId(conversationId: String, sessionId: String) {
        val entry = myState.conversations.find { it.id == conversationId }
        entry?.hermesSessionId = sessionId
    }

    /**
     * Get the Hermes session ID for a conversation.
     */
    fun getHermesSessionId(conversationId: String): String? {
        return myState.conversations.find { it.id == conversationId }?.hermesSessionId
    }

    fun getConversation(id: String): Conversation? {
        return myState.conversations.find { it.id == id }?.toConversation()
    }

    /**
     * Save messages back to the store entry for a conversation.
     * Processes image URLs: saves base64 data to disk, replaces with file references.
     */
    fun saveConversationMessages(id: String, messages: List<ChatMessage>) {
        val entry = myState.conversations.find { it.id == id } ?: return
        entry.updatedAt = System.currentTimeMillis()

        entry.messages.clear()
        for (msg in messages) {
            var msgJson = json.encodeToString(ChatMessage.serializer(), msg)
            // 将 base64 图片替换为磁盘文件引用，避免 XML 文件过大
            if (msg.contentParts != null) {
                msgJson = ImageStore.processMessageImages(project, id, msgJson)
            }
            entry.messages.add(msgJson)
        }
    }

    fun updateTitle(id: String, title: String) {
        val entry = myState.conversations.find { it.id == id } ?: return
        entry.title = title
    }

    // ============================================================
    // PersistentStateComponent
    // ============================================================

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun ConversationEntry.toConversation(): Conversation {
        val msgs = mutableListOf<JsonElement>()
        for (msgStr in messages) {
            try {
                val element = json.parseToJsonElement(msgStr)
                // Restore image file references
                val restored = restoreImageReferences(element, id)
                msgs.add(restored)
            } catch (e: Exception) {
                LOG.warn("[ConversationStore] Failed to parse message: ${e.message}")
            }
        }
        return Conversation(id, title, createdAt, updatedAt, msgs)
    }

    /**
     * Replace "hermes-image:filename" back to actual base64 data URLs.
     */
    private fun restoreImageReferences(element: JsonElement, conversationId: String): JsonElement {
        return try {
            val obj = element.jsonObject
            val content = obj["content"] ?: return element

            if (content is kotlinx.serialization.json.JsonArray) {
                val restoredParts = content.map { part ->
                    val partObj = part.jsonObject
                    val imageUrl = partObj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    if (imageUrl != null && imageUrl.startsWith("hermes-image:")) {
                        val fileName = imageUrl.substringAfter("hermes-image:")
                        val dataUrl = ImageStore.loadImage(project, conversationId, fileName)
                        if (dataUrl != null) {
                            kotlinx.serialization.json.buildJsonObject {
                                for ((key, value) in partObj) {
                                    if (key == "image_url") {
                                        put(key, kotlinx.serialization.json.buildJsonObject {
                                            for ((ik, iv) in value.jsonObject) {
                                                if (ik == "url") {
                                                    put(ik, kotlinx.serialization.json.JsonPrimitive(dataUrl))
                                                } else {
                                                    put(ik, iv)
                                                }
                                            }
                                        })
                                    } else {
                                        put(key, value)
                                    }
                                }
                            }
                        } else {
                            // Image file not found — mark as expired
                            kotlinx.serialization.json.buildJsonObject {
                                for ((key, value) in partObj) {
                                    if (key == "image_url") {
                                        put(key, kotlinx.serialization.json.buildJsonObject {
                                            for ((ik, iv) in value.jsonObject) {
                                                if (ik == "url") {
                                                    put(ik, kotlinx.serialization.json.JsonPrimitive("data:image/png;base64,"))
                                                } else {
                                                    put(ik, iv)
                                                }
                                            }
                                        })
                                    } else {
                                        put(key, value)
                                    }
                                }
                            }
                        }
                    } else {
                        part
                    }
                }
                kotlinx.serialization.json.buildJsonObject {
                    for ((key, value) in obj) {
                        if (key == "content") {
                            put(key, kotlinx.serialization.json.JsonArray(restoredParts))
                        } else {
                            put(key, value)
                        }
                    }
                }
            } else {
                element
            }
        } catch (e: Exception) {
            // Not a valid JSON object or content is not an array — return as-is
            element
        }
    }

    companion object {
        fun getInstance(project: Project): ConversationStore =
            project.getService(ConversationStore::class.java)
    }
}
