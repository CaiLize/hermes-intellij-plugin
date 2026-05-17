package com.hermes.intellij.toolwindow

import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.api.models.ContentPart
import com.hermes.intellij.api.models.MessageSegment
import com.hermes.intellij.api.models.ToolCallRecord
import com.hermes.intellij.model.FileAttachment
import com.hermes.intellij.model.ImageAttachment
import com.hermes.intellij.model.MessageContent
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.Image
import java.awt.Toolkit
import javax.swing.*
import java.util.Base64

/**
 * Scrollable panel that holds a vertical list of MessageBubble components.
 */
class MessageListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val messagesPanel = object : JPanel() {
        init {
            layout = NoStretchVerticalLayout()
            border = JBUI.Borders.empty(0)
        }
    }

    private val wrapperPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(messagesPanel, BorderLayout.NORTH)
    }

    private val scrollPane = JBScrollPane(wrapperPanel).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }

    private var streamingBubble: MessageBubble? = null

    init {
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Load a list of historical messages into the panel.
     * Only auto-scrolls to bottom if user was already at bottom.
     */
    fun loadMessages(messages: List<ChatMessage>) {
        val wasAtBottom = isUserAtBottom()
        
        clear()
        for (msg in messages) {
            if (msg.role == "system") continue
            addHistoricalMessage(msg)
        }
        
        SwingUtilities.invokeLater {
            if (wasAtBottom) {
                val vsb = scrollPane.verticalScrollBar
                vsb.value = vsb.maximum
            }
        }
    }

    private fun isUserAtBottom(): Boolean {
        val vsb = scrollPane.verticalScrollBar
        val maxScroll = vsb.maximum - vsb.visibleAmount
        val currentScroll = vsb.value
        return (maxScroll - currentScroll) < 100
    }

    /**
     * Add a historical message bubble.
     * Converts ChatMessage to MessageContent and restores content segments if present.
     * Supports both legacy toolCalls format and new segments format.
     */
    private fun addHistoricalMessage(msg: ChatMessage) {
        val messageContent = convertToMessageContent(msg)
        var bubbleRef: MessageBubble? = null
        val bubble = MessageBubble(
            project = project,
            role = msg.role,
            initialContent = messageContent,
            onDelete = { bubbleRef?.let { removeMessage(it) } }
        )
        bubbleRef = bubble
        bubble.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(bubble)
        
        // Restore content segments for assistant messages
        // Priority: segments (new format) > toolCalls (legacy format)
        if (msg.role == "assistant") {
            if (msg.segments != null && msg.segments.isNotEmpty()) {
                // New format: restore from ordered segments
                restoreFromSegments(bubble, msg.segments)
            } else if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                // Legacy format: restore tool calls at the end
                val toolCallHistory = msg.toolCalls.map { record ->
                    MessageBubble.Companion.ToolCallEntry(
                        name = record.name,
                        status = if (record.completed) "completed" else record.status,
                        completed = record.completed
                    )
                }
                bubble.restoreToolCallHistory(toolCallHistory)
            }
        }
    }

    /**
     * Restore message content from ordered segments.
     * This preserves the exact chronological order of text, tool calls, images, and files.
     */
    private fun restoreFromSegments(bubble: MessageBubble, segments: List<MessageSegment>) {
        // Let MessageBubble handle the restoration via its restoreFromSegments method
        bubble.restoreFromSegments(segments)
    }

    /**
     * Convert ChatMessage to MessageContent.
     * Extracts text from content field or contentParts, images from contentParts,
     * and files from fileAttachments.
     * Also considers segments for text extraction.
     */
    private fun convertToMessageContent(msg: ChatMessage): MessageContent {
        val images = mutableListOf<ImageAttachment>()
        val files = mutableListOf<FileAttachment>()

        // Extract text: prefer segments, then content field, then contentParts
        var text = msg.content
        
        // If segments exist, extract text from text segments
        if (msg.segments != null && msg.segments.isNotEmpty()) {
            text = msg.segments.filterIsInstance<MessageSegment.Text>()
                .joinToString("") { it.content }
        }
        
        // If content is empty but contentParts exists, extract text from parts
        if (text.isNullOrBlank() && msg.contentParts != null) {
            text = msg.contentParts
                .filterIsInstance<ContentPart.Text>()
                .joinToString("\n") { it.text }
        }

        // Extract images from contentParts
        msg.contentParts?.forEach { part ->
            if (part is ContentPart.ImageUrl) {
                val base64Data = part.imageUrl.url
                if (base64Data.startsWith("data:image")) {
                    try {
                        val imageBytes = Base64.getDecoder().decode(
                            base64Data.removePrefix("data:image/jpeg;base64,")
                                .removePrefix("data:image/png;base64,")
                                .removePrefix("data:image/jpg;base64,")
                        )
                        val image = Toolkit.getDefaultToolkit().createImage(imageBytes)
                        val thumbnail = ImageIcon(image.getScaledInstance(
                            JBUI.scale(60), JBUI.scale(45), Image.SCALE_SMOOTH))
                        images.add(ImageAttachment(base64Data = base64Data, thumbnail = thumbnail))
                    } catch (e: Exception) {
                        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(MessageListPanel::class.java)
                        LOG.warn("Failed to load image from history: ${e.message}")
                        images.add(ImageAttachment(
                            base64Data = "data:image/png;base64,", 
                            thumbnail = ImageIcon(
                                java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
                            )
                        ))
                    }
                } else if (base64Data.startsWith("hermes-image:") || base64Data.isBlank()) {
                    images.add(ImageAttachment(
                        base64Data = "", 
                        thumbnail = ImageIcon(
                            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
                        )
                    ))
                }
            }
        }

        // Extract files from fileAttachments
        msg.fileAttachments?.forEach { fa ->
            files.add(FileAttachment(
                filePath = fa.filePath,
                lineRange = fa.lineRange,
                language = fa.language,
                content = ""
            ))
        }

        return MessageContent(
            text = text,
            images = images,
            files = files
        )
    }

    fun addUserMessage(content: String): MessageBubble {
        return addUserMessage(MessageContent(text = content))
    }

    fun addUserMessage(content: MessageContent): MessageBubble {
        var bubbleRef: MessageBubble? = null
        val bubble = MessageBubble(
            project = project,
            role = "user",
            initialContent = content,
            onDelete = { bubbleRef?.let { removeMessage(it) } }
        )
        bubbleRef = bubble
        bubble.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(bubble)
        revalidateAndScroll()
        return bubble
    }

    fun startAssistantMessage(): MessageBubble {
        val bubble = MessageBubble(project = project, role = "assistant")
        bubble.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(bubble)
        streamingBubble = bubble
        revalidateAndScroll()
        return bubble
    }

    fun appendToken(token: String) {
        streamingBubble?.appendToken(token)
        revalidateAndScroll()
    }

    fun showToolCall(toolName: String, status: String = "Running...") {
        streamingBubble?.showToolCall(toolName, status)
        revalidateAndScroll()
    }

    fun completeToolCall(toolName: String) {
        streamingBubble?.completeToolCall(toolName)
        revalidateAndScroll()
    }

    fun cancelToolCall(toolName: String) {
        streamingBubble?.cancelToolCall(toolName)
        revalidateAndScroll()
    }

    fun finalizeStreaming() {
        streamingBubble?.finalizeMessage()
        streamingBubble = null
        revalidateAndScroll()
    }

    fun addErrorMessage(error: String): MessageBubble {
        var bubbleRef: MessageBubble? = null
        val bubble = MessageBubble(
            project = project,
            role = "assistant",
            initialContent = "❌ 错误：$error",
            onDelete = { bubbleRef?.let { removeMessage(it) } }
        )
        bubbleRef = bubble
        bubble.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(bubble)
        revalidateAndScroll()
        return bubble
    }

    fun removeMessage(bubble: MessageBubble) {
        messagesPanel.remove(bubble)
        revalidateAndScroll()
    }

    fun clear() {
        messagesPanel.removeAll()
        streamingBubble = null
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    fun getStreamingBubble(): MessageBubble? = streamingBubble
    
    /**
     * Get the segments from the streaming bubble for persistence.
     */
    fun getStreamingSegments(): List<MessageSegment>? {
        return streamingBubble?.getMessageSegments()
    }

    private fun revalidateAndScroll() {
        messagesPanel.revalidate()
        messagesPanel.repaint()

        SwingUtilities.invokeLater {
            val vsb = scrollPane.verticalScrollBar
            vsb.value = vsb.maximum
        }
    }
}
