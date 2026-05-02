package com.hermes.intellij.toolwindow

import com.hermes.intellij.api.models.ChatMessage
import com.hermes.intellij.api.models.ContentPart
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

    // Wrapper places messagesPanel at NORTH so it only takes its preferred height
    // instead of stretching to fill the entire scroll viewport
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
     * Used when switching conversations.
     * Only auto-scrolls to bottom if user was already at bottom, preserving scroll position otherwise.
     */
    fun loadMessages(messages: List<ChatMessage>) {
        // Remember if user was viewing the bottom (latest messages)
        val wasAtBottom = isUserAtBottom()
        
        clear()
        for (msg in messages) {
            if (msg.role == "system") continue  // Skip system messages in UI
            addHistoricalMessage(msg)
        }
        
        // Only auto-scroll if user was at bottom, otherwise preserve scroll position
        SwingUtilities.invokeLater {
            if (wasAtBottom) {
                val vsb = scrollPane.verticalScrollBar
                vsb.value = vsb.maximum
            }
            // If user was not at bottom, don't scroll - let them continue viewing
        }
    }

    /**
     * Check if the user is currently viewing the bottom of the chat.
     * Returns true if scroll position is within 100 pixels of the bottom.
     */
    private fun isUserAtBottom(): Boolean {
        val vsb = scrollPane.verticalScrollBar
        val maxScroll = vsb.maximum - vsb.visibleAmount
        val currentScroll = vsb.value
        // Consider "at bottom" if within 100 pixels
        return (maxScroll - currentScroll) < 100
    }

    /**
     * Add a historical (non-streaming) message bubble.
     * Converts ChatMessage to MessageContent for structured rendering.
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
    }

    /**
     * Convert ChatMessage to MessageContent, extracting images from contentParts
     * and files from fileAttachments.
     */
    private fun convertToMessageContent(msg: ChatMessage): MessageContent {
        val images = mutableListOf<ImageAttachment>()
        val files = mutableListOf<FileAttachment>()

        // Extract images from contentParts
        msg.contentParts?.forEach { part ->
            if (part is ContentPart.ImageUrl) {
                val base64Data = part.imageUrl.url
                if (base64Data.startsWith("data:image")) {
                    // Create a thumbnail from base64
                    try {
                        val imageBytes = Base64.getDecoder().decode(
                            base64Data.removePrefix("data:image/jpeg;base64,")
                                .removePrefix("data:image/png;base64,")
                                .removePrefix("data:image/jpg;base64,")
                        )
                        val image = Toolkit.getDefaultToolkit().createImage(imageBytes)
                        val thumbnail = ImageIcon(image.getScaledInstance(JBUI.scale(60), JBUI.scale(45), Image.SCALE_SMOOTH))
                        images.add(ImageAttachment(base64Data = base64Data, thumbnail = thumbnail))
                    } catch (e: Exception) {
                        // Image loading failed - add a placeholder with empty data
                        // MessageBubble will show "Image expired" message
                        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(MessageListPanel::class.java)
                        LOG.warn("Failed to load image from history: ${e.message}")
                        // Add placeholder with minimal valid data - bubble will detect and show expired message
                        images.add(ImageAttachment(
                            base64Data = "data:image/png;base64,", 
                            thumbnail = ImageIcon(
                                java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
                            )
                        ))
                    }
                } else if (base64Data.startsWith("hermes-image:") || base64Data.isBlank()) {
                    // Image reference not resolved or empty - add placeholder
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
                content = "" // 历史记录中的文件内容不需要显示
            ))
        }

        return MessageContent(
            text = msg.content,
            images = images,
            files = files
        )
    }

    fun addUserMessage(content: String): MessageBubble {
        return addUserMessage(MessageContent(text = content))
    }

    /**
     * Add a user message with structured content (text, images, files).
     */
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
            initialContent = "❌ 错误: $error",
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

    private fun revalidateAndScroll() {
        messagesPanel.revalidate()
        messagesPanel.repaint()

        SwingUtilities.invokeLater {
            val vsb = scrollPane.verticalScrollBar
            vsb.value = vsb.maximum
        }
    }
}
