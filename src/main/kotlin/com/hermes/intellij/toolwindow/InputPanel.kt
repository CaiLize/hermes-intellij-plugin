package com.hermes.intellij.toolwindow

import com.hermes.intellij.model.CodeContext
import com.hermes.intellij.model.FileAttachment
import com.hermes.intellij.model.ImageContext
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.KeyEventDispatcher
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Input panel with text area, context chips, image/file paste & drag-drop support, and send button.
 */
class InputPanel(
    private val project: Project,
    private val onSend: (String) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(InputPanel::class.java)
    }

    /**
     * TransferHandler 仅处理文件和图片的拖拽。
     * 文本粘贴完全交给 Swing 默认机制，不做任何干预，避免重复插入。
     * 
     * 重要：Ctrl+V 粘贴图片时，KeyEventDispatcher 已经先处理了，
     * 所以 TransferHandler 不应该再处理图片粘贴，否则会重复。
     * 只处理拖拽（DnD）场景的图片/文件。
     */
    private var isDragging = false
    
    private val unifiedTransferHandler = object : TransferHandler() {
        override fun canImport(comp: JComponent, flavors: Array<DataFlavor>): Boolean {
            return flavors.any {
                it == DataFlavor.javaFileListFlavor ||
                it == DataFlavor.imageFlavor ||
                it.mimeType.startsWith("image/", ignoreCase = true)
            }
        }

        override fun importData(comp: JComponent, t: Transferable): Boolean {
            return try {
                if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                    if (files != null) {
                        for (item in files) {
                            if (item is File && handleFilePaste(item)) return true
                        }
                    }
                    return false
                }

                // 只在拖拽时处理图片粘贴，键盘粘贴已由 KeyEventDispatcher 处理
                if (isDragging && t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    try {
                        val image = t.getTransferData(DataFlavor.imageFlavor) as? Image
                        if (image != null) {
                            handleImageFromClipboardImage(image)
                            return true
                        }
                    } catch (e: Exception) {
                        LOG.warn("[Paste] TransferHandler image failed: ${e.message}")
                    }
                }

                // 文本不处理，返回 false 让 Swing 默认机制（paste()）处理
                false
            } catch (e: Exception) {
                LOG.warn("[Paste] TransferHandler importData error: ${e.message}")
                false
            }
        }

        override fun importData(support: TransferSupport): Boolean {
            val comp = support.component as? JComponent ?: return false
            return importData(comp, support.transferable)
        }
    }

    /**
     * 文本粘贴策略（第十一次修复）：
     * 不再拦截/重定向文本粘贴，完全交给 Swing 默认的 paste() 机制。
     * KeyEventDispatcher 只在剪贴板有文件/图片时才拦截 Ctrl+V，
     * 纯文本时让默认 paste() 正常执行（只插入一次，不会重复）。
     */
    private val textArea = JBTextArea(4, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 6)
        font = JBUI.Fonts.label()
        emptyText.text = "Ask Hermes..."
        isOpaque = false
        transferHandler = unifiedTransferHandler
    }

    private val sendButton = SendButton()

    private val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(4, 4, 0, 4)
        // 允许自动换行和多行显示，最大宽度由父容器决定
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private val attachButton = JBLabel().apply {
        setIcon(IconLoader.getIcon("/icons/attach_16.svg", InputPanel::class.java))
        foreground = ChatColors.actionIcon
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Attach file or context"
        // Use same size as send button for alignment
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        minimumSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        maximumSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        horizontalTextPosition = SwingConstants.CENTER
    }

    private var isStreaming = false
    private val maxCodeContexts = 3          // 代码上下文最大数量
    private val maxMediaAttachments = 3      // 文件 + 图片最大数量

    private fun notifyUser(title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hermes AI Notifications")
            .createNotification(title, message, type)
            .notify(project)
    }

    private fun checkMediaAttachmentLimit(): Boolean {
        val total = attachedFiles.size + attachedImages.size
        if (total >= maxMediaAttachments) {
            notifyUser(
                "附件数量超限",
                "最多只能添加 $maxMediaAttachments 个文件/图片，请先删除一些。",
                NotificationType.WARNING
            )
            return true
        }
        return false
    }

    private val attachedContexts = mutableListOf<CodeContext>()
    private val attachedImages = mutableListOf<ImageContext>()
    private val attachedFiles = mutableListOf<FileAttachment>()

    // ==================== Ctrl+V 粘贴拦截 ====================

    /**
     * KeyEventDispatcher 只拦截需要特殊处理的粘贴（文件、图片）。
     * 
     * 核心思路（第十一次修复）：
     * 默认的 Ctrl+V 本身就能正确插入文本（只插入一次），
     * 我们不需要再重复插入。只有文件和图片需要自定义处理，
     * 所以只在剪贴板有文件/图片时才拦截 Ctrl+V 并 consume 事件，
     * 纯文本时完全放行，让默认机制正常工作。
     */
    private var pasteDispatcher: KeyEventDispatcher? = null

    override fun addNotify() {
        super.addNotify()
        registerPasteDispatcher()
    }

    override fun removeNotify() {
        super.removeNotify()
        unregisterPasteDispatcher()
    }

    /**
     * 检查剪贴板是否有文件或图片。
     */
    private fun clipboardHasSpecialContent(): Boolean {
        return try {
            val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            contents != null && (
                contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                contents.isDataFlavorSupported(DataFlavor.imageFlavor)
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun registerPasteDispatcher() {
        if (pasteDispatcher != null) return
        pasteDispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED
                && e.keyCode == KeyEvent.VK_V
                && e.isControlDown
                && !e.isShiftDown
                && !e.isAltDown
            ) {
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focused != null && SwingUtilities.getAncestorOfClass(InputPanel::class.java, focused) === this@InputPanel) {
                    // 只在剪贴板有文件/图片时才拦截，纯文本放行
                    if (clipboardHasSpecialContent()) {
                        LOG.info("[Paste] KeyEventDispatcher: Ctrl+V intercepted (file/image in clipboard)")
                        e.consume()
                        pasteFromClipboard()
                        return@KeyEventDispatcher true
                    }
                    // 纯文本：不拦截，让默认 paste() 正常执行
                }
            }
            false
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(pasteDispatcher)
        LOG.info("[Paste] KeyEventDispatcher registered")
    }

    private fun unregisterPasteDispatcher() {
        pasteDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            LOG.info("[Paste] KeyEventDispatcher unregistered")
        }
        pasteDispatcher = null
    }

    /**
     * 从剪贴板粘贴文件或图片。仅在 KeyEventDispatcher 检测到文件/图片时调用。
     * 处理顺序：文件 → 图片
     * 文本粘贴不经过此方法，由 Swing 默认 paste() 处理。
     */
    private fun pasteFromClipboard() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null)
            if (contents == null) {
                LOG.warn("[Paste] Clipboard is null")
                return
            }

            // 1. 文件优先（文件管理器 Ctrl+C 后 Ctrl+V）
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                    if (files != null && files.isNotEmpty()) {
                        var handled = false
                        for (item in files) {
                            if (item is File && handleFilePaste(item)) {
                                handled = true
                            }
                        }
                        if (handled) {
                            LOG.info("[Paste] File paste OK")
                            return
                        }
                    }
                } catch (ex: Exception) {
                    LOG.warn("[Paste] File paste failed: ${ex.message}")
                }
            }

            // 2. 图片（截图工具 Ctrl+V）
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                try {
                    val image = contents.getTransferData(DataFlavor.imageFlavor)
                    if (image is Image) {
                        handleImageFromClipboardImage(image)
                        LOG.info("[Paste] Image paste OK")
                        return
                    }
                } catch (ex: Exception) {
                    LOG.warn("[Paste] Image paste failed: ${ex.message}")
                }
            }
        } catch (e: Exception) {
            LOG.error("[Paste] pasteFromClipboard error: ${e.message}", e)
        }
    }

    // ==================== end Ctrl+V ====================

    init {
        // 监听拖拽开始/结束，用于区分键盘粘贴和拖拽粘贴
        textArea.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                isDragging = true
            }
        })
        textArea.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
            }
        })
        
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ChatColors.separator),
            JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(10), JBUI.scale(8), JBUI.scale(10))
        )

        attachButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showAttachPopup(e)
            }
        })

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER -> {
                        if (e.isShiftDown || e.isControlDown || e.isAltDown) {
                            // Shift/Ctrl/Alt + Enter：手动插入换行符
                            e.consume()
                            textArea.replaceSelection("\n")
                        } else {
                            // 纯 Enter：发送消息
                            e.consume()
                            if (!isStreaming) sendMessage()
                        }
                    }
                    e.keyCode == KeyEvent.VK_ESCAPE -> {
                        e.consume()
                        if (isStreaming) onCancel() else textArea.text = ""
                    }
                }
            }
        })

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = adjustHeight()
            override fun removeUpdate(e: DocumentEvent) = adjustHeight()
            override fun changedUpdate(e: DocumentEvent) = adjustHeight()
        })

        sendButton.addActionListener {
            if (isStreaming) onCancel() else sendMessage()
        }

        val textScrollPane = JScrollPane(textArea).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            // 拖拽文件到滚动区域时也使用 unifiedTransferHandler
            transferHandler = unifiedTransferHandler
        }

        val innerPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(chipRow)
            add(Box.createVerticalStrut(0))
            add(textScrollPane)
        }

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 6, 0, 0)
            add(attachButton)
        }

        val sendWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 0, 2)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                isOpaque = false
                add(sendButton)
            }, BorderLayout.SOUTH)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(innerPanel, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        add(RoundedBorderPanel().apply {
            layout = BorderLayout()
            add(contentPanel, BorderLayout.CENTER)
            add(sendWrapper, BorderLayout.EAST)
        }, BorderLayout.CENTER)
    }

    fun attachContext(context: CodeContext): Boolean {
        // 检查代码上下文数量限制（独立于图片/文件）
        if (attachedContexts.size >= maxCodeContexts) {
            notifyUser(
                "上下文数量超限",
                "最多只能添加 $maxCodeContexts 个代码上下文，请先删除一些。",
                NotificationType.WARNING
            )
            return false
        }
        attachedContexts.add(context)
        refreshChips()
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun replaceOrAddContext(context: CodeContext, predicate: (CodeContext) -> Boolean): Boolean {
        // 只移除代码上下文中与新选区重叠的旧选区（同文件且行范围重叠）
        // 图片和文件不会被自动替换，需要用户手动删除
        attachedContexts.removeAll { existing ->
            existing.filePath == context.filePath && context.lineRange != null && rangesOverlap(existing.lineRange, context.lineRange)
        }
        // 添加新选区
        attachedContexts.add(context)
        // 如果代码上下文超出限制，移除最旧的代码上下文（不影响图片和文件）
        while (attachedContexts.size > maxCodeContexts) {
            attachedContexts.removeAt(0)
        }
        refreshChips()
        return true
    }

    fun consumeContexts(): List<CodeContext> {
        val contexts = attachedContexts.toList()
        attachedContexts.clear()
        refreshChips()
        return contexts
    }

    fun consumeFiles(): List<FileAttachment> {
        val files = attachedFiles.toList()
        attachedFiles.clear()
        refreshChips()
        return files
    }

    fun consumeImages(): List<ImageContext> {
        val images = attachedImages.toList()
        attachedImages.clear()
        refreshChips()
        return images
    }

    /**
     * 释放所有已附加图片的资源，防止内存泄漏。
     * 在会话结束或不再需要图片时调用。
     */
    fun disposeAttachedImages() {
        attachedImages.forEach { ctx ->
            // 释放 ImageIcon 中的图片资源
            ctx.thumbnail.image?.flush()
        }
        attachedImages.clear()
        refreshChips()
    }

    /**
     * 释放所有已附加文件的资源。
     */
    fun disposeAttachedFiles() {
        attachedFiles.clear()
        refreshChips()
    }

    fun setStreaming(streaming: Boolean) {
        isStreaming = streaming
        sendButton.isStreaming = streaming
        sendButton.toolTipText = if (streaming) "Stop (Esc)" else "Send (Enter)"
        textArea.isEditable = !streaming
    }

    fun focusInput() {
        textArea.requestFocusInWindow()
    }

    /**
     * 从 InputStream 解码图片，带异常保护。
     */
    private fun decodeImageFromStream(stream: InputStream): BufferedImage? {
        return try {
            val image = ImageIO.read(stream)
            if (image != null) normalizeColorSpaceSafe(image) else null
        } catch (_: Exception) {
            null
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    /**
     * 处理来自剪贴板/拖拽的 Image 对象。
     */
    private fun handleImageFromClipboardImage(image: Image) {
        try {
            val bi = toBufferedImageSafe(image)
            if (bi != null) {
                handleImagePaste(bi)
            } else {
                notifyUser("Image Error", "Failed to convert clipboard image.", NotificationType.ERROR)
            }
        } catch (e: Exception) {
            notifyUser("Image Error", "Failed to process clipboard image: ${e.message}", NotificationType.ERROR)
        }
    }

    // ============================================================
    // 消息发送
    // ============================================================

    private fun sendMessage() {
        val text = textArea.text.trim()
        if (text.isEmpty() && attachedContexts.isEmpty() && attachedImages.isEmpty() && attachedFiles.isEmpty()) return
        textArea.text = ""
        onSend(text)
    }

    private fun adjustHeight() {
        SwingUtilities.invokeLater {
            textArea.rows = textArea.lineCount.coerceIn(4, 8)
            revalidate()
        }
    }

    // ============================================================
    // 上下文菜单
    // ============================================================

    private fun showAttachPopup(e: MouseEvent) {
        if (checkMediaAttachmentLimit()) return

        val uploadFileIcon = IconLoader.getIcon("/icons/upload_16.svg", InputPanel::class.java)
        val selectionIcon = IconLoader.getIcon("/icons/selection_16.svg", InputPanel::class.java)
        val currentFileIcon = IconLoader.getIcon("/icons/currentfile_16.svg", InputPanel::class.java)

        val popup = JPopupMenu()
        popup.add(JMenuItem("Upload File", uploadFileIcon).apply {
            addActionListener {
                val allSupported = supportedFileExtensions + supportedImageExtensions
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    isMultiSelectionEnabled = true
                    dialogTitle = "Select files to attach"
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                        "Supported files (*.${allSupported.joinToString(", *.")})", *allSupported.toTypedArray()
                    )
                }
                // 对话框显示后调整大小和位置（JFileChooser 会忽略 preferredSize）
                // 使用 HierarchyListener + invokeLater 确保对话框完全显示后再调整
                chooser.addHierarchyListener(object : java.awt.event.HierarchyListener {
                    override fun hierarchyChanged(e: java.awt.event.HierarchyEvent) {
                        if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && chooser.isShowing) {
                            SwingUtilities.invokeLater {
                                val dialog = SwingUtilities.getWindowAncestor(chooser) as? JDialog ?: return@invokeLater
                                val gc = dialog.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
                                val screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                                val availWidth = gc.bounds.width - screenInsets.left - screenInsets.right
                                val availHeight = gc.bounds.height - screenInsets.top - screenInsets.bottom
                                val targetWidth = availWidth / 2
                                val targetHeight = availHeight * 3 / 4
                                dialog.setSize(targetWidth, targetHeight)
                                // 居中到可用区域（考虑任务栏偏移）
                                val x = gc.bounds.x + screenInsets.left + (availWidth - targetWidth) / 2
                                val y = gc.bounds.y + screenInsets.top + (availHeight - targetHeight) / 2
                                dialog.setLocation(x, y)
                            }
                            // 只需调整一次
                            chooser.removeHierarchyListener(this)
                        }
                    }
                })
                val result = chooser.showOpenDialog(this@InputPanel)
                if (result == JFileChooser.APPROVE_OPTION) {
                    for (file in chooser.selectedFiles) {
                        handleFilePaste(file)
                    }
                }
            }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Current Selection", selectionIcon).apply {
            addActionListener {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@addActionListener
                val selectedText = editor.selectionModel.selectedText ?: return@addActionListener
                val vf = editor.virtualFile ?: return@addActionListener
                val startLine = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1
                val endLine = editor.document.getLineNumber(editor.selectionModel.selectionEnd) + 1
                attachContext(CodeContext(vf.path, "$startLine-$endLine", selectedText, vf.fileType.name.lowercase()))
            }
        })
        popup.add(JMenuItem("Current File", currentFileIcon).apply {
            addActionListener {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@addActionListener
                val vf = editor.virtualFile ?: return@addActionListener
                val content = editor.document.text
                val truncated = if (content.lines().size > 10000) {
                    content.lines().take(10000).joinToString("\n") + "\n// ... truncated"
                } else content
                attachContext(CodeContext(vf.path, null, truncated, vf.fileType.name.lowercase()))
            }
        })
        popup.show(e.component, e.x, e.y)
    }

    // ============================================================
    // Chip 管理
    // ============================================================

    private fun refreshChips() {
        chipRow.removeAll()
        val hasContent = attachedContexts.isNotEmpty() || attachedImages.isNotEmpty() || attachedFiles.isNotEmpty()
        chipRow.isVisible = hasContent
        
        // 显示代码上下文
        for (ctx in attachedContexts) {
            val fileName = ctx.filePath.substringAfterLast("/").substringAfterLast("\\")
            val label = if (ctx.lineRange != null) "$fileName (${ctx.lineRange})" else fileName
            chipRow.add(createChip(label, getFileTypeIcon(ctx.filePath), ctx.filePath, ctx.lineRange) {
                attachedContexts.remove(ctx); refreshChips()
            })
        }
        
        // 显示文件附件
        for (file in attachedFiles) {
            val label = if (file.lineRange != null) "${file.fileName} (${file.lineRange})" else file.fileName
            chipRow.add(createChip(label, getFileTypeIcon(file.filePath), file.filePath, file.lineRange) {
                attachedFiles.remove(file); refreshChips()
            })
        }
        
        // 显示图片
        for (img in attachedImages) {
            chipRow.add(createImageChip(img) {
                attachedImages.remove(img); refreshChips()
            })
        }
        chipRow.revalidate()
        chipRow.repaint()
    }

    private fun createChip(label: String, fileIcon: Icon?, filePath: String, lineRange: String?, onRemove: () -> Unit): JComponent {
        return object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
                if (fileIcon != null) {
                    add(JLabel(fileIcon).apply {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        toolTipText = filePath
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) { openSourceFile(filePath, lineRange) }
                        })
                    })
                }
                add(JBLabel(label).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = ChatColors.actionIcon
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = filePath
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) { openSourceFile(filePath, lineRange) }
                        override fun mouseEntered(e: MouseEvent) {
                            text = "<html><u>$label</u></html>"
                        }
                        override fun mouseExited(e: MouseEvent) {
                            text = label
                        }
                    })
                })
                add(JBLabel("\u00d7").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = ChatColors.actionIcon
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) { onRemove() }
                    })
                })
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ChatColors.chipBg
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), JBUI.scale(8).toDouble(), JBUI.scale(8).toDouble()))
                super.paintComponent(g)
            }
        }
    }

    /**
     * Open source file in IntelliJ and navigate to the specified line range.
     */
    private fun openSourceFile(filePath: String, lineRange: String?) {
        try {
            val normalizedPath = filePath.replace("\\", "/")
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
                ?: LocalFileSystem.getInstance().findFileByIoFile(File(filePath))

            if (virtualFile == null) {
                com.intellij.openapi.ui.Messages.showMessageDialog(
                    this@InputPanel,
                    "File not found: $filePath",
                    "Open File",
                    com.intellij.openapi.ui.Messages.getErrorIcon()
                )
                return
            }

            val descriptor = if (lineRange != null) {
                val startLine = lineRange.split("-").first().trim().toIntOrNull()?.minus(1) ?: 0
                OpenFileDescriptor(project, virtualFile, startLine, 0)
            } else {
                OpenFileDescriptor(project, virtualFile)
            }

            descriptor.navigate(true)

        } catch (e: Exception) {
            com.intellij.openapi.ui.Messages.showMessageDialog(
                this@InputPanel,
                "Failed to open file: ${e.message}",
                "Open File",
                com.intellij.openapi.ui.Messages.getErrorIcon()
            )
        }
    }

    private fun createImageChip(img: ImageContext, onRemove: () -> Unit): JComponent {
        val chipPanel = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ChatColors.chipBg
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), JBUI.scale(8).toDouble(), JBUI.scale(8).toDouble()))
                super.paintComponent(g)
            }
        }
        chipPanel.apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            val thumbLabel = JLabel(img.thumbnail).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Click to preview"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        ImagePreviewDialog.showFromBase64(chipPanel, img.base64Data)
                    }
                })
            }
            add(thumbLabel)
            add(JBLabel("\u00d7").apply {
                font = JBUI.Fonts.smallFont()
                foreground = ChatColors.actionIcon
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { onRemove() }
                })
            })
        }
        return chipPanel
    }

    private fun getFileTypeIcon(filePath: String): Icon? {
        return try {
            val iconPath = when (filePath.substringAfterLast(".").lowercase()) {
                "java" -> "/fileTypes/java.svg"
                "kt", "kts" -> "/fileTypes/kotlin.svg"
                "xml" -> "/fileTypes/xml.svg"
                "json" -> "/fileTypes/json.svg"
                "html", "htm" -> "/fileTypes/html.svg"
                "css" -> "/fileTypes/css.svg"
                "js" -> "/fileTypes/javaScript.svg"
                "ts" -> "/fileTypes/typeScript.svg"
                "py" -> "/fileTypes/python.svg"
                "sql" -> "/fileTypes/dtd.svg"
                else -> "/fileTypes/text.svg"
            }
            IconLoader.getIcon(iconPath, InputPanel::class.java)
        } catch (_: Exception) { null }
    }

    // ============================================================
    // 文件处理
    // ============================================================

    private val supportedFileExtensions = setOf(
        "java", "kt", "kts", "scala", "groovy",
        "js", "ts", "tsx", "jsx",
        "py", "rb", "php",
        "html", "htm", "xml", "svg",
        "css", "scss", "sass", "less",
        "json", "yaml", "yml", "toml",
        "sql", "sh", "bash", "zsh", "ps1", "bat", "cmd",
        "md", "txt", "rst", "adoc",
        "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift",
        "vue", "svelte"
    )

    private val supportedImageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
    private val maxFileSize = 10 * 1024 * 1024L

    private fun readImageSafely(file: File): BufferedImage? {
        // 方法1: ImageReader 精确控制
        try {
            val inputStream = ImageIO.createImageInputStream(file)
            if (inputStream != null) {
                val readers = ImageIO.getImageReaders(inputStream)
                while (readers.hasNext()) {
                    val reader = readers.next()
                    reader.input = inputStream
                    try {
                        val image = reader.read(0)
                        if (image != null) return normalizeColorSpaceSafe(image)
                    } catch (_: Exception) {
                    } finally {
                        reader.dispose()
                    }
                }
                inputStream.close()
            }
        } catch (_: Exception) {}

        // 方法2: ImageIO 标准方法
        try {
            val image = ImageIO.read(file)
            if (image != null) return normalizeColorSpaceSafe(image)
        } catch (_: Exception) {}

        // 方法3: Toolkit（最宽容）
        try {
            val img = Toolkit.getDefaultToolkit().getImage(file.absolutePath)
            val mediaTracker = MediaTracker(this)
            mediaTracker.addImage(img, 0)
            mediaTracker.waitForAll()
            if (!mediaTracker.isErrorAny()) {
                val bi = toBufferedImageSafe(img)
                if (bi != null) return normalizeColorSpaceSafe(bi)
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * 将任意颜色空间的 BufferedImage 转换为标准 sRGB RGB（无 alpha）。
     * 使用纯像素拷贝，完全绕过 drawImage 和 CMM 颜色管理，避免 "Bogus input colorspace"。
     */
    private fun normalizeColorSpaceSafe(src: BufferedImage): BufferedImage {
        val w = src.width
        val h = src.height
        // 使用 TYPE_INT_RGB（无 alpha），JPEG 编码不会出问题
        val dest = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        // 方法1: 逐像素拷贝（最安全，但可能因 getRGB 触发 CMM）
        try {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    try {
                        val argb = src.getRGB(x, y)
                        // 强制不透明（去掉 alpha 通道）
                        dest.setRGB(x, y, argb or 0xFF000000.toInt())
                    } catch (_: Exception) {
                        dest.setRGB(x, y, 0xFFFFFFFF.toInt()) // 白色占位
                    }
                }
            }
            return dest
        } catch (_: Exception) {}

        // 方法2: drawImage 回退
        try {
            val g2 = dest.createGraphics()
            g2.composite = AlphaComposite.Src
            g2.color = Color.WHITE
            g2.fillRect(0, 0, w, h)
            g2.drawImage(src, 0, 0, null)
            g2.dispose()
            return dest
        } catch (_: Exception) {}

        return dest
    }

    private fun handleImageFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            notifyUser("File Error", "Cannot read file '${file.name}'.", NotificationType.WARNING)
            return false
        }
        if (checkMediaAttachmentLimit()) return false
        if (file.length() > maxFileSize) {
            notifyUser("File Too Large", "'${file.name}' exceeds 10MB limit.", NotificationType.WARNING)
            return false
        }

        return try {
            val image = readImageSafely(file)
            if (image != null) {
                handleImagePaste(image)
                true
            } else {
                notifyUser("Invalid Image", "Cannot read '${file.name}' as an image.", NotificationType.WARNING)
                false
            }
        } catch (e: Exception) {
            notifyUser("Image Read Error", "Failed to read '${file.name}': ${e.message}", NotificationType.ERROR)
            false
        }
    }

    private fun handleFilePaste(file: File): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            notifyUser("File Error", "Cannot read file '${file.name}'.", NotificationType.WARNING)
            return false
        }

        val ext = file.extension.lowercase()

        if (ext in supportedImageExtensions) {
            return handleImageFile(file)
        }

        if (ext !in supportedFileExtensions) {
            notifyUser("Unsupported File Type", "File type '.${ext}' is not supported.", NotificationType.INFORMATION)
            return false
        }

        if (checkMediaAttachmentLimit()) return false
        if (file.length() > maxFileSize) {
            notifyUser("File Too Large", "'${file.name}' exceeds 10MB limit.", NotificationType.WARNING)
            return false
        }

        return try {
            val content = Files.readString(file.toPath())
            if (content.isBlank()) {
                notifyUser("Empty File", "File '${file.name}' is empty.", NotificationType.WARNING)
                return false
            }

            val truncated = if (content.lines().size > 10000) {
                content.lines().take(10000).joinToString("\n") + "\n// ... truncated"
            } else content

            val fileAttachment = FileAttachment(
                filePath = file.absolutePath,
                lineRange = null,
                language = ext,
                content = truncated
            )
            attachedFiles.add(fileAttachment)
            refreshChips()
            true
        } catch (e: Exception) {
            notifyUser("File Read Error", "Failed to read '${file.name}': ${e.message}", NotificationType.ERROR)
            false
        }
    }

    // ============================================================
    // 图片处理
    // ============================================================

    /**
     * 安全版 toBufferedImage。始终创建 TYPE_INT_RGB，避免 alpha 相关问题。
     */
    private fun toBufferedImageSafe(img: Image): BufferedImage? {
        return try {
            if (img.getWidth(null) < 0 || img.getHeight(null) < 0) {
                val mediaTracker = MediaTracker(this)
                mediaTracker.addImage(img, 0)
                mediaTracker.waitForAll(3000)
                if (mediaTracker.isErrorAny()) return null
            }

            val w = img.getWidth(null).coerceAtLeast(1)
            val h = img.getHeight(null).coerceAtLeast(1)
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g2 = bi.createGraphics()
            g2.color = Color.WHITE
            g2.fillRect(0, 0, w, h)
            g2.drawImage(img, 0, 0, null)
            g2.dispose()
            bi
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 处理已标准化的 BufferedImage：自适应压缩 + 编码为 base64 + 生成缩略图。
     * 注意：会清理中间创建的临时 BufferedImage，只保留最终缩略图。
     */
    private fun handleImagePaste(image: BufferedImage) {
        try {
            if (checkMediaAttachmentLimit()) return

            // 确保是 TYPE_INT_RGB（传入的应该已经是，但再保险一次）
            val normalized = if (image.type != BufferedImage.TYPE_INT_RGB) {
                normalizeColorSpaceSafe(image)
            } else {
                image
            }

            // 自适应压缩：目标 base64 < 2MB
            val maxBase64Bytes = 2 * 1024 * 1024
            val result = compressImageAdaptive(normalized, maxBase64Bytes)

            if (result == null) {
                notifyUser("Image Error", "Failed to compress image.", NotificationType.ERROR)
                return
            }

            val (base64, scaledImage) = result

            // 创建缩略图用于 UI 显示
            val thumb = scaledImage.getScaledInstance(JBUI.scale(60), JBUI.scale(45), Image.SCALE_SMOOTH)
            attachedImages.add(ImageContext(base64, ImageIcon(thumb)))
            refreshChips()
        } catch (e: Exception) {
            notifyUser("Image Processing Error", "Failed to process image: ${e.message}", NotificationType.ERROR)
        }
    }

    /**
     * 自适应压缩图片：根据源图片尺寸智能选择目标尺寸和质量，减少临时对象创建。
     * 优化策略：
     * 1. 根据源图片尺寸直接计算目标尺寸，避免多次迭代
     * 2. 优先尝试中等质量 (0.6f)，失败后再尝试更低质量
     * 3. 最大支持 50MB 原始图片，超过则直接缩放到小尺寸
     */
    private fun compressImageAdaptive(image: BufferedImage, maxBase64Bytes: Int): Pair<String, BufferedImage>? {
        val srcW = image.width
        val srcH = image.height
        
        // 根据源图片尺寸智能选择目标最大尺寸
        val targetMaxDim = when {
            // 超大图片 (>50MP) - 直接缩放到 512px
            srcW * srcH > 50_000_000 -> 512
            // 大图片 (>10MP) - 缩放到 768px
            srcW * srcH > 10_000_000 -> 768
            // 中等图片 (>2MP) - 缩放到 1024px
            srcW * srcH > 2_000_000 -> 1024
            // 小图片 - 保持原尺寸
            else -> maxOf(srcW, srcH)
        }
        
        // 计算缩放后的尺寸
        val (scaledW, scaledH) = if (srcW > targetMaxDim || srcH > targetMaxDim) {
            val scale = minOf(targetMaxDim.toDouble() / srcW, targetMaxDim.toDouble() / srcH)
            ((srcW * scale).toInt().coerceAtLeast(1)) to ((srcH * scale).toInt().coerceAtLeast(1))
        } else {
            srcW to srcH
        }
        
        // 只创建一次缩放后的图片
        val scaled = if (scaledW < srcW || scaledH < srcH) {
            toBufferedImageSafe(image.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH)) ?: image
        } else {
            image
        }
        
        // 尝试 3 个质量级别：0.6f (平衡), 0.4f (压缩), 0.2f (极限)
        val qualitySteps = listOf(0.6f, 0.4f, 0.2f)
        
        for (quality in qualitySteps) {
            val (base64, bytes) = encodeJpegSafe(scaled, quality)
            if (bytes.size <= maxBase64Bytes) {
                return base64 to scaled
            }
        }
        
        // 兜底：即使质量最低也返回，让调用者处理
        return encodeJpegSafe(scaled, 0.15f).let { (base64, _) -> base64 to scaled }
    }

    /**
     * JPEG 安全编码。失败回退 PNG。
     */
    private fun encodeJpegSafe(image: BufferedImage, quality: Float = 0.7f): Pair<String, ByteArray> {
        // JPEG
        try {
            val baos = ByteArrayOutputStream()
            val jpegWriter = ImageIO.getImageWritersByFormatName("JPEG").next()
            val params = jpegWriter.defaultWriteParam
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = quality
            jpegWriter.output = MemoryCacheImageOutputStream(baos)
            jpegWriter.write(null, IIOImage(image, null, null), params)
            jpegWriter.dispose()
            val bytes = baos.toByteArray()
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes) to bytes
        } catch (_: Exception) {}

        // PNG 回退
        try {
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "PNG", baos)
            val bytes = baos.toByteArray()
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes) to bytes
        } catch (_: Exception) {}

        // 手动 PNG
        try {
            val baos = ByteArrayOutputStream()
            val pngWriter = ImageIO.getImageWritersByFormatName("PNG").next()
            pngWriter.output = MemoryCacheImageOutputStream(baos)
            pngWriter.write(null, IIOImage(image, null, null), null)
            pngWriter.dispose()
            val bytes = baos.toByteArray()
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes) to bytes
        } catch (e: Exception) {
            throw e
        }
    }

    // ============================================================
    // UI 组件
    // ============================================================

    private inner class SendButton : JButton() {
        var isStreaming = false

        private val sendIcon: Icon? by lazy {
            IconLoader.getIcon("/icons/send_16.svg", InputPanel::class.java)
        }

        private val stopIcon: Icon? by lazy {
            IconLoader.getIcon("/icons/stop_16.svg", InputPanel::class.java)
        }

        init {
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Send (Enter)"
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
            margin = JBUI.insets(0)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            if (isStreaming) {
                // 停止图标：使用 SVG
                stopIcon?.let { icon ->
                    val cx = width / 2.0
                    val cy = height / 2.0
                    val iconX = (cx - icon.iconWidth / 2.0).toInt()
                    val iconY = (cy - icon.iconHeight / 2.0).toInt()
                    icon.paintIcon(this, g2, iconX, iconY)
                }
            } else {
                // 发送图标：使用 SVG
                sendIcon?.let { icon ->
                    val cx = width / 2.0
                    val cy = height / 2.0
                    val iconX = (cx - icon.iconWidth / 2.0).toInt()
                    val iconY = (cy - icon.iconHeight / 2.0).toInt()
                    icon.paintIcon(this, g2, iconX, iconY)
                }
            }
        }
    }

    /**
     * 检查两个行范围是否重叠。
     */
    private fun rangesOverlap(range1: String?, range2: String): Boolean {
        if (range1 == null) return false
        val parts1 = range1.split("-")
        val parts2 = range2.split("-")
        if (parts1.size != 2 || parts2.size != 2) return false
        val s1 = parts1[0].toIntOrNull() ?: return false
        val e1 = parts1[1].toIntOrNull() ?: return false
        val s2 = parts2[0].toIntOrNull() ?: return false
        val e2 = parts2[1].toIntOrNull() ?: return false
        return s1 <= e2 && s2 <= e1
    }

    private inner class RoundedBorderPanel : JPanel() {
        init { isOpaque = false }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10).toDouble()
            g2.color = UIManager.getColor("TextField.background") ?: Color.WHITE
            g2.fill(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc))
            g2.color = ChatColors.inputBorder
            g2.draw(RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc))
            super.paintComponent(g)
        }
    }
}
