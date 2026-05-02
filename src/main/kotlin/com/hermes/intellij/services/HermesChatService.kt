package com.hermes.intellij.services

import com.hermes.intellij.api.HermesApiClient
import com.hermes.intellij.api.HermesApiException
import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.api.models.ChatRequest
import com.hermes.intellij.api.models.FileAttachmentData
import com.hermes.intellij.model.CodeContext
import com.hermes.intellij.model.ConversationSummary
import com.hermes.intellij.model.FileAttachment
import com.hermes.intellij.model.ImageContext
import com.hermes.intellij.toolwindow.ChatPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ConnectException

@Service(Service.Level.PROJECT)
class HermesChatService(private val project: Project) {

    private val logger = Logger.getInstance(HermesChatService::class.java)
    private val conversationManager = ConversationManager(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    init {
        conversationManager.setProjectInfo(project.name)
    }

    fun sendMessage(
        prompt: String,
        contexts: List<CodeContext>,
        files: List<FileAttachment>,
        images: List<ImageContext>,
        chatPanel: ChatPanel
    ) {
        // Update current file info
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null) {
            val vf = editor.virtualFile
            conversationManager.setCurrentFileInfo(vf?.path, vf?.fileType?.name)
        }

        // Convert contexts
        val codeContextInfos = contexts.map {
            ConversationManager.CodeContextInfo(
                filePath = it.filePath,
                lineRange = it.lineRange,
                content = it.content,
                language = it.language
            )
        }

        // Convert files to CodeContextInfo (with content for API request)
        val fileInfos = files.map {
            val fileInfo = ConversationManager.CodeContextInfo(
                filePath = it.filePath,
                lineRange = it.lineRange,
                content = it.content, // 文件内容需要发送到 API
                language = it.language
            )
            // 仅记录文件名和大小，不记录内容
            logger.info("[HermesChat] File attached: ${it.fileName}, contentLen=${it.content.length}")
            fileInfo
        }

        // Extract image base64 URLs
        val imageBase64Urls = images.map { it.base64Data }

        // Build request — returns (messages for API, merged user text for history)
        val settings = HermesSettingsState.getInstance()
        val (messages, mergedUserText) = conversationManager.buildRequestMessages(
            prompt, codeContextInfos + fileInfos, imageBase64Urls
        )
        logger.info("[HermesChat] Sending request: contexts=${codeContextInfos.size}, files=${fileInfos.size}, images=${imageBase64Urls.size}, mergedTextLen=${mergedUserText.length}")

        // Build the ChatRequest
        val request = ChatRequest(
            model = settings.modelName,
            messages = messages,
            stream = true
        )

        // Check request body size before sending
        val requestJson = Json.encodeToString(request)
        val requestSizeMB = requestJson.length.toDouble() / (1024 * 1024)
        logger.info("[HermesChat] Request body size: ${"%.2f".format(requestSizeMB)} MB")
        if (requestSizeMB > 15) {
            ApplicationManager.getApplication().invokeLater {
                chatPanel.onStreamingError(
                    "Request too large (${"%.1f".format(requestSizeMB)} MB). " +
                    "Maximum supported size is ~15 MB. Try reducing conversation history or compressing images."
                )
            }
            return
        }

        val responseBuilder = StringBuilder()

        currentJob = scope.launch {
            try {
                val apiClient = HermesApiClient.getInstance()
                
                // Get current session ID (if we're continuing a conversation)
                val currentSessionId = conversationManager.getCurrentSessionId()
                logger.info("[HermesChat] Using session ID: $currentSessionId")
                
                // Call new API that extracts session ID from response header
                val result = apiClient.streamChatWithSession(request, currentSessionId)
                
                // Save session ID immediately (from response header)
                if (result.sessionId != null) {
                    logger.info("[HermesChat] Received new session ID: ${result.sessionId}")
                    conversationManager.setHermesSessionId(result.sessionId)
                }
                
                result.deltas
                    .catch { e ->
                        val errorMsg = when (e) {
                            is ConnectException -> {
                                "Cannot connect to Hermes at ${settings.apiEndpoint}. Is hermes gateway running?"
                            }
                            is HermesApiException -> {
                                when {
                                    e.statusCode == 401 -> "Authentication failed. Check your API key in Settings."
                                    e.statusCode == 403 -> "Access forbidden. Check your permissions."
                                    e.statusCode == 404 -> "API endpoint not found. Check your endpoint URL."
                                    e.statusCode in 500..599 -> "Server error (${e.statusCode}). The Hermes service may be experiencing issues."
                                    else -> "API error (${e.statusCode}). Check logs for details."
                                }
                            }
                            else -> "Error: ${e.message}"
                        }
                        // 不记录完整的异常堆栈和响应体，避免泄露敏感信息
                        logger.warn("Hermes streaming error: ${e.javaClass.simpleName} (status=${if (e is HermesApiException) e.statusCode else "N/A"})")
                        ApplicationManager.getApplication().invokeLater {
                            chatPanel.onStreamingError(errorMsg)
                        }
                    }
                    .collect { delta ->
                        for (choice in delta.choices) {
                            val content = choice.delta.content
                            if (content != null) {
                                responseBuilder.append(content)
                                ApplicationManager.getApplication().invokeLater {
                                    chatPanel.onTokenReceived(content)
                                }
                            }
                        }
                    }

                val fullResponse = responseBuilder.toString()
                if (fullResponse.isNotEmpty()) {
                    // Save user message with full attachment info for persistence
                    // 合并 codeContextInfos 和 fileInfos（两者都包含文件信息）
                    val allFileAttachments = codeContextInfos.map { ctx ->
                        FileAttachmentData(
                            filePath = ctx.filePath,
                            lineRange = ctx.lineRange,
                            language = ctx.language
                        )
                    } + fileInfos.map { file ->
                        FileAttachmentData(
                            filePath = file.filePath,
                            lineRange = file.lineRange,
                            language = file.language
                        )
                    }
                    // 注意：保存时只用原始 prompt 作为显示文本，不包含代码上下文内容
                    // 代码上下文和文件内容仅用于 API 请求，文件信息通过 fileAttachments 持久化
                    conversationManager.addUserMessageWithAttachments(
                        text = prompt,
                        imageBase64Urls = imageBase64Urls,
                        fileAttachments = allFileAttachments
                    )
                    conversationManager.addAssistantMessage(fullResponse)
                    // Persist after each exchange
                    conversationManager.saveCurrentConversation()
                }

                ApplicationManager.getApplication().invokeLater {
                    chatPanel.onStreamingComplete()
                }
            } catch (e: CancellationException) {
                // Save partial conversation on cancel
                val partial = responseBuilder.toString()
                if (partial.isNotEmpty()) {
                    // 合并 codeContextInfos 和 fileInfos
                    val allFileAttachments = codeContextInfos.map { ctx ->
                        FileAttachmentData(
                            filePath = ctx.filePath,
                            lineRange = ctx.lineRange,
                            language = ctx.language
                        )
                    } + fileInfos.map { file ->
                        FileAttachmentData(
                            filePath = file.filePath,
                            lineRange = file.lineRange,
                            language = file.language
                        )
                    }
                    // 同上：只保存原始 prompt，不包含代码上下文内容
                    conversationManager.addUserMessageWithAttachments(
                        text = prompt,
                        imageBase64Urls = imageBase64Urls,
                        fileAttachments = allFileAttachments
                    )
                    conversationManager.addAssistantMessage(partial + "\n\n(Response cancelled)")
                    conversationManager.saveCurrentConversation()
                }
                logger.info("Hermes request cancelled")
            } catch (e: Exception) {
                logger.warn("Hermes unexpected error", e)
                ApplicationManager.getApplication().invokeLater {
                    chatPanel.onStreamingError("Unexpected error: ${e.message}")
                }
            }
        }
    }

