package com.hermes.intellij.toolwindow

import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.model.CodeContext
import com.hermes.intellij.model.ConversationSummary
import com.hermes.intellij.model.FileAttachment
import com.hermes.intellij.model.ImageAttachment
import com.hermes.intellij.model.ImageContext
import com.hermes.intellij.model.MessageContent
import com.hermes.intellij.services.HermesChatService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Main chat panel - the composite root of the entire chat UI.
 * Holds conversation title bar (top), MessageListPanel (center) and InputPanel (bottom).
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    val messageListPanel = MessageListPanel(project)
    private var isStreaming = false
    val inputPanel: InputPanel

    private val titleLabel = JBLabel("Hermes AI").apply {
        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
        border = JBUI.Borders.empty(0, 2)
    }

    private val dropdownButton = JBLabel().apply {
        setIcon(IconLoader.getIcon("/icons/dropdown_16.svg", ChatPanel::class.java))
        foreground = com.intellij.ui.JBColor.GRAY
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 6, 2, 6)
        toolTipText = "Conversation history"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showConversationMenu(this@apply)
            }

            override fun mouseEntered(e: MouseEvent) {
                foreground = com.intellij.ui.JBColor.DARK_GRAY
            }

            override fun mouseExited(e: MouseEvent) {
                foreground = com.intellij.ui.JBColor.GRAY
            }
        })
    }

    private val titleBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(10), JBUI.scale(4), JBUI.scale(10))

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(dropdownButton)
            add(titleLabel)
        }

        add(leftPanel, BorderLayout.WEST)
    }

    init {
        inputPanel = InputPanel(
            project = project,
            onSend = { text -> onUserSubmit(text) },
            onCancel = { onCancelStreaming() }
        )

        add(titleBar, BorderLayout.NORTH)
        add(messageListPanel, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)

        // Load conversation asynchronously to avoid blocking EDT on startup
        initializeConversationAsync()
    }

    /**
     * Initialize conversation on a background thread to prevent IDE freeze.
     * Heavy work (loading from disk, image decoding, component creation) is done off-EDT,
     * then UI updates are dispatched back to EDT via invokeLater.
     */
    private fun initializeConversationAsync() {
        Thread {
            try {
                // 1. Load conversation data from disk (heavy I/O + image decoding)
                val chatService = HermesChatService.getInstance(project)
                chatService.ensureConversation()
                val title = chatService.getCurrentTitle()
                val messages = chatService.getCurrentConversationMessages()

                // 2. Update UI on EDT
                SwingUtilities.invokeLater {
                    if (!isDisplayable) return@invokeLater // Panel already disposed
                    updateTitleDisplay(title)
                    messageListPanel.loadMessages(messages)
                }
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(ChatPanel::class.java)
                    .warn("[ChatPanel] Failed to initialize conversation", e)
            }
        }.apply {
            isDaemon = true
            name = "Hermes-Init-Conversation"
            start()
        }
    }

    // ============================================================
    // Conversation menu
    // ============================================================

    private fun showConversationMenu(invoker: JComponent) {
        val chatService = HermesChatService.getInstance(project)
        val summaries = chatService.getConversationSummaries()
        val currentId = chatService.getCurrentConversationId()

        val popup = JPopupMenu()

        // Header: "History"
        popup.add(JMenuItem("  History").apply {
            isEnabled = false
            font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, 11f)
        })
        popup.addSeparator()

        val closeIcon = IconLoader.getIcon("/icons/close_16.svg", ChatPanel::class.java)

        // Calculate max width from all conversation titles
        val fontMetrics = getFontMetrics(JBUI.Fonts.label())
        val maxWidth = summaries.maxOf { fontMetrics.stringWidth(it.title) } + JBUI.scale(80)

        // Conversation list with delete icon for non-current items
        for (summary in summaries) {
            val isActive = summary.id == currentId
            val panel = JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(maxWidth, JBUI.scale(28))

                val label = JBLabel(if (isActive) "● ${summary.title}" else "   ${summary.title}").apply {
                    font = if (isActive) JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD) else JBUI.Fonts.label()
                }
                add(label, BorderLayout.CENTER)

                // Delete icon for non-current conversations
                if (!isActive) {
                    val deleteBtn = JButton(closeIcon).apply {
                        toolTipText = "Delete"
                        isFocusPainted = false
                        isBorderPainted = false
                        isContentAreaFilled = false
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        margin = JBUI.insets(0)
                        preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
                        verticalAlignment = SwingConstants.CENTER
                        horizontalAlignment = SwingConstants.CENTER
                    }
                    deleteBtn.addActionListener {
                        popup.isVisible = false
                        deleteConversation(summary.id)
                    }
                    add(deleteBtn, BorderLayout.EAST)
                }
            }

            // Click on the panel (not delete button) to switch conversation
            if (!isActive) {
                panel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        popup.isVisible = false
                        switchToConversation(summary.id)
                    }
                })
            }

            popup.add(panel)
        }

        popup.show(invoker, 0, invoker.height + JBUI.scale(4))
    }
    private fun createAndSwitchToNew() {
        // Prevent switching during streaming response
        if (isStreaming) {
            JOptionPane.showMessageDialog(
                this,
                "Please wait for the current response to complete before creating a new conversation.",
                "Response in Progress",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        messageListPanel.clear()
        val chatService = HermesChatService.getInstance(project)
        chatService.createNewConversation()
        updateTitleDisplay(chatService.getCurrentTitle())
    }

    private fun switchToConversation(id: String) {
        // Prevent switching during streaming response
        if (isStreaming) {
            JOptionPane.showMessageDialog(
                this,
                "Please wait for the current response to complete before switching conversations.",
                "Response in Progress",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        messageListPanel.clear()
        val chatService = HermesChatService.getInstance(project)
        chatService.switchConversation(id)
        updateTitleDisplay(chatService.getCurrentTitle())
        loadConversationMessages()
    }

    private fun deleteCurrentConversation() {
        val chatService = HermesChatService.getInstance(project)
        val currentId = chatService.getCurrentConversationId()
        if (currentId == null) return

        // Confirm deletion
        val result = JOptionPane.showConfirmDialog(
            this,
            "Delete this conversation?",
            "Delete Conversation",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result != JOptionPane.YES_OPTION) return

        messageListPanel.clear()
        chatService.deleteConversation(currentId)
        updateTitleDisplay(chatService.getCurrentTitle())
        loadConversationMessages()
    }

    private fun deleteConversation(id: String) {
        val chatService = HermesChatService.getInstance(project)
        val currentId = chatService.getCurrentConversationId()

        // Confirm deletion
        val result = JOptionPane.showConfirmDialog(
            this,
            "Delete this conversation?",
            "Delete Conversation",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result != JOptionPane.YES_OPTION) return

        chatService.deleteConversation(id)
        if (id == currentId) {
            messageListPanel.clear()
            loadConversationMessages()
        }
        updateTitleDisplay(chatService.getCurrentTitle())
    }

    private fun loadConversationMessages() {
        val chatService = HermesChatService.getInstance(project)
        val messages = chatService.getCurrentConversationMessages()
        messageListPanel.loadMessages(messages)
    }

    private fun updateTitleDisplay(title: String) {
        titleLabel.text = title.ifBlank { "Hermes AI" }
    }

    // ============================================================
    // Message sending
    // ============================================================

    private fun onUserSubmit(text: String) {
        // Consume contexts and files BEFORE building display text
        val contexts = inputPanel.consumeContexts()
        val files = inputPanel.consumeFiles()
        val images = inputPanel.consumeImages()

        // Build structured message content with attachments
        val messageContent = buildMessageContent(text, contexts, files, images)
        messageListPanel.addUserMessage(messageContent)
        messageListPanel.startAssistantMessage()
        inputPanel.setStreaming(true)
        isStreaming = true

        val chatService = HermesChatService.getInstance(project)
        chatService.sendMessage(text, contexts, files, images, this)

        // Update title bar after sending first message
        SwingUtilities.invokeLater {
            updateTitleDisplay(chatService.getCurrentTitle())
        }
    }

    /**
     * Build structured message content with separate text, images, and files.
     * This allows the UI to render them appropriately (clickable file links, image thumbnails).
     */
    private fun buildMessageContent(
        text: String,
        contexts: List<CodeContext>,
        files: List<FileAttachment>,
        images: List<ImageContext>
    ): MessageContent {
        // 合并代码上下文和文件附件
        val allFileAttachments = contexts.map { ctx ->
            FileAttachment(
                filePath = ctx.filePath,
                lineRange = ctx.lineRange,
                language = ctx.language,
                content = ctx.content
            )
        } + files
        
        val imageAttachments = images.map { img ->
            ImageAttachment(
                base64Data = img.base64Data,
                thumbnail = img.thumbnail
            )
        }
        return MessageContent(
            text = text,
            images = imageAttachments,
            files = allFileAttachments
        )
    }

    fun onCancelStreaming() {
        val chatService = HermesChatService.getInstance(project)
        chatService.cancelCurrentRequest()
        // 标记所有工具调用为中断状态
        messageListPanel.getStreamingBubble()?.cancelAllToolCalls()
        // 先追加取消提示，再 finalize（顺序不能颠倒：finalizeStreaming 会把 streamingBubble 置 null）
        messageListPanel.appendToken("\n\n*(Response cancelled)*")
        inputPanel.setStreaming(false)
        messageListPanel.finalizeStreaming()
        isStreaming = false
    }

    fun onTokenReceived(token: String) {
        messageListPanel.appendToken(token)
    }

    /**
     * 显示工具调用状态
     */
    fun onToolCall(toolName: String, status: String = "Running...") {
        messageListPanel.showToolCall(toolName, status)
    }

    /**
     * 标记工具调用完成
     */
    fun onToolCallComplete(toolName: String) {
        messageListPanel.completeToolCall(toolName)
    }

    /**
     * FIX: 添加幂等性检查，防止重复调用导致 UI 状态异常
     * 流式完成后，isStreaming 已经是 false，重复调用会直接返回
     */
    fun onStreamingComplete() {
        if (!isStreaming) {
            // 已经结束了，忽略重复调用
            return
        }
        isStreaming = false
        inputPanel.setStreaming(false)
        messageListPanel.finalizeStreaming()
    }

    fun onStreamingError(error: String) {
        isStreaming = false
        inputPanel.setStreaming(false)

        val currentBubble = messageListPanel.getStreamingBubble()
        if (currentBubble != null) {
            currentBubble.showError(error)
        } else {
            messageListPanel.addErrorMessage(error)
        }

        messageListPanel.finalizeStreaming()
    }

    fun newChat() {
        createAndSwitchToNew()
    }

    fun attachContext(context: CodeContext) {
        inputPanel.attachContext(context)
        inputPanel.focusInput()
    }

    fun replaceOrAddContext(context: CodeContext, predicate: (CodeContext) -> Boolean) {
        inputPanel.replaceOrAddContext(context, predicate)
    }
}
