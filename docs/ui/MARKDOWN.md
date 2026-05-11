# Markdown 渲染

## MarkdownRenderer 对象

将 Markdown 文本渲染为 Swing 组件列表：

```kotlin
object MarkdownRenderer {
    fun renderToComponents(text: String): List<JComponent> {
        val components = mutableListOf<JComponent>()
        val lines = text.split("\n")
        var currentParagraph = StringBuilder()
        var inCodeBlock = false
        var codeBlockLines = mutableListOf<String>()
        var codeBlockLanguage = ""
        
        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) {
                        // 代码块结束
                        if (currentParagraph.isNotEmpty()) {
                            components.add(createParagraph(currentParagraph.toString()))
                            currentParagraph.clear()
                        }
                        components.add(CodeBlockPanel(codeBlockLanguage, codeBlockLines.joinToString("\n")))
                        codeBlockLines.clear()
                        inCodeBlock = false
                    } else {
                        // 代码块开始
                        if (currentParagraph.isNotEmpty()) {
                            components.add(createParagraph(currentParagraph.toString()))
                            currentParagraph.clear()
                        }
                        codeBlockLanguage = line.removePrefix("```").trim()
                        inCodeBlock = true
                    }
                }
                inCodeBlock -> {
                    codeBlockLines.add(line)
                }
                line.startsWith("#") -> {
                    // 标题
                    if (currentParagraph.isNotEmpty()) {
                        components.add(createParagraph(currentParagraph.toString()))
                        currentParagraph.clear()
                    }
                    components.add(createHeader(line))
                }
                line.startsWith("- ") || line.startsWith("* ") || line.matches(Regex("^\\d+\\. ")) -> {
                    // 列表
                    if (currentParagraph.isNotEmpty()) {
                        components.add(createParagraph(currentParagraph.toString()))
                        currentParagraph.clear()
                    }
                    components.add(createListItem(line))
                }
                line.isBlank() -> {
                    // 空行
                    if (currentParagraph.isNotEmpty()) {
                        components.add(createParagraph(currentParagraph.toString()))
                        currentParagraph.clear()
                    }
                }
                else -> {
                    currentParagraph.appendLine(line)
                }
            }
        }
        
        // 处理剩余内容
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            components.add(CodeBlockPanel(codeBlockLanguage, codeBlockLines.joinToString("\n")))
        }
        
        if (currentParagraph.isNotEmpty()) {
            components.add(createParagraph(currentParagraph.toString()))
        }
        
        return components
    }
}
```

## 组件创建

### 段落

```kotlin
private fun createParagraph(text: String): JComponent {
    return JBTextArea(text).apply {
        isEditable = false
        isLineWrap = true
        isWrapStyleWord = true
        border = null
        background = ChatColors.assistantBubble
        foreground = ChatColors.primaryText
        font = JBFont.label()
    }
}
```

### 标题

```kotlin
private fun createHeader(line: String): JComponent {
    val level = line.takeWhile { it == '#' }.length
    val text = line.removePrefix("#").trim()
    
    return JBLabel(text).apply {
        font = when (level) {
            1 -> JBFont.h1()
            2 -> JBFont.h2()
            3 -> JBFont.h3()
            else -> JBFont.label().asBold()
        }
        foreground = ChatColors.primaryText
    }
}
```

### 列表项

```kotlin
private fun createListItem(line: String): JComponent {
    val text = line.removePrefix("- ").removePrefix("* ").removePrefix(Regex("^\\d+\\. "))
    
    return JBLabel("• $text").apply {
        font = JBFont.label()
        foreground = ChatColors.primaryText
        border = EmptyBorder(2, 10, 2, 0)
    }
}
```

### 代码块

```kotlin
class CodeBlockPanel(
    private val language: String,
    private val code: String
) : JPanel(BorderLayout()) {
    
    init {
        background = ChatColors.codeBackground
        
        // 语言标签
        val languageLabel = JBLabel(language.ifBlank { "code" }).apply {
            font = JBFont.small()
            foreground = ChatColors.secondaryText
            border = EmptyBorder(4, 8, 4, 8)
        }
        add(languageLabel, BorderLayout.NORTH)
        
        // 代码编辑器
        val codeEditor = createCodeEditor(code, language)
        add(codeEditor, BorderLayout.CENTER)
        
        // 操作按钮
        val actionsPanel = createActionsPanel(code)
        add(actionsPanel, BorderLayout.SOUTH)
    }
}
```

## 代码编辑器创建

```kotlin
private fun createCodeEditor(code: String, language: String): JComponent {
    val editor = JBTextArea(code).apply {
        isEditable = false
        font = JBFont.monospaced()
        background = ChatColors.codeBackground
        foreground = ChatColors.codeText
        border = EmptyBorder(8, 8, 8, 8)
    }
    
    // 可选：使用 Lexer 进行语法高亮
    // val lexer = createLexer(language)
    // applySyntaxHighlighting(editor, lexer)
    
    return JBScrollPane(editor).apply {
        border = null
    }
}
```

## 操作按钮

```kotlin
private fun createActionsPanel(code: String): JPanel {
    return JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        background = ChatColors.codeBackground
        border = EmptyBorder(4, 8, 4, 8)
        
        add(JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(code))
            }
        })
        
        add(JButton("Insert").apply {
            addActionListener {
                insertAtCaret(code)
            }
        })
        
        add(JButton("Replace").apply {
            addActionListener {
                replaceSelection(code)
            }
        })
    }
}
```

## 流式渲染优化

对于流式消息，避免频繁重绘：

```kotlin
fun appendToken(token: String) {
    // 累积 token
    currentText.append(token)
    
    // 批量更新（每 100ms 或每 50 个字符）
    if (currentText.length % 50 == 0 || System.currentTimeMillis() - lastRenderTime > 100) {
        renderContent()
        lastRenderTime = System.currentTimeMillis()
    }
}
```

## 相关文档

- [../modules/TOOLWINDOW.md](../modules/TOOLWINDOW.md) - UI 组件实现
- [COLORS.md](COLORS.md) - 颜色系统
- [MESSAGE_BUBBLE.md](MESSAGE_BUBBLE.md) - 消息气泡渲染
