package com.hermes.intellij.services

import com.hermes.intellij.api.HermesApiClient
import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.api.models.ContentPart
import com.hermes.intellij.api.models.FileAttachmentData
import com.hermes.intellij.api.models.MessageSegment
import com.hermes.intellij.api.models.ToolCallRecord
import com.hermes.intellij.model.Conversation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Manages multiple conversations with persistence support.
 * Delegates storage to ConversationStore and image management to ImageStore.
 * 
 * Optimizations:
 * - Proper resource cleanup on project close
 * - Lazy conversation loading
 * - Improved error handling
 */
class ConversationManager(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(ConversationManager::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val store = ConversationStore.getInstance(project)
    private val messages = mutableListOf<ChatMessage>()
    private var currentConversationId: String? = null
    private var projectName: String = ""
    private var currentFilePath: String? = null
    private var currentFileType: String? = null
    
    // Track if conversation is loaded (lazy loading support)
    private var isConversationLoaded = false

    init {
        // Register cleanup on project dispose using Disposer
        // This is the recommended approach for IntelliJ Platform 2024.1+
        Disposer.register(project) {
            LOG.info("[ConvMgr] Project disposing, cleaning up resources...")
            saveCurrentConversation()
            ImageStore.clearCache()
            store.clearCache()
        }
        LOG.info("[ConvMgr] Initialized for project: ${project.name}")
    }

    /**
     * Load the last active conversation (if any). Should be called from a background thread.
     */
    fun loadLastConversation() {
        val activeId = store.getActiveConversationId()
        if (activeId != null) {
            loadConversation(activeId)
        } else {
            // No active conversation, create a new one
            createConversation()
        }
    }

    fun setProjectInfo(projectName: String) {
        this.projectName = projectName
    }

    fun setCurrentFileInfo(filePath: String?, fileType: String?) {
        this.currentFilePath = filePath
        this.currentFileType = fileType
    }

    // ============================================================
    // Conversation lifecycle
    // ============================================================

    fun getCurrentConversationId(): String? = currentConversationId

    /**
     * Check if a conversation is currently loaded.
     */
    fun isConversationLoaded(): Boolean = isConversationLoaded

    /**
     * Create a new empty conversation and switch to it.
     * Returns the new conversation ID.
     */
    fun createConversation(): String {
        // Save current conversation before creating new one
        saveCurrentConversation()

        val id = UUID.randomUUID().toString()
        store.createConversation(id)
        currentConversationId = id
        messages.clear()
        isConversationLoaded = true
        LOG.info("[ConvMgr] Created new conversation: $id")
        return id
    }

    /**
     * Switch to an existing conversation.
     * Returns true if the switch was successful.
     */
    fun switchConversation(id: String): Boolean {
        if (id == currentConversationId) return false

        saveCurrentConversation()
        return loadConversation(id)
    }

    /**
     * Delete a conversation by ID.
     * Image cleanup is handled by ConversationStore.deleteConversation().
     * Also calls Hermes API to delete the session on the server.
     */
    fun deleteConversation(id: String) {
        // Get Hermes session ID BEFORE deleting from local storage
        val hermesSessionId = store.getHermesSessionId(id)
        LOG.info("[ConvMgr] Deleting conversation $id, Hermes session ID: ${hermesSessionId ?: "null"}")
        
        // Delete from local storage
        store.deleteConversation(id)
        
        // Delete from Hermes server if session ID was available
        if (!hermesSessionId.isNullOrBlank()) {
            try {
                val apiClient = HermesApiClient.getInstance()
                LOG.info("[ConvMgr] Calling Hermes API to delete session: $hermesSessionId")
                val deleted = apiClient.deleteSession(hermesSessionId)
                if (deleted) {
                    LOG.info("[ConvMgr] Successfully deleted Hermes session: $hermesSessionId")
                } else {
                    LOG.warn("[ConvMgr] Failed to delete Hermes session (API returned false): $hermesSessionId")
                }
            } catch (e: Exception) {
                LOG.warn("[ConvMgr] Exception deleting Hermes session $hermesSessionId: ${e.message}", e)
            }
        } else {
            LOG.warn("[ConvMgr] No Hermes session ID found for conversation $id, skipping server deletion. Was any message sent in this conversation?")
        }
        if (id == currentConversationId) {
            messages.clear()
            currentConversationId = null
            isConversationLoaded = false
            // Switch to the first available conversation, or create new
            val summaries = store.getConversationSummaries()
            if (summaries.isNotEmpty()) {
                loadConversation(summaries.first().id)
            } else {
                createConversation()
            }
        }
        LOG.info("[ConvMgr] Deleted conversation: $id")
    }

    /**
     * Get all conversation summaries (without messages) for the UI list.
     */
    fun getConversationSummaries() = store.getConversationSummaries()

    /**
     * Get the current conversation title.
     */
    fun getCurrentTitle(): String {
        val id = currentConversationId ?: return "New Chat"
        return store.getConversation(id)?.title ?: "New Chat"
    }

    /**
     * Update the current conversation's title.
     */
    fun updateCurrentTitle(title: String) {
        if (currentConversationId != null) {
            store.updateTitle(currentConversationId!!, title)
        }
    }

    // ============================================================
    // Message management
    // ============================================================

    fun addUserMessage(content: String) {
        ensureConversationLoaded()
        messages.add(ChatMessage.user(content))
        // Auto-generate title from first user message
        if (messages.count { it.role == "user" } == 1 && currentConversationId != null) {
            val title = Conversation.generateTitle(content)
            store.updateTitle(currentConversationId!!, title)
        }
    }

    /**
     * Add a user message with image and file attachments for full persistence.
     */
    fun addUserMessageWithAttachments(
        text: String,
        imageBase64Urls: List<String> = emptyList(),
        fileAttachments: List<FileAttachmentData> = emptyList()
    ) {
        ensureConversationLoaded()
        val msg = ChatMessage.userWithAttachments(text, imageBase64Urls, fileAttachments)
        messages.add(msg)
        // Auto-generate title from first user message
        if (messages.count { it.role == "user" } == 1 && currentConversationId != null) {
            val title = Conversation.generateTitle(text)
            store.updateTitle(currentConversationId!!, title)
        }
    }
    
    /**
     * Add a user message with ordered segments for perfect interleaving of text, images, and files.
     * This is the preferred method for saving user messages with attachments.
     * Note: segments should contain all content parts (text, images, files) in order.
     */
    fun addUserMessageWithSegments(
        text: String,
        segments: List<MessageSegment>?
    ) {
        ensureConversationLoaded()
        val msg = ChatMessage(
            role = "user",
            content = text,
            segments = segments
        )
        messages.add(msg)
        // Auto-generate title from first user message
        if (messages.count { it.role == "user" } == 1 && currentConversationId != null) {
            val title = Conversation.generateTitle(text)
            store.updateTitle(currentConversationId!!, title)
        }
    }

    fun addAssistantMessage(content: String) {
        ensureConversationLoaded()
        messages.add(ChatMessage.assistant(content))
    }

    /**
     * Add an assistant message with tool call history for persistence.
     */
    fun addAssistantMessage(content: String, toolCalls: List<ToolCallRecord>?) {
        ensureConversationLoaded()
        val msg = if (toolCalls != null && toolCalls.isNotEmpty()) {
            ChatMessage.assistant(content, toolCalls)
        } else {
            ChatMessage.assistant(content)
        }
        messages.add(msg)
    }

    /**
     * Add an assistant message with ordered segments for perfect interleaving.
     * This is the preferred method for saving streaming responses with tool calls.
     */
    fun addAssistantMessageWithSegments(
        content: String,
        segments: List<MessageSegment>?,
        toolCalls: List<ToolCallRecord>? = null
    ) {
        ensureConversationLoaded()
        val msg = ChatMessage(
            role = "assistant",
            content = content,
            segments = segments,
            toolCalls = toolCalls
        )
        messages.add(msg)
    }

    /**
     * Set the Hermes session ID for the current conversation.
     */
    fun setHermesSessionId(sessionId: String) {
        if (currentConversationId != null) {
            store.setHermesSessionId(currentConversationId!!, sessionId)
            LOG.info("[ConvMgr] Saved Hermes session ID $sessionId for conversation $currentConversationId")
        }
    }

    /**
     * Get the Hermes session ID for the current conversation.
     */
    fun getCurrentSessionId(): String? {
        return if (currentConversationId != null) {
            store.getHermesSessionId(currentConversationId!!)
        } else {
            null
        }
    }

    fun getMessages(): List<ChatMessage> {
        ensureConversationLoaded()
        return messages.toList()
    }

    fun clear() {
        createConversation()
    }
    
    /**
     * Ensure conversation is loaded before accessing messages.
     * This supports lazy loading pattern.
     */
    private fun ensureConversationLoaded() {
        if (!isConversationLoaded && currentConversationId != null) {
            loadConversation(currentConversationId!!)
        } else if (currentConversationId == null) {
            createConversation()
        }
    }

    // ============================================================
    // Request building
    // ============================================================

    /**
     * Build the full message text by merging user prompt with code contexts.
     * Uses structured formatting with clear section headers for better model understanding.
     */
    private fun buildUserText(
        userPrompt: String,
        codeContexts: List<CodeContextInfo>
    ): String {
        if (codeContexts.isEmpty()) return userPrompt
        return buildString {
            append("## User Request\n")
            append(userPrompt)
            append("\n\n## Code Context\n")
            for ((index, ctx) in codeContexts.withIndex()) {
                val fileInfo = if (ctx.lineRange != null) {
                    "${ctx.filePath} (lines ${ctx.lineRange})"
                } else {
                    ctx.filePath
                }
                append("\n### Context ${index + 1}: `$fileInfo`\n")
                append("```language:${ctx.language}\n")
                append(ctx.content)
                append("\n```\n")
            }
        }
    }

    /**
     * Build the full list of messages for an API request.
     * Code contexts are merged into the user message (not sent as a separate message)
     * to avoid consecutive user-role messages which many APIs reject.
     * Returns a Pair: (request messages for API, merged user text for saving to history).
     */
    fun buildRequestMessages(
        userPrompt: String,
        codeContexts: List<CodeContextInfo> = emptyList(),
        imageBase64Urls: List<String> = emptyList()
    ): Pair<List<ChatMessage>, String> {
        ensureConversationLoaded()
        
        val requestMessages = mutableListOf<ChatMessage>()

        // System message
        val systemPrompt = buildString {
            append("You are Hermes, an AI coding assistant.")
            if (projectName.isNotEmpty()) {
                append(" The user is working in IntelliJ IDEA on project '$projectName'.")
            }
            append(" Provide concise, accurate answers.")
            append(" When suggesting code, use fenced code blocks with the language tag.")
            if (currentFilePath != null) {
                append(" Current file: $currentFilePath")
                if (currentFileType != null) {
                    append(" ($currentFileType)")
                }
                append(".")
            }
        }
        requestMessages.add(ChatMessage.system(systemPrompt))

        // Conversation history
        requestMessages.addAll(messages)

        // Current user message: merge prompt + code context + images
        val mergedUserText = buildUserText(userPrompt, codeContexts)
        if (imageBase64Urls.isNotEmpty()) {
            requestMessages.add(ChatMessage.userWithImages(mergedUserText, imageBase64Urls))
        } else {
            requestMessages.add(ChatMessage.user(mergedUserText))
        }

        return requestMessages to mergedUserText
    }

    // ============================================================
    // Persistence helpers
    // ============================================================

    /**
     * Save current in-memory messages to the store.
     * Call this before switching conversations or on shutdown.
     */
    fun saveCurrentConversation() {
        if (currentConversationId == null || messages.isEmpty()) return
        store.saveConversationMessages(currentConversationId!!, messages)
        LOG.info("[ConvMgr] Saved conversation $currentConversationId (${messages.size} messages)")
    }

    /**
     * Load a conversation's messages from the store into memory.
     * Image references (hermes-image:xxx) are resolved back to base64 data URLs.
     * Returns true if successful.
     */
    private fun loadConversation(id: String): Boolean {
        val conversation = store.getConversation(id) ?: return false

        messages.clear()
        for (jsonElement in conversation.messages) {
            try {
                val msg = json.decodeFromJsonElement(ChatMessage.serializer(), jsonElement)
                // Resolve hermes-image:xxx references back to base64 data URLs
                val resolvedMsg = resolveHermesImageReferences(msg, id)
                messages.add(resolvedMsg)
            } catch (e: Exception) {
                LOG.warn("[ConvMgr] Failed to parse message: ${e.message}")
            }
        }

        currentConversationId = id
        isConversationLoaded = true
        LOG.info("[ConvMgr] Loaded conversation $id (${messages.size} messages)")
        return true
    }

    /**
     * Resolve hermes-image:xxx references in a ChatMessage's contentParts back to
     * actual base64 data URLs by loading from disk.
     * If an image cannot be loaded, preserves the reference for lazy loading.
     */
    private fun resolveHermesImageReferences(msg: ChatMessage, conversationId: String): ChatMessage {
        if (msg.contentParts == null) return msg

        val resolvedParts = msg.contentParts.map { part ->
            when (part) {
                is ContentPart.ImageUrl -> {
                    val url = part.imageUrl.url
                    if (url.startsWith("hermes-image:")) {
                        val fileName = url.removePrefix("hermes-image:")
                        val actualUrl = ImageStore.loadImage(project, conversationId, fileName)
                        if (actualUrl != null) {
                            LOG.info("[ConvMgr] Resolved image reference: $fileName")
                            ContentPart.ImageUrl(part.imageUrl.copy(url = actualUrl))
                        } else {
                            // Keep reference for lazy loading - UI can handle expired marker
                            LOG.warn("[ConvMgr] Failed to load image: $fileName, keeping reference")
                            part
                        }
                    } else if (url.startsWith("hermes-image-expired:")) {
                        // Already marked as expired - keep as-is for UI to handle
                        part
                    } else {
                        part // Not a hermes-image reference, keep as-is
                    }
                }
                else -> part
            }
        }
        return msg.copy(contentParts = resolvedParts)
    }

    // ============================================================
    // Data classes
    // ============================================================

    data class CodeContextInfo(
        val filePath: String,
        val lineRange: String?,
        val content: String,
        val language: String
    )
}
