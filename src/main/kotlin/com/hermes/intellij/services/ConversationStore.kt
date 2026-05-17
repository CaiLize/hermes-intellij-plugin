package com.hermes.intellij.services

import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.model.Conversation
import com.hermes.intellij.model.ConversationSummary
import com.hermes.intellij.security.ValidationResult
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent storage for conversation list metadata.
 * Message content is serialized as raw JsonElement to support multimodal messages.
 *
 * Optimizations:
 * - Thread-safe with ConcurrentHashMap for active operations
 * - Improved error handling for image loading
 * - Lazy image loading support (returns reference instead of failing)
 *
 * Session ID semantics:
 * - Each conversation has its own hermesSessionId (null until first message)
 * - New conversations start with null, get assigned on first message send
 * - Continuing a conversation uses the same hermesSessionId
 *
 * FIX: Issue #5 - Added image integrity validation
 */
@State(
    name = "HermesConversations",
    storages = [Storage("hermes-conversations.xml")]
)
@Service(Service.Level.PROJECT)
class ConversationStore(private val project: Project) : PersistentStateComponent<ConversationStore.State> {

    private val LOG = Logger.getInstance(ConversationStore::class.java)
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private val stateLock = Any()
    private val conversationCache = ConcurrentHashMap<String, Conversation>()

    // FIX: Issue #5 - Image validation constants (moved to class-level instead of companion object to avoid conflict)
    private val maxImageSizeBytes = 10 * 1024 * 1024 // 10MB

    data class ConversationEntry(
        var id: String = "",
        var title: String = "New Chat",
        var createdAt: Long = 0L,
        var updatedAt: Long = 0L,
        var messages: MutableList<String> = mutableListOf(),
        var hermesSessionId: String? = null
    )

    data class State(
        var conversations: MutableList<ConversationEntry> = mutableListOf(),
        var activeConversationId: String = ""
    )

    private var myState = State()

