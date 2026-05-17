package com.hermes.intellij.toolwindow

import com.hermes.intellij.api.models.MessageSegment
import com.hermes.intellij.model.FileAttachment
import com.hermes.intellij.model.ImageAttachment
import com.hermes.intellij.model.MessageContent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.Timer
import java.util.Base64

/**
 * Renders a single chat message in a compact card layout.
 * Uses contentSegments for chronological ordering of text, tool calls, images, and files.
 */
class MessageBubble(
    private val project: Project,
    private val role: String,
    initialContent: Any = "",
    private val timestamp: Long = System.currentTimeMillis(),
    private val onDelete: (() -> Unit)? = null
) : JPanel() {

    private val messageContent: MessageContent
    private var renderTimer: Timer? = null

    enum class State { THINKING, TOOL_CALLING, STREAMING, COMPLETE }
    private var state: State
    private var thinkingTimer: Timer? = null
    private var fullText = StringBuilder()
    
    // Unified content segments list - stores all content in chronological order
    private val contentSegments = mutableListOf<ContentSegment>()
    
    private val contentPanel = object : JPanel() {
        init {
            layout = NoStretchVerticalLayout()
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            alignmentY = Component.TOP_ALIGNMENT
            minimumSize = Dimension(0, 0)
        }
    }

    private val renderer = MarkdownRenderer

    companion object {
        const val RENDER_DELAY = 150
        data class ToolCallEntry(val name: String, val status: String, val completed: Boolean)
        
        /** Unified content segment types - supports all content types in chronological order */
        sealed class ContentSegment {
            data class Text(val content: StringBuilder) : ContentSegment()
            data class ToolCall(
                val name: String, 
                val status: String, 
                val completed: Boolean = false, 
                val cancelled: Boolean = false
            ) : ContentSegment()
            data class Image(val base64Data: String, val mimeType: String = "image/png") : ContentSegment()
            data class File(
                val filePath: String,
                val fileName: String,
                val lineRange: String? = null,
                val language: String = ""
            ) : ContentSegment()
        }
    }

    init {
        messageContent = when (initialContent) {
            is MessageContent -> initialContent
            is String -> MessageContent(text = initialContent)
            else -> MessageContent(text = initialContent.toString())
        }

        fullText = StringBuilder(messageContent.text)
        
        // Initialize with text content if present
        if (messageContent.text.isNotBlank()) {
            contentSegments.add(ContentSegment.Text(StringBuilder(messageContent.text)))
        }
        
        state = if (messageContent.text.isEmpty() && role == "assistant" && messageContent.images.isEmpty() && messageContent.files.isEmpty()) {
            State.THINKING
        } else {
            State.COMPLETE
        }

        layout = NoStretchVerticalLayout()
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ChatColors.separator),
            JBUI.Borders.empty(JBUI.scale(8), JBUI.scale(12), JBUI.scale(8), JBUI.scale(12))
        )

        val headerPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        leftPanel.add(AvatarPanel(role))
        leftPanel.add(JBLabel(if (role == "user") "You" else "Hermes").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            foreground = if (role == "user") ChatColors.userBlue else ChatColors.hermesPurple
        })
        leftPanel.add(JBLabel(SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(timestamp))).apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.timestampGray
        })
        headerPanel.add(leftPanel, BorderLayout.WEST)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        actionsPanel.add(createActionButton(IconLoader.getIcon("/icons/copy_16.svg", MessageBubble::class.java), "Copy") {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(fullText.toString()), null)
        })
        if (onDelete != null) {
            actionsPanel.add(createActionButton(IconLoader.getIcon("/icons/delete_16.svg", MessageBubble::class.java), "Delete") { onDelete.invoke() })
        }
        headerPanel.add(actionsPanel, BorderLayout.EAST)

        val contentWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(20))
        }
        contentWrapper.add(contentPanel, BorderLayout.NORTH)
        headerPanel.alignmentX = Component.LEFT_ALIGNMENT
        contentWrapper.alignmentX = Component.LEFT_ALIGNMENT
        add(headerPanel)
        add(contentWrapper)

        when (state) {
            State.THINKING -> renderThinking()
            State.COMPLETE -> renderContent()
            else -> {}
        }
    }

    fun appendToken(token: String) {
        if (state == State.THINKING || state == State.TOOL_CALLING) {
            state = State.STREAMING
            thinkingTimer?.stop()
        }
        fullText.append(token)
        
        // Append to last text segment or create new one
        val lastSegment = contentSegments.lastOrNull()
        if (lastSegment is ContentSegment.Text) {
            lastSegment.content.append(token)
        } else {
            contentSegments.add(ContentSegment.Text(StringBuilder(token)))
        }
        
        renderTimer?.stop()
        if (fullText.length < 200) {
            renderStreamingContent()
        } else {
            renderTimer = Timer(RENDER_DELAY) { renderStreamingContent(); renderTimer = null }.apply { isRepeats = false; start() }
        }
    }

    private fun renderStreamingContent() {
        contentPanel.removeAll()
        contentSegments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> {
                    if (segment.content.isNotEmpty()) {
                        val components = renderer.renderToComponents(segment.content.toString(), project, getRenderWidth())
                        components.forEach { contentPanel.add(it) }
                    }
                }
                is ContentSegment.ToolCall -> contentPanel.add(createToolCallComponent(segment))
                is ContentSegment.Image -> contentPanel.add(createImagePreviewFromBase64(segment.base64Data, segment.mimeType))
                is ContentSegment.File -> contentPanel.add(createFileChipFromSegment(segment))
            }
        }
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    fun finalizeMessage() {
        state = State.COMPLETE
        thinkingTimer?.stop()
        renderTimer?.stop()
        renderTimer = null
        renderContent()
    }

    fun showError(error: String) {
        state = State.COMPLETE
        thinkingTimer?.stop()
        renderTimer?.stop()
        renderTimer = null
        fullText = StringBuilder("Error: $error")
        contentSegments.add(ContentSegment.Text(StringBuilder("\n\nError: $error")))
        renderContent()
    }

    fun getText(): String = fullText.toString()

    private fun getRenderWidth(): Int {
        if (contentPanel.width > 0) return contentPanel.width
        var parent = contentPanel.parent
        while (parent != null) {
            if (parent.width > 0) return (parent.width - JBUI.scale(20)).coerceAtLeast(200)
            parent = parent.parent
        }
        return 500
    }

    private fun renderThinking() {
        contentPanel.removeAll()
        val thinkingPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        thinkingPanel.add(JBLabel("Hermes thinking").apply {
            font = JBUI.Fonts.label().deriveFont(Font.ITALIC)
            foreground = ChatColors.timestampGray
        })
        val dotsLabel = object : JBLabel("") {
            private var dotCount = 0
            init { thinkingTimer = Timer(500) { dotCount = (dotCount + 1) % 4; text = ".".repeat(dotCount); repaint() }.apply { start() } }
            override fun removeNotify() { super.removeNotify(); thinkingTimer?.stop() }
        }.apply { font = JBUI.Fonts.label().deriveFont(Font.ITALIC); foreground = ChatColors.timestampGray }
        thinkingPanel.add(dotsLabel)
        contentPanel.add(thinkingPanel)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    /**
     * Show a tool call status indicator - appended at end for chronological order
     */
    fun showToolCall(toolName: String, status: String = "Running...") {
        if (state == State.THINKING || state == State.TOOL_CALLING) {
            state = State.TOOL_CALLING
            thinkingTimer?.stop()
        }
        
        // Check for existing incomplete tool call with same name
        val existingIndex = contentSegments.indexOfLast { seg ->
            seg is ContentSegment.ToolCall && seg.name == toolName && !seg.completed && !seg.cancelled
        }
        
        if (existingIndex >= 0) {
            val existing = contentSegments[existingIndex] as ContentSegment.ToolCall
            contentSegments[existingIndex] = existing.copy(status = status)
        } else {
            contentSegments.add(ContentSegment.ToolCall(toolName, status, false, false))
        }
        
        renderStreamingContent()
    }

    /**
     * Mark a tool call as completed
     */
    fun completeToolCall(toolName: String) {
        val lastIndex = contentSegments.indexOfLast { seg ->
            seg is ContentSegment.ToolCall && seg.name == toolName && !seg.completed && !seg.cancelled
        }
        
        if (lastIndex >= 0) {
            val existing = contentSegments[lastIndex] as ContentSegment.ToolCall
            contentSegments[lastIndex] = existing.copy(completed = true)
            renderStreamingContent()
        }
    }

    /**
     * Cancel a tool call - mark as cancelled
     */
    fun cancelToolCall(toolName: String) {
        val lastIndex = contentSegments.indexOfLast { seg ->
            seg is ContentSegment.ToolCall && seg.name == toolName && !seg.completed && !seg.cancelled
        }
        
        if (lastIndex >= 0) {
            val existing = contentSegments[lastIndex] as ContentSegment.ToolCall
            contentSegments[lastIndex] = existing.copy(cancelled = true, status = "Cancelled")
            renderStreamingContent()
        }
    }

    private fun getToolIcon(toolName: String): String = when {
        toolName.contains("search", ignoreCase = true) -> "🔍"
        toolName.contains("read", ignoreCase = true) -> "📖"
        toolName.contains("write", ignoreCase = true) || toolName.contains("create", ignoreCase = true) -> "✍️"
        toolName.contains("delete", ignoreCase = true) -> "🗑️"
        toolName.contains("skill", ignoreCase = true) -> "🎯"
        toolName.contains("terminal", ignoreCase = true) || toolName.contains("shell", ignoreCase = true) -> "💻"
        toolName.contains("file", ignoreCase = true) -> "📁"
        toolName.contains("web", ignoreCase = true) || toolName.contains("http", ignoreCase = true) -> "🌐"
        toolName.contains("cron", ignoreCase = true) -> "⏰"
        toolName.contains("delegate", ignoreCase = true) -> "👥"
        else -> "⚙️"
    }

    private fun formatToolName(toolName: String): String = 
        toolName.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun createToolCallComponent(toolCall: ContentSegment.ToolCall): JComponent {
        val statusText = when {
            toolCall.cancelled -> "⚠️ ${formatToolName(toolCall.name)} (Interrupted)"
            toolCall.completed -> "✓ ${formatToolName(toolCall.name)}"
            else -> "${getToolIcon(toolCall.name)} ${formatToolName(toolCall.name)}: ${toolCall.status}"
        }
        
        val color = when {
            toolCall.cancelled -> Color(255, 140, 0)
            toolCall.completed -> Color(100, 180, 100)
            else -> ChatColors.actionIcon
        }
        
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(JBUI.scale(4), 0)
            add(JBLabel(statusText).apply {
                font = JBUI.Fonts.smallFont()
                foreground = color
            })
        }
    }

    /**
     * Final render - displays all content in chronological order
     */
    private fun renderContent() {
        contentPanel.removeAll()
        
        contentSegments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> {
                    if (segment.content.isNotEmpty()) {
                        val components = renderer.renderToComponents(segment.content.toString(), project, getRenderWidth())
                        components.forEach { contentPanel.add(it) }
                    }
                }
                is ContentSegment.ToolCall -> contentPanel.add(createToolCallComponent(segment))
                is ContentSegment.Image -> contentPanel.add(createImagePreviewFromBase64(segment.base64Data, segment.mimeType))
                is ContentSegment.File -> contentPanel.add(createFileChipFromSegment(segment))
            }
        }
        
        // Fallback for images/files from messageContent if not in segments
        if (contentSegments.none { it is ContentSegment.Image }) {
            messageContent.images.forEach { contentPanel.add(createImagePreview(it)) }
        }
        if (contentSegments.none { it is ContentSegment.File }) {
            messageContent.files.forEach { contentPanel.add(createFileChip(it)) }
        }
        
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createImagePreview(img: ImageAttachment): JComponent {
        val isValidImage = img.base64Data.isNotBlank() && img.base64Data.startsWith("data:image") && 
            img.base64Data.length > 50 && img.thumbnail.iconWidth > 0 && img.thumbnail.iconHeight > 0
        if (!isValidImage) {
            return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel("🖼️ Image expired or unavailable").apply { 
                    font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
                    foreground = ChatColors.timestampGray
                    border = JBUI.Borders.empty(4, 8) 
                })
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
            }
        }
        val thumbnailLabel = JLabel(img.thumbnail).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to view full image"
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { showFullImage(img.base64Data) } })
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(thumbnailLabel)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        }
    }

    private fun createImagePreviewFromBase64(base64Data: String, mimeType: String): JComponent {
        val fullData = if (base64Data.startsWith("data:")) base64Data else "data:$mimeType;base64,$base64Data"
        if (base64Data.isBlank() || base64Data.length < 50) {
            return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel("🖼️ Image expired or unavailable").apply { 
                    font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
                    foreground = ChatColors.timestampGray
                    border = JBUI.Borders.empty(4, 8) 
                })
            }
        }
        try {
            val cleanData = base64Data.removePrefix("data:$mimeType;base64,")
            val imageBytes = Base64.getDecoder().decode(cleanData)
            val image = Toolkit.getDefaultToolkit().createImage(imageBytes)
            val thumbnail = ImageIcon(image.getScaledInstance(JBUI.scale(60), JBUI.scale(45), Image.SCALE_SMOOTH))
            val thumbnailLabel = JLabel(thumbnail).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Click to view full image"
                addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { showFullImage(fullData) } })
            }
            return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(thumbnailLabel)
            }
        } catch (e: Exception) {
            return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel("🖼️ Failed to load image").apply { 
                    font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
                    foreground = ChatColors.timestampGray
                    border = JBUI.Borders.empty(4, 8) 
                })
            }
        }
    }

    private fun showFullImage(base64Data: String) { ImagePreviewDialog.showFromBase64(this, base64Data) }

    private fun createFileChipFromSegment(segment: ContentSegment.File): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        }
        panel.add(JLabel(getFileTypeIcon(segment.filePath)).apply { verticalAlignment = SwingConstants.CENTER })
        val fileText = "${segment.fileName}${if (segment.lineRange != null) " (${segment.lineRange})" else ""}"
        val textLabel = JLabel(fileText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.actionIcon
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = segment.filePath
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { openSourceFile(segment.filePath, segment.lineRange) }
                override fun mouseEntered(e: MouseEvent) { text = "<html><u>$fileText</u></html>" }
                override fun mouseExited(e: MouseEvent) { text = fileText }
            })
        }
        panel.add(textLabel)
        return panel
    }

    private fun createFileChip(file: FileAttachment): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        }
        panel.add(JLabel(getFileTypeIcon(file.filePath)).apply { verticalAlignment = SwingConstants.CENTER })
        val fileText = "${file.fileName}${if (file.lineRange != null) " (${file.lineRange})" else ""}"
        val textLabel = JLabel(fileText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.actionIcon
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = file.filePath
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { openSourceFile(file.filePath, file.lineRange) }
                override fun mouseEntered(e: MouseEvent) { text = "<html><u>$fileText</u></html>" }
                override fun mouseExited(e: MouseEvent) { text = fileText }
            })
        }
        panel.add(textLabel)
        return panel
    }

    private fun openSourceFile(filePath: String, lineRange: String?) {
        try {
            val normalizedPath = filePath.replace("\\", "/")
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath) 
                ?: LocalFileSystem.getInstance().findFileByIoFile(File(filePath))
            if (virtualFile == null) { 
                JOptionPane.showMessageDialog(this, "File not found: $filePath", "Open File", JOptionPane.ERROR_MESSAGE)
                return 
            }
            val descriptor = if (lineRange != null) {
                val startLine = lineRange.split("-").first().trim().toIntOrNull()?.minus(1) ?: 0
                OpenFileDescriptor(project, virtualFile, startLine, 0)
            } else OpenFileDescriptor(project, virtualFile)
            descriptor.navigate(true)
        } catch (e: Exception) { 
            JOptionPane.showMessageDialog(this, "Failed to open file: ${e.message}", "Open File", JOptionPane.ERROR_MESSAGE) 
        }
    }

    private fun getFileTypeIcon(filePath: String): Icon {
        val ext = filePath.substringAfterLast(".").lowercase()
        return when (ext) {
            "kt", "kts" -> IconLoader.getIcon("/fileTypes/kotlin.svg", javaClass)
            "java" -> IconLoader.getIcon("/fileTypes/java.svg", javaClass)
            "py" -> IconLoader.getIcon("/fileTypes/python.svg", javaClass)
            "js", "ts", "tsx", "jsx" -> IconLoader.getIcon("/fileTypes/javaScript.svg", javaClass)
            "html", "htm" -> IconLoader.getIcon("/fileTypes/html.svg", javaClass)
            "css", "scss", "sass" -> IconLoader.getIcon("/fileTypes/css.svg", javaClass)
            "xml" -> IconLoader.getIcon("/fileTypes/xml.svg", javaClass)
            "json" -> IconLoader.getIcon("/fileTypes/json.svg", javaClass)
            "md" -> IconLoader.getIcon("/fileTypes/markdown.svg", javaClass)
            else -> IconLoader.getIcon("/fileTypes/text.svg", javaClass)
        }
    }

    private fun createActionButton(icon: Icon, toolTip: String, action: () -> Unit): JButton = JButton(icon).apply {
        this.toolTipText = toolTip
        font = JBUI.Fonts.smallFont()
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(0)
        preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
        addActionListener { action() }
    }

    private inner class AvatarPanel(private val avatarRole: String) : JPanel() {
        private val size = ChatColors.avatarSize
        private val icon: Icon
        private val iconScale = 2.0
        
        init {
            isOpaque = false
            preferredSize = Dimension(size, size)
            maximumSize = Dimension(size, size)
            minimumSize = Dimension(size, size)
            icon = if (avatarRole == "user") {
                IconLoader.getIcon("/icons/user_13.svg", MessageBubble::class.java)
            } else {
                IconLoader.getIcon("/icons/hermes_13.svg", MessageBubble::class.java)
            }
        }
        
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            
            // Draw background circle
            g2.color = if (avatarRole == "user") ChatColors.userBlue else ChatColors.hermesPurple
            val margin = 1
            g2.fillOval(margin, margin, size - margin * 2, size - margin * 2)
            
            // Draw icon centered in the circle at 2x size
            val iconWidth = (icon.iconWidth * iconScale).toInt()
            val iconHeight = (icon.iconHeight * iconScale).toInt()
            val x = (size - iconWidth) / 2
            val y = (size - iconHeight) / 2
            
            // Simple approach: draw scaled icon at calculated position
            val originalTransform = g2.transform
            g2.translate(x.toDouble(), y.toDouble())
            g2.scale(iconScale, iconScale)
            icon.paintIcon(this, g2, 0, 0)
            g2.transform = originalTransform
        }
    }

    /**
     * Get ordered content segments for persistence
     */
    fun getMessageSegments(): List<MessageSegment> {
        return contentSegments.map { segment ->
            when (segment) {
                is ContentSegment.Text -> MessageSegment.Text(segment.content.toString())
                is ContentSegment.ToolCall -> MessageSegment.ToolCall(
                    name = segment.name,
                    status = if (segment.completed) "completed" else segment.status,
                    completed = segment.completed
                )
                is ContentSegment.Image -> MessageSegment.Image(
                    base64Data = segment.base64Data,
                    mimeType = segment.mimeType
                )
                is ContentSegment.File -> MessageSegment.File(
                    filePath = segment.filePath,
                    fileName = segment.fileName,
                    lineRange = segment.lineRange,
                    language = segment.language
                )
            }
        }
    }

    /**
     * Restore content from ordered segments - preserves chronological order
     */
    fun restoreFromSegments(segments: List<MessageSegment>) {
        contentSegments.clear()
        fullText = StringBuilder()
        
        for (segment in segments) {
            when (segment) {
                is MessageSegment.Text -> {
                    contentSegments.add(ContentSegment.Text(StringBuilder(segment.content)))
                    fullText.append(segment.content)
                }
                is MessageSegment.ToolCall -> {
                    contentSegments.add(ContentSegment.ToolCall(
                        name = segment.name,
                        status = segment.status,
                        completed = segment.completed,
                        cancelled = false
                    ))
                }
                is MessageSegment.Image -> {
                    contentSegments.add(ContentSegment.Image(
                        base64Data = segment.base64Data,
                        mimeType = segment.mimeType
                    ))
                }
                is MessageSegment.File -> {
                    contentSegments.add(ContentSegment.File(
                        filePath = segment.filePath,
                        fileName = segment.fileName,
                        lineRange = segment.lineRange,
                        language = segment.language
                    ))
                }
            }
        }
        
        renderContent()
    }

    /**
     * Legacy method for backward compatibility
     */
    fun restoreToolCallHistory(history: List<ToolCallEntry>) {
        for (entry in history) {
            contentSegments.add(ContentSegment.ToolCall(
                name = entry.name,
                status = entry.status,
                completed = entry.completed,
                cancelled = false
            ))
        }
        renderContent()
    }

    /**
     * Mark all incomplete tool calls as cancelled
     */
    fun cancelAllToolCalls() {
        var changed = false
        for (i in contentSegments.indices) {
            val seg = contentSegments[i]
            if (seg is ContentSegment.ToolCall && !seg.completed && !seg.cancelled) {
                contentSegments[i] = seg.copy(cancelled = true, status = "Interrupted")
                changed = true
            }
        }
        if (changed) renderContent()
    }

    /**
     * Mark all tool calls as complete
     */
    fun markAllToolCallsComplete() {
        var changed = false
        for (i in contentSegments.indices) {
            val seg = contentSegments[i]
            if (seg is ContentSegment.ToolCall && !seg.completed && !seg.cancelled) {
                contentSegments[i] = seg.copy(completed = true)
                changed = true
            }
        }
        if (changed) renderContent()
    }

    /**
     * Legacy method for backward compatibility
     */
    fun getToolCallHistory(): List<ToolCallEntry> {
        return contentSegments.filterIsInstance<ContentSegment.ToolCall>().map {
            ToolCallEntry(it.name, it.status, it.completed)
        }
    }
}
