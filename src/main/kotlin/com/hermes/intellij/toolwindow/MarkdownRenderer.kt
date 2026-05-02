package com.hermes.intellij.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.regex.Pattern
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.text.View
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

object MarkdownRenderer {

    /**
     * Render markdown text to Swing components.
     * @param text The markdown text to render.
     * @param project The project instance (needed for CodeBlockPanel).
     * @param parentWidth The available width for text wrapping. If <= 0, defaults to 600.
     */
    fun renderToComponents(text: String, project: Project? = null, parentWidth: Int = 0): List<JComponent> {
        val components = mutableListOf<JComponent>()
        val cleanedText = cleanText(text)

        val wrapWidth = if (parentWidth > 0) parentWidth else 600

        // 解析为 Block 列表（文本+表格、代码块）
        val blocks = parseBlocks(cleanedText)

        for (block in blocks) {
            when (block) {
                is Block.Text -> {
                    createTextComponent(block.content, wrapWidth)?.let { components.add(it) }
                }
                is Block.Code -> {
                    if (block.code.isNotBlank()) {
                        createCodeBlockComponent(block.code, block.language, project)?.let { components.add(it) }
                    }
                }
                is Block.Table -> {
                    createTableComponent(block.header, block.rows, wrapWidth)?.let { components.add(it) }
                }
            }
        }

        return components
    }

    /**
     * 块类型：文本、代码块、表格
     */
    private sealed class Block {
        data class Text(val content: String) : Block()
        data class Code(val language: String, val code: String) : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
    }

    /**
     * 逐行解析，将文本拆分为代码块、表格和普通文本块。
     * 表格行会被抽离为独立的 Block.Table，不再混入 HTML 渲染。
     */
    private fun parseBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = text.lines()
        var i = 0
        val textBuffer = StringBuilder()

        fun flushText() {
            if (textBuffer.isNotEmpty()) {
                blocks.add(Block.Text(textBuffer.toString()))
                textBuffer.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. 检测代码块开始
            val codeStartMatch = Regex("^```(\\w*)\\s*$").find(line)
            if (codeStartMatch != null) {
                flushText()
                val language = codeStartMatch.groupValues[1]
                val codeLines = mutableListOf<String>()
                i++ // 跳过开始行
                var foundClose = false
                while (i < lines.size) {
                    if (lines[i].trim() == "```") {
                        foundClose = true
                        break
                    }
                    codeLines.add(lines[i])
                    i++
                }
                if (foundClose) {
                    blocks.add(Block.Code(language, codeLines.joinToString("\n")))
                } else {
                    // 未闭合的代码块（流式输出中），当作文本处理
                    textBuffer.append("```").append(language)
                    for (codeLine in codeLines) {
                        textBuffer.append("\n").append(codeLine)
                    }
                }
                i++
                continue
            }

            // 2. 检测表格块：连续的以 | 开头的行
            if (isTableRow(trimmed)) {
                flushText()
                val tableLines = mutableListOf<String>()
                while (i < lines.size && isTableRow(lines[i].trim())) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                val table = parseTable(tableLines)
                if (table != null) {
                    blocks.add(table)
                }
                continue
            }

            // 3. 普通文本行
            if (textBuffer.isNotEmpty()) {
                textBuffer.append("\n")
            }
            textBuffer.append(line)
            i++
        }

        flushText()
        return blocks
    }

    /**
     * 将连续的表格行解析为 Block.Table。
     */
    private fun parseTable(tableLines: List<String>): Block.Table? {
        if (tableLines.isEmpty()) return null

        val header = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        var headerParsed = false

        for (line in tableLines) {
            if (isTableSeparator(line)) continue

            val cells = parseTableCells(line)
            if (!headerParsed) {
                header.addAll(cells)
                headerParsed = true
            } else {
                rows.add(cells)
            }
        }

        if (header.isEmpty()) return null
        return Block.Table(header, rows)
    }

    private fun createCodeBlockComponent(code: String, language: String, project: Project?): JComponent? {
        if (code.isBlank()) return null

        if (project != null) {
            val codeBlockPanel = CodeBlockPanel(project, code, language).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            return codeBlockPanel
        }

        return createFallbackCodeBlock(code)
    }