    fun getConversationSummaries(): List<ConversationSummary> {
        synchronized(stateLock) {
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
    }

    fun getActiveConversationId(): String? = myState.activeConversationId.ifBlank { null }

    /**
     * Create a new conversation entry.
     *
     * @param id The unique conversation ID
     * @param hermesSessionId Initial session ID (usually null for new conversations)
     *                        The session ID will be assigned on first message send.
     */
    fun createConversation(id: String, hermesSessionId: String? = null): Conversation {
        synchronized(stateLock) {
            val entry = ConversationEntry(
                id = id,
                title = "New Chat",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                hermesSessionId = hermesSessionId
            )
            myState.conversations.add(entry)
            myState.activeConversationId = id
            val conversation = entry.toConversation()
            conversationCache[id] = conversation
            LOG.info("[ConversationStore] Created conversation $id (hermesSessionId: ${hermesSessionId ?: "null"})")
            return conversation
        }
    }

    fun switchConversation(id: String): Conversation? {
        synchronized(stateLock) {
            conversationCache[id]?.let {
                myState.activeConversationId = id
                return it
            }

            val entry = myState.conversations.find { it.id == id } ?: return null
            myState.activeConversationId = id
            val conversation = entry.toConversation()
            conversationCache[id] = conversation
            return conversation
        }
    }

    fun deleteConversation(id: String): String? {
        synchronized(stateLock) {
            val entry = myState.conversations.find { it.id == id }
            val hermesSessionId = entry?.hermesSessionId
            myState.conversations.removeIf { it.id == id }
            conversationCache.remove(id)
            ImageStore.deleteConversationImages(project, id)
            if (myState.activeConversationId == id) {
                myState.activeConversationId = myState.conversations.firstOrNull()?.id ?: ""
            }
            return hermesSessionId
        }
    }

    fun setHermesSessionId(conversationId: String, sessionId: String) {
        synchronized(stateLock) {
            val entry = myState.conversations.find { it.id == conversationId }
            if (entry != null) {
                entry.hermesSessionId = sessionId
                entry.updatedAt = System.currentTimeMillis()
                conversationCache.remove(conversationId)
                LOG.info("[ConversationStore] Saved Hermes session ID $sessionId for conversation $conversationId")
            }
        }
    }

    fun getHermesSessionId(conversationId: String): String? {
        synchronized(stateLock) {
            return myState.conversations.find { it.id == conversationId }?.hermesSessionId
        }
    }

    fun getConversation(id: String): Conversation? {
        conversationCache[id]?.let { return it }

        synchronized(stateLock) {
            return myState.conversations.find { it.id == id }?.toConversation()?.also {
                conversationCache[id] = it
            }
        }
    }

    fun saveConversationMessages(id: String, messages: List<ChatMessage>) {
        synchronized(stateLock) {
            val entry = myState.conversations.find { it.id == id } ?: return
            entry.updatedAt = System.currentTimeMillis()
            conversationCache.remove(id)

            entry.messages.clear()
            for (msg in messages) {
                var msgJson = json.encodeToString(ChatMessage.serializer(), msg)
                if (msg.contentParts != null) {
                    msgJson = ImageStore.processMessageImages(project, id, msgJson)
                }
                entry.messages.add(msgJson)
            }
        }
    }

    fun updateTitle(id: String, title: String) {
        synchronized(stateLock) {
            val entry = myState.conversations.find { it.id == id } ?: return
            entry.title = title
            conversationCache.remove(id)
        }
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        synchronized(stateLock) {
            myState = state
            conversationCache.clear()
            LOG.info("[ConversationStore] Loaded ${myState.conversations.size} conversations from XML")
        }
    }

    private fun ConversationEntry.toConversation(): Conversation {
        val msgs = mutableListOf<JsonElement>()
        for (msgStr in messages) {
            try {
                val element = json.parseToJsonElement(msgStr)
                // FIX: Issue #5 - Use enhanced image restoration with validation
                val restored = restoreImageReferences(element, id)
                msgs.add(restored)
            } catch (e: Exception) {
                LOG.warn("[ConversationStore] Failed to parse message: ${e.message}")
                try {
                    msgs.add(json.parseToJsonElement("""{"role":"system","content":"[Message parse error]"}"""))
                } catch (e2: Exception) {
                    // Ignore
                }
            }
        }
        return Conversation(id, title, createdAt, updatedAt, msgs)
    }

    /**
     * FIX: Issue #5 - Enhanced image reference restoration with integrity validation
     *
     * Validates:
     * 1. Image file exists
     * 2. Image size is within limits
     * 3. Image has valid header (magic bytes)
     * 4. Image is not corrupted
     */
    private fun restoreImageReferences(element: JsonElement, conversationId: String): JsonElement {
        return try {
            val obj = element.jsonObject
            val content = obj["content"] ?: return element

            if (content is JsonArray) {
                val restoredParts = content.map { part ->
                    val partObj = part.jsonObject
                    val imageUrl = partObj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content

                    if (imageUrl != null && imageUrl.startsWith("hermes-image:")) {
                        val fileName = imageUrl.substringAfter("hermes-image:")

                        // FIX: Issue #5 - Check if image file exists
                        if (!ImageStore.imageExists(project, conversationId, fileName)) {
                            LOG.warn("[ConversationStore] Image file not found: $fileName, marking as expired")
                            return@map buildJsonObjectWithExpiredImage(partObj, fileName)
                        }

                        // FIX: Issue #5 - Validate image integrity before loading
                        val validationResult = validateImageIntegrity(project, conversationId, fileName)
                        if (!validationResult.isValid) {
                            LOG.warn("[ConversationStore] Image validation failed for $fileName: ${validationResult.toString()}, marking as expired")
                            return@map buildJsonObjectWithExpiredImage(partObj, fileName)
                        }

                        val dataUrl = ImageStore.loadImage(project, conversationId, fileName)
                        if (dataUrl != null) {
                            buildJsonObjectWithImageUrl(partObj, dataUrl)
                        } else {
                            LOG.warn("[ConversationStore] Failed to load image: $fileName")
                            buildJsonObjectWithExpiredImage(partObj, fileName)
                        }
                    } else {
                        part
                    }
                }
                buildJsonObject {
                    for ((key, value) in obj) {
                        if (key == "content") {
                            put(key, JsonArray(restoredParts))
                        } else {
                            put(key, value)
                        }
                    }
                }
            } else {
                element
            }
        } catch (e: Exception) {
            LOG.warn("[ConversationStore] Error restoring image references: ${e.message}")
            element
        }
    }

    /**
     * FIX: Issue #5 - Validate image file integrity
     *
     * Checks:
     * 1. File size is within limits
     * 2. File has valid image header (magic bytes)
     * 3. File is not empty or corrupted
     */
    private fun validateImageIntegrity(project: Project, conversationId: String, fileName: String): ValidationResult {
        return try {
            // FIX: Use getImagePath instead of non-existent getImageFile
            val imagePath = ImageStore.getImagePath(project, conversationId, fileName)

            // Check file exists
            if (imagePath == null) {
                return ValidationResult.Invalid("Image file does not exist")
            }

            val imageFile = File(imagePath)

            // Check file size
            val fileSize = imageFile.length()
            if (fileSize == 0L) {
                return ValidationResult.Invalid("Image file is empty")
            }
            if (fileSize > maxImageSizeBytes) {
                LOG.warn("[ConversationStore] Image file too large: $fileName (${fileSize} bytes)")
                return ValidationResult.Invalid("Image file too large (max ${maxImageSizeBytes / 1024 / 1024}MB)")
            }

            // Check image header (magic bytes)
            val header = imageFile.inputStream().use { input ->
                ByteArray(12).also { input.read(it) }
            }

            // Validate magic bytes for common image formats
            val isValidImage = when {
                // JPEG: FF D8 FF
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> true
                // PNG: 89 50 4E 47 0D 0A 1A 0A
                header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() &&
                header[3] == 0x47.toByte() && header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() -> true
                // GIF: 47 49 46 38
                header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() &&
                header[3] == 0x38.toByte() -> true
                // WebP: 52 49 46 46 ... 57 45 42 50
                header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() &&
                header[3] == 0x46.toByte() && header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> true
                // BMP: 42 4D
                header[0] == 0x42.toByte() && header[1] == 0x4D.toByte() -> true
                else -> {
                    LOG.warn("[ConversationStore] Invalid image header for: $fileName")
                    false
                }
            }

            if (!isValidImage) {
                return ValidationResult.Invalid("Invalid image format (corrupted or not an image)")
            }

            ValidationResult.Valid
        } catch (e: Exception) {
            LOG.warn("[ConversationStore] Error validating image integrity: $fileName", e)
            ValidationResult.Invalid("Error validating image: ${e.message}")
        }
    }

    private fun buildJsonObjectWithImageUrl(
        original: JsonObject,
        dataUrl: String
    ): JsonObject {
        return buildJsonObject {
            for ((key, value) in original) {
                if (key == "image_url") {
                    put(key, buildJsonObject {
                        for ((ik, iv) in value.jsonObject) {
                            if (ik == "url") {
                                put(ik, JsonPrimitive(dataUrl))
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

    private fun buildJsonObjectWithExpiredImage(
        original: JsonObject,
        fileName: String
    ): JsonObject {
        return buildJsonObject {
            for ((key, value) in original) {
                if (key == "image_url") {
                    put(key, buildJsonObject {
                        for ((ik, iv) in value.jsonObject) {
                            if (ik == "url") {
                                put(ik, JsonPrimitive("hermes-image-expired:$fileName"))
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

    fun clearCache() {
        conversationCache.clear()
    }

    companion object {
        fun getInstance(project: Project): ConversationStore =
            project.getService(ConversationStore::class.java)
    }
}
