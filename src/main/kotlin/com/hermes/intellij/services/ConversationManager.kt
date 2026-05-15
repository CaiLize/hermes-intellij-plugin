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
 * 
 * Session ID semantics:
 * - New conversation: hermesSessionId = null (will be assigned on first message)
 * - Continuing conversation: hermesSessionId = assigned ID (shared across messages)
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
        Disposer.register(project) {
            LOG.info("[ConvMgr] Project disposing, cleaning up resources...")
            saveCurrentConversation()
            ImageStore.clearCache()
            store.clearCache()
        }
        LOG.info("[ConvMgr] Initialized for project: ${project.name}")
    }

    /**
     * Load the last active conversation (if any).
     */
    fun loadLastConversation() {
        val activeId = store.getActiveConversationId()
        if (activeId != null) {
            loadConversation(activeId)
        } else {
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

    fun isConversationLoaded(): Boolean = isConversationLoaded

    /**
     * Create a new empty conversation and switch to it.
     * 
     * IMPORTANT: New conversations do NOT inherit hermesSessionId.
     * Each new conversation should have its own session on the Hermes server.
     * The session ID will be assigned on the first message send.
     */
    fun createConversation(): String {
        saveCurrentConversation()

        val id = UUID.randomUUID().toString()
        // New conversation starts with no session ID
        store.createConversation(id, hermesSessionId = null)
        currentConversationId = id
        messages.clear()
        isConversationLoaded = true
        LOG.info("[ConvMgr] Created new conversation: $id (no hermesSessionId yet)")
        return id
    }

    /**
     * Switch to an existing conversation.
     */
    fun switchConversation(id: String): Boolean {
        if (id == currentConversationId) return false

        saveCurrentConversation()
        return loadConversation(id)
    }

    /**
     * Delete a conversation by ID.
     * Only delete the Hermes session if this is the ONLY conversation using it.
     */
    fun deleteConversation(id: String) {
        val hermesSessionId = store.getHermesSessionId(id)
        LOG.info("[ConvMgr] Deleting conversation $id, Hermes session ID: ${hermesSessionId ?: "null"}")
        
        store.deleteConversation(id)
        
        // Only delete from Hermes server if no other conversation uses this session
        if (!hermesSessionId.isNullOrBlank()) {
            val otherConversationsWithSameSession = store.getConversationSummaries()
                .filter { it.id != id }
                .mapNotNull { store.getHermesSessionId(it.id) }
                .count { it == hermesSessionId }
            
            if (otherConversationsWithSameSession == 0) {
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
                LOG.info("[ConvMgr] Skipping Hermes session deletion - $otherConversationsWithSameSession other conversation(s) still using this session")
            }
        }
        
        if (id == currentConversationId) {
            messages.clear()
            currentConversationId = null
            isConversationLoaded = false
            val summaries = store.getConversationSummaries()
            if (summaries.isNotEmpty()) {
                loadConversation(summaries.first().id)
            } else {
                createConversation()
            }
        }
        LOG.info("[ConvMgr] Deleted conversation: $id")
    }

    fun getConversationSummaries() = store.getConversationSummaries()

    fun getCurrentTitle(): String {
        val id = currentConversationId ?: return "New Chat"
        return store.getConversation(id)?.title ?: "New Chat"
    }

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
        if (messages.count { it.role == "user" } == 1 && currentConversationId != null) {
            val title = Conversation.generateTitle(content)
            store.updateTitle(currentConversationId!!, title)
        }
    }

    fun addUserMessageWithAttachments(
        text: String,
        imageBase64Urls: List<String> = emptyList(),
        fileAttachments: List<FileAttachmentData> = emptyList()
    ) {
        ensureConversationLoaded()
        val msg = ChatMessage.userWithAttachments(text, imageBase64Urls, fileAttachments)
        messages.add(msg)
        if (messages.count { it.role == "user" } == 1 && currentConversationId != null) {
            val title = Conversation.generateTitle(text)
            store.updateTitle(currentConversationId!!, title)
        }
    }
    
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
        if (messages.count { it.role == "user" } == 1 && currentConversationId != null) {
            val title = Conversation.generateTitle(text)
            store.updateTitle(currentConversationId!!, title)
        }
    }

    fun addAssistantMessage(content: String) {
        ensureConversationLoaded()
        messages.add(ChatMessage.assistant(content))
    }

    fun addAssistantMessage(content: String, toolCalls: List<ToolCallRecord>?) {
        ensureConversationLoaded()
        val msg = if (toolCalls != null && toolCalls.isNotEmpty()) {
            ChatMessage.assistant(content, toolCalls)
        } else {
            ChatMessage.assistant(content)
        }
        messages.add(msg)
    }

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

    fun setHermesSessionId(sessionId: String) {
        if (currentConversationId != null) {
            store.setHermesSessionId(currentConversationId!!, sessionId)
            LOG.info("[ConvMgr] Saved Hermes session ID $sessionId for conversation $currentConversationId")
        }
    }

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

    fun buildRequestMessages(
        userPrompt: String,
        codeContexts: List<CodeContextInfo> = emptyList(),
        imageBase64Urls: List<String> = emptyList()
    ): Pair<List<ChatMessage>, String> {
        ensureConversationLoaded()
        
        val requestMessages = mutableListOf<ChatMessage>()

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
        requestMessages.addAll(messages)

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

    fun saveCurrentConversation() {
        if (currentConversationId == null || messages.isEmpty()) return
        store.saveConversationMessages(currentConversationId!!, messages)
        LOG.info("[ConvMgr] Saved conversation $currentConversationId (${messages.size} messages)")
    }

    private fun loadConversation(id: String): Boolean {
        val conversation = store.getConversation(id) ?: return false

        messages.clear()
        for (jsonElement in conversation.messages) {
            try {
                val msg = json.decodeFromJsonElement(ChatMessage.serializer(), jsonElement)
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
                            LOG.warn("[ConvMgr] Failed to load image: $fileName, keeping reference")
                            part
                        }
                    } else if (url.startsWith("hermes-image-expired:")) {
                        part
                    } else {
                        part
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