    fun cancelCurrentRequest() {
        currentJob?.cancel()
        currentJob = null
    }

    // ============================================================
    // Conversation management (delegated to ConversationManager)
    // ============================================================

    fun createNewConversation(): String {
        cancelCurrentRequest()
        return conversationManager.createConversation()
    }

    fun switchConversation(id: String): Boolean {
        cancelCurrentRequest()
        return conversationManager.switchConversation(id)
    }

    fun deleteConversation(id: String) {
        cancelCurrentRequest()
        conversationManager.deleteConversation(id)
    }

    fun getConversationSummaries(): List<ConversationSummary> {
        return conversationManager.getConversationSummaries()
    }

    fun getCurrentConversationMessages(): List<ChatMessage> {
        return conversationManager.getMessages()
    }

    fun getCurrentConversationId(): String? {
        return conversationManager.getCurrentConversationId()
    }

    fun getCurrentTitle(): String {
        return conversationManager.getCurrentTitle()
    }

    fun updateCurrentTitle(title: String) {
        conversationManager.updateCurrentTitle(title)
    }

    /**
     * Initialize: ensure at least one conversation exists.
     * Loads the last active conversation if available.
     * This performs I/O and should be called from a background thread.
     */
    fun ensureConversation(): String {
        // Load last conversation if we haven't loaded one yet
        if (conversationManager.getCurrentConversationId() == null) {
            conversationManager.loadLastConversation()
        }
        // Still no conversation? Create a new one
        if (conversationManager.getCurrentConversationId() == null) {
            return conversationManager.createConversation()
        }
        return conversationManager.getCurrentConversationId()!!
    }

    companion object {
        fun getInstance(project: Project): HermesChatService =
            project.getService(HermesChatService::class.java)
    }
}
