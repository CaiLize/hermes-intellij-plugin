package com.hermes.intellij.services

import com.hermes.intellij.api.HermesApiClient
import com.hermes.intellij.api.HermesApiException
import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.api.models.ChatRequest
import com.hermes.intellij.api.models.FileAttachmentData
import com.hermes.intellij.api.models.FunctionDefinition
import com.hermes.intellij.api.models.MessageSegment
import com.hermes.intellij.api.models.ToolCall
import com.hermes.intellij.api.models.ToolCallRecord
import com.hermes.intellij.api.models.ToolDefinition
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

@Service(Service.Level.PROJECT)
class HermesChatService(private val project: Project) {

    private val logger = Logger.getInstance(HermesChatService::class.java)
    private val conversationManager = ConversationManager(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    // FIX: Use centralized state machine instead of scattered trackers
    private val toolCallStateMachine = ToolCallStateMachine()

    init {
        conversationManager.setProjectInfo(project.name)
    }

    fun sendMessage(
        prompt: String,
        contexts: List<CodeContext>,
        files: List<FileAttachment>,
        images: List<ImageContext>,
        chatPanel: ChatPanel,
        stateListener: ToolCallStateMachine.StateChangeListener? = null
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
                content = it.content,
                language = it.language
            )
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

        // Build tool definitions for function calling
        val tools = buildToolDefinitions()
        logger.info("[HermesChat] Tool definitions: ${tools.size} tools declared")

        // Clear pending tool calls — state machine handles its own cleanup
        toolCallStateMachine.startStream()

        // Register state listener for this request
        stateListener?.let { toolCallStateMachine.addListener(it) }

        // Build the ChatRequest
        val request = ChatRequest(
            model = settings.modelName,
            messages = messages,
            stream = true,
            temperature = null,
            maxTokens = null,
            tools = tools
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
        var hasCompleted = false
        var hasError = false

        currentJob = scope.launch {
            try {
                val apiClient = HermesApiClient.getInstance()

                // Get current session ID (if we're continuing a conversation)
                val currentSessionId = conversationManager.getCurrentSessionId()
                logger.info("[HermesChat] Using session ID: $currentSessionId")

                // Call new API that extracts session ID from response header
                val result = apiClient.streamChatWithSession(request, currentSessionId)

                // FIX: Issue #6 - Validate session ID before saving
                if (result.sessionId != null) {
                    logger.info("[HermesChat] Received new session ID: ${result.sessionId}")
                    // Validate session ID format before accepting
                    val sessionIdValidation = com.hermes.intellij.security.SessionSecurityValidator.validateSessionId(result.sessionId)
                    if (sessionIdValidation.isValid) {
                        conversationManager.setHermesSessionId(result.sessionId)
                    } else {
                        logger.warn("[HermesChat] Invalid session ID from server: ${sessionIdValidation.toString()}, ignoring")
                    }
                }

                // FIX: Issue #1 - Use withContext(Dispatchers.Main) for all UI updates
                // This ensures ordered execution and proper thread switching
                result.deltas
                    .flowOn(Dispatchers.IO)
                    .catch { e ->
                        // FIX: Better error categorization for stream interruptions
                        val errorMsg = when (e) {
                            is ConnectException -> {
                                "无法连接到 Hermes 服务 (${settings.apiEndpoint})。请检查服务是否正在运行。"
                            }
                            is SocketTimeoutException -> {
                                "连接超时。服务器响应太慢，请检查网络连接或服务器状态。"
                            }
                            is SocketException -> {
                                "网络连接中断: ${e.message}。可能是网络不稳定或服务器关闭了连接。"
                            }
                            is HermesApiException -> {
                                when {
                                    e.statusCode == 401 -> "认证失败。请检查设置中的 API Key。"
                                    e.statusCode == 403 -> "访问被拒绝。请检查权限设置。"
                                    e.statusCode == 404 -> "API 端点未找到。请检查端点 URL。"
                                    e.statusCode == 413 -> "请求体过大。请减少对话历史或压缩图片。"
                                    e.statusCode == 500 -> "服务器内部错误 (${e.statusCode})。请稍后重试。"
                                    e.statusCode in 502..504 -> "网关错误 (${e.statusCode})。Hermes 服务可能正在重启。"
                                    else -> "API 错误 (${e.statusCode})。请查看日志获取详细信息。"
                                }
                            }
                            is CancellationException -> {
                                // FIX: Don't show error for cancellation - it's expected
                                logger.info("[HermesChat] Stream cancelled: ${e.message}")
                                throw e  // Re-throw to trigger cancellation handler
                            }
                            is Exception -> {
                                logger.warn("[HermesChat] Stream error: ${e.javaClass.simpleName}: ${e.message}")
                                "流式响应中断: ${e.javaClass.simpleName}。请检查网络连接，然后重试。"
                            }
                            else -> {
                                logger.warn("[HermesChat] Unexpected stream error: ${e.javaClass.simpleName}: ${e.message}")
                                "发生未知错误: ${e.message}"
                            }
                        }

                        // Mark that we had an error
                        hasError = true

                        // FIX: Don't log full stack trace to avoid exposing sensitive info
                        if (e !is CancellationException) {
                            logger.warn("Hermes streaming error: ${e.javaClass.simpleName} (status=${if (e is HermesApiException) e.statusCode else "N/A"})")
                        }

                        // FIX: Issue #1 - Use withContext for thread-safe UI update
                        withContext(Dispatchers.Main) {
                            chatPanel.onStreamingError(errorMsg)
                        }
                    }
                    .collect { delta ->
                        // FIX: Issue #1 - All UI updates happen in Dispatchers.Main context
                        withContext(Dispatchers.Main) {
                            // Log delta for debugging tool call visibility
                            logger.info("[HermesChat] Received delta: choices=${delta.choices.size}, hasToolCalls=${delta.choices.any { it.delta.toolCalls != null }}, hasContent=${delta.choices.any { it.delta.content != null }}")

                            for (choice in delta.choices) {
                                // Handle tool_calls - register with state machine
                                choice.delta.toolCalls?.forEach { toolCall ->
                                    val toolName = toolCall.function?.name ?: "unknown"
                                    val toolArgs = toolCall.function?.arguments ?: ""
                                    logger.info("[HermesChat] Tool call detected: $toolName, args=${toolArgs.take(100)}")

                                    // FIX: Use state machine for tracking — adds UI notification via listener
                                    val record = toolCallStateMachine.addToolCall(toolCall, toolArgs)

                                    logger.info("[HermesChat] Tool call registered: id=${record.id}, name=$toolName, pending=${toolCallStateMachine.getRunningCount()}")
                                    // UI update is handled by ChatPanelStateListener
                                }

                                // Handle content — mark all running tool calls as complete
                                val content = choice.delta.content
                                if (content != null) {
                                    if (toolCallStateMachine.hasRunning()) {
                                        // FIX: Use state machine — idempotent, thread-safe
                                        val completedTools = toolCallStateMachine.markAllCompleted()

                                        logger.info("[HermesChat] Marking ${completedTools.size} tool(s) as complete via state machine, starting content display")
                                        // UI update is handled by ChatPanelStateListener
                                    }

                                    responseBuilder.append(content)
                                    chatPanel.onTokenReceived(content)
                                }
                            }
                        }
                    }

                // FIX: State machine cleanup after stream ends
                if (toolCallStateMachine.hasRunning()) {
                    val completedTools = toolCallStateMachine.markAllCompleted()
                    // UI updates handled by listener

                    logger.info("[HermesChat] Post-stream cleanup: ${completedTools.size} remaining tool(s) marked complete")
                }

                toolCallStateMachine.endStream(hasError = hasError)

                val fullResponse = responseBuilder.toString()
                if (fullResponse.isNotEmpty() && !hasError) {
                    // Save user message with full attachment info for persistence
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

                    // FIX: Use segments for user message to preserve order of text, images, files
                    val userSegments = buildUserMessageSegments(prompt, imageBase64Urls, allFileAttachments)
                    conversationManager.addUserMessageWithSegments(
                        text = prompt,
                        segments = userSegments
                    )

                    // Save assistant message with ordered segments
                    val assistantSegments = chatPanel.messageListPanel.getStreamingSegments()
                    val toolCallRecords = if (!toolCallStateMachine.isEmpty()) {
                        toolCallStateMachine.getAllRecords().map { r ->
                            ToolCallRecord(
                                name = r.name,
                                arguments = r.arguments,
                                status = when (r.status) {
                                    ToolCallStateMachine.ToolCallStatus.RUNNING -> "running"
                                    ToolCallStateMachine.ToolCallStatus.COMPLETED -> "completed"
                                    ToolCallStateMachine.ToolCallStatus.CANCELLED -> "cancelled"
                                },
                                completed = r.status != ToolCallStateMachine.ToolCallStatus.RUNNING
                            )
                        }
                    } else null
                    conversationManager.addAssistantMessageWithSegments(
                        content = fullResponse,
                        segments = assistantSegments,
                        toolCalls = toolCallRecords
                    )

                    // Persist after each exchange
                    conversationManager.saveCurrentConversation()
                }

                hasCompleted = true
                withContext(Dispatchers.Main) {
                    chatPanel.onStreamingComplete()
                }
            } catch (e: CancellationException) {
                logger.info("[HermesChat] Request cancelled: ${e.message}")

                // FIX: Use state machine for cancellation — idempotent, thread-safe
                val cancelledTools = toolCallStateMachine.markAllCancelled()
                toolCallStateMachine.endStream(hasError = false)

                withContext(Dispatchers.Main) {
                    cancelledTools.forEach { tool ->
                        chatPanel.onToolCallComplete(tool.name) // UI shows cancelled state
                    }
                }

                // Save partial conversation on cancel
                val partial = responseBuilder.toString()
                if (partial.isNotEmpty()) {
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

                    val userSegments = buildUserMessageSegments(prompt, imageBase64Urls, allFileAttachments)
                    conversationManager.addUserMessageWithSegments(
                        text = prompt,
                        segments = userSegments
                    )

                    val assistantSegments = chatPanel.messageListPanel.getStreamingSegments()
                    val toolCallRecords = toolCallStateMachine.getAllRecords().map { r ->
                        ToolCallRecord(
                            name = r.name,
                            arguments = r.arguments,
                            status = "cancelled",
                            completed = true
                        )
                    }
                    conversationManager.addAssistantMessageWithSegments(
                        content = partial + "\n\n*(响应已取消)*",
                        segments = assistantSegments,
                        toolCalls = toolCallRecords
                    )

                    conversationManager.saveCurrentConversation()
                }

                // FIX: Explicitly call onCancelStreaming to clean up UI state
                withContext(Dispatchers.Main) {
                    chatPanel.onCancelStreaming()
                }
            } catch (e: Exception) {
                // FIX: Use state machine for error cleanup — idempotent, thread-safe
                val erroredTools = toolCallStateMachine.markAllCancelled()
                toolCallStateMachine.endStream(hasError = true)

                withContext(Dispatchers.Main) {
                    erroredTools.forEach { tool ->
                        chatPanel.onToolCallComplete(tool.name)
                    }
                }

                logger.warn("[HermesChat] Unexpected error", e)
                withContext(Dispatchers.Main) {
                    val errorMsg = when (e) {
                        is SocketTimeoutException -> "请求超时。服务器响应太慢，请检查网络连接。"
                        is SocketException -> "网络连接错误: ${e.message}。请检查网络后重试。"
                        else -> "发生错误: ${e.message}"
                    }
                    chatPanel.onStreamingError(errorMsg)
                }
            } finally {
                // FIX: Always clean up, regardless of how the coroutine ended
                currentJob = null
                stateListener?.let { toolCallStateMachine.removeListener(it) }
                toolCallStateMachine.clear()

                // Only call onStreamingComplete if we didn't already handle completion/cancellation/error
                if (!hasCompleted && !hasError) {
                    logger.info("[HermesChat] Finally block cleaning up (hasCompleted=$hasCompleted, hasError=$hasError)")
                    withContext(Dispatchers.Main) {
                        // Only finalize if not already finalized
                        if (chatPanel.messageListPanel.getStreamingBubble() != null) {
                            chatPanel.onStreamingComplete()
                        }
                    }
                }
            }
        }
    }

    /**
     * Build ordered segments for a user message, preserving the chronological order
     * of text, images, and files.
     */
    private fun buildUserMessageSegments(
        text: String,
        imageBase64Urls: List<String>,
        fileAttachments: List<FileAttachmentData>
    ): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()

        // Start with text
        if (text.isNotBlank()) {
            segments.add(MessageSegment.Text(text))
        }

        // Add images in order
        for (base64Url in imageBase64Urls) {
            val base64Data = if (base64Url.startsWith("data:")) {
                base64Url.substringAfter(",")
            } else {
                base64Url
            }
            if (base64Data.isNotBlank()) {
                segments.add(MessageSegment.Image(base64Data = base64Data))
            }
        }

        // Add files in order
        for (file in fileAttachments) {
            segments.add(MessageSegment.File(
                filePath = file.filePath,
                fileName = file.filePath.substringAfterLast('/'),
                lineRange = file.lineRange,
                language = file.language
            ))
        }

        return segments
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
        if (conversationManager.getCurrentConversationId() == null) {
            conversationManager.loadLastConversation()
        }
        if (conversationManager.getCurrentConversationId() == null) {
            return conversationManager.createConversation()
        }
        return conversationManager.getCurrentConversationId()!!
    }

