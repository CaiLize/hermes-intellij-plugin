package com.hermes.intellij.toolwindow

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Displays a code block with action buttons (Copy, Insert).
 * Insert: replaces selection if text is selected, otherwise inserts at cursor.
 */
class CodeBlockPanel(
    private val project: Project,
    val code: String,
    private val language: String
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(4, 0)
        isOpaque = false
        buildUI()
    }

    private fun buildUI() {
        val contentPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createLineBorder(ChatColors.separator, 1)
            background = ChatColors.codeBlockBg
        }

        // Header bar: language label + action buttons
        val headerPanel = JPanel(BorderLayout()).apply {
            background = ChatColors.codeBlockHeader
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ChatColors.separator),
                JBUI.Borders.empty(4, 8)
            )
        }

        val langLabel = JBLabel(language.ifEmpty { "text" }).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = ChatColors.actionIcon
        }
        headerPanel.add(langLabel, BorderLayout.WEST)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }

        val insertIcon = IconLoader.getIcon("/icons/insert_16.svg", CodeBlockPanel::class.java)
        val insertButton = JButton(insertIcon).apply {
            toolTipText = "Insert"
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(0)
            preferredSize = Dimension(insertIcon.iconWidth, insertIcon.iconHeight)
        }
        insertButton.addActionListener {
            insertOrReplace()
        }
        buttonsPanel.add(insertButton)

        val copyIcon = IconLoader.getIcon("/icons/codecopy_16.svg", CodeBlockPanel::class.java)
        val copyButton = JButton(copyIcon).apply {
            toolTipText = "Copy"
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(0)
            preferredSize = Dimension(copyIcon.iconWidth, copyIcon.iconHeight)
        }
        copyButton.addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(code), null)
            copyButton.toolTipText = "Copied!"
            Timer(2000) { copyButton.toolTipText = "Copy" }.apply {
                isRepeats = false
                start()
            }
        }
        buttonsPanel.add(copyButton)

        val newFileIcon = IconLoader.getIcon("/icons/newfile_16.svg", CodeBlockPanel::class.java)
        val newFileButton = JButton(newFileIcon).apply {
            toolTipText = "New File"
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(0)
            preferredSize = Dimension(newFileIcon.iconWidth, newFileIcon.iconHeight)
        }
        newFileButton.addActionListener {
            createNewFile()
        }
        buttonsPanel.add(newFileButton)

        headerPanel.add(buttonsPanel, BorderLayout.EAST)
        contentPanel.add(headerPanel, BorderLayout.NORTH)

        // Code area — fully expanded, no vertical scroll
        val codeArea = JTextArea(code).apply {
            isEditable = false
            font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size)
            background = ChatColors.codeBlockBg
            foreground = ChatColors.actionIcon
            border = JBUI.Borders.empty(8)
            lineWrap = false
            tabSize = 4
        }

        val scrollPane = JScrollPane(codeArea).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED

            // Height = actual line count * line height + padding (no cap)
            val lineCount = code.lines().size.coerceAtLeast(1)
            val lineHeight = codeArea.getFontMetrics(codeArea.font).height
            preferredSize = Dimension(0, lineHeight * lineCount + JBUI.scale(20))
        }

        contentPanel.add(scrollPane, BorderLayout.CENTER)
        add(contentPanel, BorderLayout.CENTER)
    }

    private fun createSmallButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = JBUI.Fonts.smallFont()
            isFocusPainted = false
            margin = JBUI.insets(2, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }

    /**
     * 如果有选中文本则替换，否则在光标位置插入。
     * 支持多选区替换：所有选区都会被替换为相同的代码。
     */
    private fun insertOrReplace() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        WriteCommandAction.runWriteCommandAction(project, "Hermes AI: Insert Code", null, {
            // 获取所有选区，支持多选区（Ctrl+多选）
            val allCarets = editor.caretModel.allCarets
            val selections = allCarets.filter { it.hasSelection() }

            println("[DEBUG] 总 Carets: ${allCarets.size}, 有选区的：${selections.size}")
            selections.forEachIndexed { i, caret ->
                println("[DEBUG] 选区 $i: ${caret.selectionStart} - ${caret.selectionEnd}")
            }

            if (selections.isNotEmpty()) {
                // 从后往前替换，避免偏移量变化导致位置错误
                val sortedSelections = selections.sortedByDescending { it.selectionStart }
                for (caret in sortedSelections) {
                    val start = caret.selectionStart
                    val end = caret.selectionEnd
                    editor.document.replaceString(start, end, code)
                }
                // 选中第一个选区的位置（最小的 start）
                val firstCaret = sortedSelections.last()
                editor.selectionModel.setSelection(firstCaret.selectionStart, firstCaret.selectionStart + code.length)
            } else {
                // 无选区，在光标位置插入
                val offset = editor.caretModel.offset
                editor.document.insertString(offset, code)
                editor.caretModel.moveToOffset(offset + code.length)
            }
        })
    }

    /**
     * 在当前文件所在目录创建新文件并写入代码内容。
     * 文件扩展名根据代码块语言自动推断。
     */
    private fun createNewFile() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val currentFile = editor?.virtualFile
        val parentDir = if (currentFile != null && !currentFile.isDirectory) {
            currentFile.parent
        } else {
            currentFile ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        } ?: return

        val ext = languageToExtension(language)
        val baseName = "untitled"
        val fileName = "$baseName.$ext"

        // 在父目录中找一个不重名的文件名
        var finalName = fileName
        var counter = 1
        while (parentDir.findChild(finalName) != null) {
            finalName = "${baseName}${counter}.$ext"
            counter++
        }

        val newFile = parentDir.createChildData(this, finalName)
        WriteCommandAction.runWriteCommandAction(project, "Hermes AI: New File", null, {
            newFile.getOutputStream(this).bufferedWriter().use { writer ->
                writer.write(code)
            }
        })

        // 刷新文件系统并打开新文件
        LocalFileSystem.getInstance().refresh(true)
        val freshFile = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(newFile.path))
        if (freshFile != null) {
            FileEditorManager.getInstance(project).openFile(freshFile, true)
        } else {
            FileEditorManager.getInstance(project).openFile(newFile, true)
        }
    }

    private fun languageToExtension(lang: String): String {
        return when (lang.lowercase()) {
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "python", "py" -> "py"
            "javascript", "js" -> "js"
            "typescript", "ts" -> "ts"
            "groovy" -> "groovy"
            "xml" -> "xml"
            "html" -> "html"
            "css" -> "css"
            "json" -> "json"
            "yaml", "yml" -> "yml"
            "sql" -> "sql"
            "shell", "bash", "sh" -> "sh"
            "go" -> "go"
            "rust", "rs" -> "rs"
            "c" -> "c"
            "cpp", "c++" -> "cpp"
            "ruby", "rb" -> "rb"
            "php" -> "php"
            "swift" -> "swift"
            "dart" -> "dart"
            "markdown", "md" -> "md"
            "properties" -> "properties"
            "toml" -> "toml"
            "dockerfile" -> "Dockerfile"
            else -> "txt"
        }
    }
}
