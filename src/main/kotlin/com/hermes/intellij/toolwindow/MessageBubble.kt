package com.hermes.intellij.toolwindow

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
 * Renders a single chat message in a compact card layout:
 * [Avatar] [Name] [Timestamp]          [Action buttons]
 *          Content (markdown rendered)
 *          [Image thumbnails]
 *          [File chips]
 * ─────────────── separator ───────────────
 */
class MessageBubble(
    private val project: Project,
    private val role: String,
    initialContent: Any = "",  // String or MessageContent
    private val timestamp: Long = System.currentTimeMillis(),
    private val onDelete: (() -> Unit)? = null
) : JPanel() {

    // Internal content storage
    private val messageContent: MessageContent

    // 流式渲染防抖定时器（避免频繁重建 Swing 组件树）
    private var renderTimer: Timer? = null
    private companion object {
        const val RENDER_DELAY = 150 // ms
    }

    // 消息状态枚举
    enum class State { THINKING, STREAMING, COMPLETE }
    private var state: State

    // 思考动画定时器
    private var thinkingTimer: Timer? = null
    private var fullText = StringBuilder()

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

    init {
        // Handle both String and MessageContent
        messageContent = when (initialContent) {
            is MessageContent -> initialContent
            is String -> MessageContent(text = initialContent)
            else -> MessageContent(text = initialContent.toString())
        }

        fullText = StringBuilder(messageContent.text)

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

        // Header row
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        // Left side: avatar + name + timestamp
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }

        leftPanel.add(AvatarPanel(role))

        val nameLabel = JBLabel(if (role == "user") "You" else "Hermes").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            foreground = if (role == "user") ChatColors.userBlue else ChatColors.hermesPurple
        }
        leftPanel.add(nameLabel)

        val timeLabel = JBLabel(SimpleDateFormat("HH:mm").format(Date(timestamp))).apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.timestampGray
        }
        leftPanel.add(timeLabel)

        headerPanel.add(leftPanel, BorderLayout.WEST)

        // Right side: action buttons — both user and assistant can copy
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
        }

        actionsPanel.add(createActionButton(IconLoader.getIcon("/icons/copy_16.svg", MessageBubble::class.java), "Copy") {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(fullText.toString()), null)
        })

        if (onDelete != null) {
            actionsPanel.add(createActionButton(IconLoader.getIcon("/icons/delete_16.svg", MessageBubble::class.java), "Delete") {
                onDelete.invoke()
            })
        }

        headerPanel.add(actionsPanel, BorderLayout.EAST)

        // Content panel with left indent to align under name
        val contentWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(30))
        }
        contentWrapper.add(contentPanel, BorderLayout.NORTH)

        headerPanel.alignmentX = Component.LEFT_ALIGNMENT
        contentWrapper.alignmentX = Component.LEFT_ALIGNMENT

        add(headerPanel)
        add(contentWrapper)

        // 根据状态渲染初始内容
        when (state) {
            State.THINKING -> renderThinking()
            State.COMPLETE -> if (messageContent.text.isNotBlank() || messageContent.images.isNotEmpty() || messageContent.files.isNotEmpty()) renderContent()
            else -> {} // STREAMING 由 appendToken 处理
        }

    }

    fun appendToken(token: String) {
        // 第一次收到 token，从 THINKING 转为 STREAMING
        if (state == State.THINKING) {
            state = State.STREAMING
            thinkingTimer?.stop()
            contentPanel.removeAll()
        }
        fullText.append(token)

        // 防抖渲染：短文本立即渲染以保持响应感，长文本用定时器节流
        renderTimer?.stop()
        if (fullText.length < 200) {
            renderStreamingContent()
        } else {
            renderTimer = Timer(RENDER_DELAY) {
                renderStreamingContent()
                renderTimer = null
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /**
     * 流式阶段渲染：使用 MarkdownRenderer 渲染当前累积的文本，
     * 确保流式和完成后的显示样式一致。
     */
    private fun renderStreamingContent() {
        contentPanel.removeAll()
        val textToRender = fullText.toString()
        if (textToRender.isNotBlank()) {
            val renderWidth = getRenderWidth()
            val components = renderer.renderToComponents(textToRender, project, renderWidth)
            for (component in components) {
                component.alignmentX = Component.LEFT_ALIGNMENT
                contentPanel.add(component)
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
        // 完成后做完整 Markdown 渲染
        renderContent()
    }

    fun showError(error: String) {
        state = State.COMPLETE
        thinkingTimer?.stop()
        renderTimer?.stop()
        renderTimer = null
        fullText = StringBuilder("Error: $error")
        renderContent()
    }

    fun getText(): String = fullText.toString()

    /**
     * 获取渲染宽度，确保在 contentPanel 宽度未计算时也能返回合理的值。
     * 优先使用 contentPanel 的实际宽度，如果为 0 则向上查找父容器。
     */
    private fun getRenderWidth(): Int {
        var width = contentPanel.width
        if (width > 0) return width

        // 向上遍历父容器获取可用宽度
        var parent = contentPanel.parent
        while (parent != null) {
            if (parent.width > 0) {
                // 减去 contentWrapper 的 30px 左缩进
                return (parent.width - JBUI.scale(30)).coerceAtLeast(200)
            }
            parent = parent.parent
        }

        // 最终回退：使用工具窗口的标准宽度
        return 500
    }

    private fun renderThinking() {
        contentPanel.removeAll()

        val thinkingPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }

        val thinkingLabel = JBLabel("Hermes thinking").apply {
            font = JBUI.Fonts.label().deriveFont(Font.ITALIC)
            foreground = ChatColors.timestampGray
        }
        thinkingPanel.add(thinkingLabel)

        // 动画点
        val dotsLabel = object : JBLabel("") {
            private var dotCount = 0
            init {
                thinkingTimer = Timer(500) {
                    dotCount = (dotCount + 1) % 4
                    text = ".".repeat(dotCount)
                    repaint()
                }.apply { start() }
            }

            override fun removeNotify() {
                super.removeNotify()
                thinkingTimer?.stop()
            }
        }.apply {
            font = JBUI.Fonts.label().deriveFont(Font.ITALIC)
            foreground = ChatColors.timestampGray
        }
        thinkingPanel.add(dotsLabel)

        thinkingPanel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(thinkingPanel)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun renderContent() {
        contentPanel.removeAll()

        // 1. Render text content (use fullText which contains streamed content)
        val textToRender = fullText.toString()
        if (textToRender.isNotBlank()) {
            val renderWidth = getRenderWidth()
            val components = renderer.renderToComponents(textToRender, project, renderWidth)
            for (component in components) {
                component.alignmentX = Component.LEFT_ALIGNMENT
                contentPanel.add(component)
            }
        }

        // 2. Render image attachments
        for (img in messageContent.images) {
            contentPanel.add(createImagePreview(img))
        }

        // 3. Render file attachments (clickable chips)
        for (file in messageContent.files) {
            contentPanel.add(createFileChip(file))
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    /**
     * Create a thumbnail preview for an image.
     * Clicking opens a larger view.
     * If the image is invalid (expired), shows a placeholder with error message.
     */
    private fun createImagePreview(img: ImageAttachment): JComponent {
        // Check if image data is valid (not empty or expired)
        // Use multiple checks: data format, minimum size, and thumbnail validity
        val isValidImage = img.base64Data.isNotBlank() && 
                          img.base64Data.startsWith("data:image") &&
                          img.base64Data.length > 50 &&  // Reduced from 100 to avoid killing small images
                          img.thumbnail.iconWidth > 0 &&
                          img.thumbnail.iconHeight > 0

        if (!isValidImage) {
            // Show placeholder for expired/invalid image
            val placeholderLabel = JBLabel("\uD83D\uDDBC️ Image expired or unavailable").apply {
                font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
                foreground = ChatColors.timestampGray
                border = JBUI.Borders.empty(4, 8)
            }
            return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(placeholderLabel)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
            }
        }

        val thumbnailLabel = JLabel(img.thumbnail).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to view full image"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    showFullImage(img.base64Data)
                }
            })
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(thumbnailLabel)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        }
    }

    /**
     * Show full-size image in a zoomable preview dialog.
     */
    private fun showFullImage(base64Data: String) {
        ImagePreviewDialog.showFromBase64(this, base64Data)
    }

    /**
     * Create a clickable chip for a file attachment.
     * Clicking opens the source file in IntelliJ.
     */
    private fun createFileChip(file: FileAttachment): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        }

        val iconLabel = JLabel(getFileTypeIcon(file.filePath)).apply {
            verticalAlignment = SwingConstants.CENTER
        }
        panel.add(iconLabel)

        val fileText = "${file.fileName}${if (file.lineRange != null) " (${file.lineRange})" else ""}"
        val textLabel = JLabel(fileText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.actionIcon
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = file.filePath

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    openSourceFile(file.filePath, file.lineRange)
                }
                override fun mouseEntered(e: MouseEvent) {
                    text = "<html><u>$fileText</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    text = fileText
                }
            })
        }
        panel.add(textLabel)

        return panel
    }

    /**
     * Open source file in IntelliJ and navigate to the specified line range.
     */
    private fun openSourceFile(filePath: String, lineRange: String?) {
        try {
            // 转换路径格式（处理 Windows 路径）
            val normalizedPath = filePath.replace("\\", "/")

            val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
                ?: LocalFileSystem.getInstance().findFileByIoFile(java.io.File(filePath))

            if (virtualFile == null) {
                JOptionPane.showMessageDialog(
                    this,
                    "File not found: $filePath",
                    "Open File",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }

            val descriptor = if (lineRange != null) {
                // 解析行号（支持单个数字或范围如 "10-20"）
                val startLine = lineRange.split("-").first().trim().toIntOrNull()?.minus(1) ?: 0
                OpenFileDescriptor(project, virtualFile, startLine, 0)
            } else {
                OpenFileDescriptor(project, virtualFile)
            }

            descriptor.navigate(true)

        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to open file: ${e.message}",
                "Open File",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    /**
     * Get file type icon based on extension.
     */
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
            "yaml", "yml" -> IconLoader.getIcon("/fileTypes/yaml.svg", javaClass)
            "md" -> IconLoader.getIcon("/fileTypes/markdown.svg", javaClass)
            "txt", "text" -> IconLoader.getIcon("/fileTypes/text.svg", javaClass)
            "sql" -> IconLoader.getIcon("/fileTypes/dtd.svg", javaClass)
            "sh", "bash", "zsh" -> IconLoader.getIcon("/fileTypes/shell.svg", javaClass)
            "go" -> IconLoader.getIcon("/fileTypes/go.svg", javaClass)
            "rs" -> IconLoader.getIcon("/fileTypes/rust.svg", javaClass)
            "cpp", "cc", "cxx", "h", "hpp" -> IconLoader.getIcon("/fileTypes/cplusplus.svg", javaClass)
            "c" -> IconLoader.getIcon("/fileTypes/c.svg", javaClass)
            "cs" -> IconLoader.getIcon("/fileTypes/csharp.svg", javaClass)
            "php" -> IconLoader.getIcon("/fileTypes/php.svg", javaClass)
            "rb" -> IconLoader.getIcon("/fileTypes/ruby.svg", javaClass)
            "swift" -> IconLoader.getIcon("/fileTypes/swift.svg", javaClass)
            "vue" -> IconLoader.getIcon("/fileTypes/vue.svg", javaClass)
            else -> IconLoader.getIcon("/fileTypes/text.svg", javaClass)
        }
    }

    private fun createActionButton(icon: Icon, toolTip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
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
    }

    /**
     * Custom circular avatar component.
     */
    private inner class AvatarPanel(private val avatarRole: String) : JPanel() {
        private val size = ChatColors.avatarSize

        init {
            preferredSize = Dimension(size, size)
            minimumSize = Dimension(size, size)
            maximumSize = Dimension(size, size)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Draw filled circle
            val bgColor = if (avatarRole == "user") ChatColors.userBlue else ChatColors.hermesPurple
            g2.color = bgColor
            g2.fillOval(0, 0, width, height)

            // Draw letter
            g2.color = Color.WHITE
            val letter = if (avatarRole == "user") "Y" else "H"
            g2.font = JBUI.Fonts.label().deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(letter)) / 2
            val y = (height + fm.ascent - fm.descent) / 2
            g2.drawString(letter, x, y)
        }
    }
}