    /**
     * Build tool definitions for function calling.
     * Declares available tools to the model so it can request tool use.
     */
    private fun buildToolDefinitions(): List<ToolDefinition> {
        val json = Json { encodeDefaults = true }

        return listOf(
            // web_search tool
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "web_search",
                    description = "Search the web for current information on any topic",
                    parameters = json.parseToJsonElement("""
                        {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "The search query"
                                }
                            },
                            "required": ["query"]
                        }
                    """)
                )
            ),
            // read_file tool
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read the contents of a file at the specified path",
                    parameters = json.parseToJsonElement("""
                        {
                            "type": "object",
                            "properties": {
                                "path": {
                                    "type": "string",
                                    "description": "The absolute or relative path to the file"
                                }
                            },
                            "required": ["path"]
                        }
                    """)
                )
            ),
            // write_file tool
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "write_file",
                    description = "Write content to a file at the specified path, creating it if it doesn't exist",
                    parameters = json.parseToJsonElement("""
                        {
                            "type": "object",
                            "properties": {
                                "path": {
                                    "type": "string",
                                    "description": "The absolute or relative path to the file"
                                },
                                "content": {
                                    "type": "string",
                                    "description": "The content to write to the file"
                                }
                            },
                            "required": ["path", "content"]
                        }
                    """)
                )
            ),
            // terminal tool
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "terminal",
                    description = "Execute a shell command and return the output",
                    parameters = json.parseToJsonElement("""
                        {
                            "type": "object",
                            "properties": {
                                "command": {
                                    "type": "string",
                                    "description": "The shell command to execute"
                                },
                                "timeout": {
                                    "type": "integer",
                                    "description": "Maximum time in seconds to wait for the command to complete"
                                }
                            },
                            "required": ["command"]
                        }
                    """)
                )
            ),
            // search_files tool
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "search_files",
                    description = "Search for files by name pattern or search inside file contents using regex",
                    parameters = json.parseToJsonElement("""
                        {
                            "type": "object",
                            "properties": {
                                "pattern": {
                                    "type": "string",
                                    "description": "The glob pattern (for files) or regex pattern (for content)"
                                },
                                "target": {
                                    "type": "string",
                                    "enum": ["files", "content"],
                                    "description": "Whether to search for files or inside file contents"
                                },
                                "path": {
                                    "type": "string",
                                    "description": "The directory to search in"
                                }
                            },
                            "required": ["pattern", "target"]
                        }
                    """)
                )
            )
        )
    }

    companion object {
        fun getInstance(project: Project): HermesChatService =
            project.getService(HermesChatService::class.java)
    }
}