    private fun createFallbackCodeBlock(code: String): JComponent {
        val label = JLabel("<html><pre style='margin:0;font-family:monospace;font-size:12px;'>${escapeHtml(code)}</pre></html>").apply {
            foreground = UIUtil.getLabelForeground()
            background = com.intellij.ui.JBColor(Color(248, 248, 248), Color(45, 45, 45))
            isOpaque = true
            border = JBUI.Borders.customLine(Color(230, 230, 230), 1)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            alignmentY = Component.TOP_ALIGNMENT
            add(label, BorderLayout.CENTER)
            // 限制 maximumSize 防止 BoxLayout 分配多余空间
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
        }
    }

    /**
     * 用 JTable 创建表格组件，替代 JEditorPane 的 HTML 表格渲染。
     */
    private fun createTableComponent(header: List<String>, rows: List<List<String>>, parentWidth: Int = 0): JComponent? {
        if (header.isEmpty()) return null

        val wrapWidth = if (parentWidth > 0) parentWidth else 600

        val columnCount = header.size
        val columnNames = header.toTypedArray()
        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, col: Int) = false
        }

        for (row in rows) {
            val rowData = Array(columnCount) { col ->
                if (col < row.size) row[col] else ""
            }
            model.addRow(rowData)
        }

        val tableFont = java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, JBUI.scale(13))

        val table = JTable(model).apply {
            tableHeader?.apply {
                font = tableFont.deriveFont(java.awt.Font.BOLD)
                background = com.intellij.ui.JBColor(Color(245, 245, 245), Color(60, 60, 60))
                foreground = UIUtil.getLabelForeground()
            }

            font = tableFont
            foreground = UIUtil.getLabelForeground()
            background = UIUtil.getPanelBackground()
            gridColor = com.intellij.ui.JBColor(Color(221, 221, 221), Color(80, 80, 80))
            setShowGrid(true)
            intercellSpacing = Dimension(1, 1)
            rowHeight = JBUI.scale(28)
            setRowMargin(JBUI.scale(2))

            // 自适应列宽：根据表头和单元格内容计算
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            if (columnModel.columnCount == columnCount) {
                for (col in 0 until columnCount) {
                    val headerWidth = tableHeader?.getFontMetrics(tableHeader.font)
                        ?.stringWidth(header[col]) ?: JBUI.scale(80)
                    var maxContentWidth = headerWidth
                    for (row in 0 until model.rowCount) {
                        val cellValue = model.getValueAt(row, col)?.toString() ?: ""
                        val strippedCell = cellValue
                            .replace(Regex("`([^`]+)`")) { "'${it.groupValues[1]}'" }
                            .replace(Regex("\\*\\*([^*]+)\\*\\*")) { it.groupValues[1] }
                        val cellWidth = getFontMetrics(font).stringWidth(strippedCell)
                        if (cellWidth > maxContentWidth) maxContentWidth = cellWidth
                    }
                    columnModel.getColumn(col).apply {
                        preferredWidth = (maxContentWidth + JBUI.scale(20)).coerceIn(JBUI.scale(60), JBUI.scale(400))
                        minWidth = JBUI.scale(50)
                    }
                }
            }

            val cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): java.awt.Component {
                    val text = value?.toString() ?: ""
                    // 使用 JLabel 渲染，将行内代码标记去掉 HTML 标签，
                    // 用简单文本显示（比 JEditorPane 更可靠，不会截断）
                    val displayText = text
                        .replace(Regex("`([^`]+)`")) { "'${it.groupValues[1]}'" }
                        .replace(Regex("\\*\\*([^*]+)\\*\\*")) { it.groupValues[1] }
                    val c = super.getTableCellRendererComponent(table, displayText, isSelected, hasFocus, row, column)
                    border = JBUI.Borders.empty(2, 6)
                    toolTipText = text  // 悬停显示原始文本（含格式标记）
                    return c
                }
            }
            setDefaultRenderer(Any::class.java, cellRenderer)

            setCellSelectionEnabled(false)
            rowSelectionAllowed = false
            columnSelectionAllowed = false

            alignmentX = Component.LEFT_ALIGNMENT
        }

        val headerHeight = table.tableHeader?.preferredSize?.height ?: JBUI.scale(24)
        val dataHeight = table.rowHeight * table.rowCount
        val totalHeight = headerHeight + dataHeight

        // 计算表格实际宽度
        val tableWidth = (0 until table.columnCount).sumOf { table.columnModel.getColumn(it).preferredWidth }

        val scrollPane = JScrollPane(table).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(minOf(tableWidth + 2, wrapWidth), totalHeight + 2)
            isOpaque = false
            viewport.isOpaque = false
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            alignmentY = Component.TOP_ALIGNMENT
            border = JBUI.Borders.empty(4, 0)
            add(scrollPane, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, totalHeight + JBUI.scale(10))
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(" ", "&nbsp;")
            .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
    }

    /**
     * Clean text before rendering: trim whitespace, remove think tags.
     */
    private fun cleanText(text: String): String {
        var result = text.trim()

        val completePattern = Pattern.compile("<think" + ">" + "[\\s\\S]*?</think" + ">", Pattern.CASE_INSENSITIVE)
        result = completePattern.matcher(result).replaceAll("")

        val thinkTag = "<think" + ">"
        val thinkCloseTag = "</think" + ">"
        val thinkOpenIdx = result.indexOf(thinkTag)
        if (thinkOpenIdx >= 0) {
            val thinkCloseIdx = result.indexOf(thinkCloseTag)
            if (thinkCloseIdx < 0) {
                result = result.replaceFirst(thinkTag, "")
            }
        }

        return result.trim()
    }

    /**
     * 用单个 JEditorPane 渲染文本块，支持文本选择和复制。
     *
     * 【核心设计】不预先计算高度，让 JEditorPane 自然布局。
     * 在 addNotify() 中设置初始宽度约束，确保 HTML 渲染器有正确的换行宽度。
     * 不覆盖 preferredSize / maximumSize，让 JEditorPane 自己管理。
     * NoStretchVerticalLayout 会尊重子组件的 getPreferredSize()，不会强行压缩。
     */
    private fun createTextComponent(text: String, parentWidth: Int = 0): JComponent? {
        val html = convertTextToHtml(text)
        if (html.isBlank()) return null

        val wrapWidth = if (parentWidth > 0) parentWidth else 600

        val safeHtml = buildString {
            append("<html><body style='")
            append("font-family:Dialog,'Microsoft YaHei','PingFang SC',sans-serif;")
            append("font-size:13px;")
            append("margin:2px 2px;")
            append("padding:0;")
            append("word-wrap:break-word;")
            append("'>")
            append(html)
            append("</body></html>")
        }

        return try {
            val editorPane = object : JEditorPane() {
                private var initialWidthSet = false

                /**
                 * 组件被添加到容器时调用。此时设置宽度约束，让 HTML 渲染器
                 * 在后续布局中使用正确的换行宽度。
                 * 注意：不能在 addNotify 中调用 setText，会触发 Swing 内部 NPE！
                 */
                override fun addNotify() {
                    super.addNotify()
                    if (!initialWidthSet) {
                        initialWidthSet = true
                        setSize(wrapWidth, Int.MAX_VALUE)
                        invalidate()
                        doLayout()
                        parent?.revalidate()
                    }
                }

                /**
                 * 覆盖 getPreferredSize，确保在视图构建后返回正确高度。
                 * 如果高度为0（视图尚未构建），返回一个基于内容行数的估算高度。
                 */
                override fun getPreferredSize(): Dimension {
                    val pref = super.getPreferredSize()
                    if (pref.height <= JBUI.scale(20)) {
                        val lineCount = text.count { it == '\n' } + 1
                        val estimatedHeight = (lineCount * JBUI.scale(20)).coerceAtMost(8000)
                        return Dimension(pref.width, estimatedHeight)
                    }
                    return pref
                }

                init {
                    contentType = "text/html"
                    editorKit = javax.swing.text.html.HTMLEditorKit()
                    isEditable = false
                    setText(safeHtml)
                    foreground = UIUtil.getLabelForeground()
                    font = JBUI.Fonts.label()
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            }

            // 窗口缩放时重新布局
            editorPane.addComponentListener(object : ComponentAdapter() {
                private var lastWidth = -1
                override fun componentResized(e: ComponentEvent?) {
                    val newWidth = editorPane.width
                    if (newWidth <= 0 || newWidth == lastWidth) return
                    lastWidth = newWidth
                    editorPane.setSize(newWidth, Int.MAX_VALUE)
                    editorPane.invalidate()
                    editorPane.doLayout()
                    editorPane.parent?.revalidate()
                }
            })

            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                alignmentY = Component.TOP_ALIGNMENT
                add(editorPane, BorderLayout.CENTER)
            }
        } catch (e: Exception) {
            val fallbackLabel = JLabel(text).apply {
                foreground = UIUtil.getLabelForeground()
                font = JBUI.Fonts.label()
            }
            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                alignmentY = Component.TOP_ALIGNMENT
                add(fallbackLabel, BorderLayout.CENTER)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
            }
        }
    }

    /**
     * 将普通文本转换为 HTML。
     * 注意：表格已在 parseBlocks 阶段被抽离为 Block.Table，不会出现在这里。
     *
     * 使用 <p> 标签 + margin 属性（Swing HTML 渲染器对 <p> + margin 支持最稳定），
     * 不使用 <div> + padding/height（Swing 对这些 CSS 属性支持不稳定）。
     * 不依赖 line-height（Swing 中表现不一致），改用显式 margin 控制间距。
     */
    private fun convertTextToHtml(text: String): String {
        val sb = StringBuilder()
        val lines = text.split("\n")

        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()

            if (trimmed.isEmpty()) {
                // 空行 → 段落间距（用 &nbsp; 防止空 <p> 被折叠）
                sb.append("<p style='margin-top:4px;margin-bottom:4px;'>&nbsp;</p>")
                i++
                continue
            }

            when {
                trimmed.matches(Regex("^[-*_]{3,}$")) -> {
                    sb.append("<hr noshade size=1 color=#cccccc>")
                }
                trimmed.startsWith("### ") -> {
                    sb.append("<p style='margin-top:10px;margin-bottom:4px;'><b style='font-size:14px;'>")
                        .append(processInline(trimmed.removePrefix("### ")))
                        .append("</b></p>")
                }
                trimmed.startsWith("## ") -> {
                    sb.append("<p style='margin-top:12px;margin-bottom:4px;'><b style='font-size:15px;'>")
                        .append(processInline(trimmed.removePrefix("## ")))
                        .append("</b></p>")
                }
                trimmed.startsWith("# ") -> {
                    sb.append("<p style='margin-top:14px;margin-bottom:6px;'><b style='font-size:16px;'>")
                        .append(processInline(trimmed.removePrefix("# ")))
                        .append("</b></p>")
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    sb.append("<p style='margin-left:16px;margin-top:3px;margin-bottom:3px;'>&bull;&nbsp;")
                        .append(processInline(trimmed.substring(2)))
                        .append("</p>")
                }
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val content = trimmed.replaceFirst(Regex("^\\d+\\.\\s"), "")
                    val num = trimmed.substringBefore(".")
                    sb.append("<p style='margin-left:16px;margin-top:3px;margin-bottom:3px;'>")
                        .append(num).append(".&nbsp;")
                        .append(processInline(content))
                        .append("</p>")
                }
                else -> {
                    sb.append("<p style='margin-top:3px;margin-bottom:3px;'>").append(processInline(trimmed)).append("</p>")
                }
            }
            i++
        }

        return sb.toString()
    }

    private fun isTableRow(line: String): Boolean {
        return line.trim().startsWith("|")
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
            .removePrefix("|").removeSuffix("|")
        return trimmed.all { it == '-' || it == ':' || it == '|' || it == ' ' }
            && trimmed.contains('-')
    }

    private fun parseTableCells(line: String): List<String> {
        val trimmed = line.trim()
        var content = trimmed
        if (content.startsWith("|")) content = content.substring(1)
        if (content.endsWith("|")) content = content.substring(0, content.length - 1)
        return content.split("|")
    }

    private fun processInline(text: String): String {
        var result = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        // 简化代码样式，只保留 Swing 支持的 CSS 属性（border-radius 等会导致 NPE）
        result = result.replace(Regex("`(.+?)`"), "<code style='background:#f0f0f0;font-family:monospace;font-size:12px;'>$1</code>")

        return result
    }
}
