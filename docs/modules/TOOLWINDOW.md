# UI 组件 - 工具窗口实现

## 模块职责

实现 IntelliJ 工具窗口 UI，包括聊天面板、消息气泡、输入面板等组件。

---

## 文件清单

| 文件 | 职责 | 关键组件 |
|------|------|----------|
| `ChatColors.kt` | 颜色常量定义 (支持亮/暗主题) | `ChatColors` |
| `ChatPanel.kt` | 聊天主面板，组合所有子组件 | `ChatPanel` |
| `CodeBlockPanel.kt` | 代码块渲染，带 Copy/Insert/Replace 按钮 | `CodeBlockPanel` |
| `HermesToolWindowFactory.kt` | 工具窗口工厂，注册服务 | `HermesToolWindowFactory` |
| `InputPanel.kt` | 消息输入面板，支持文件/图片粘贴 | `InputPanel`, `SendButton` |
| `MarkdownRenderer.kt` | Markdown 文本渲染 | `renderToComponents()` |
| `MessageBubble.kt` | 单条消息气泡组件 | `MessageBubble`, `State` |
| `MessageListPanel.kt` | 消息列表滚动面板 | `MessageListPanel` |
| `NoStretchVerticalLayout.kt` | 自定义布局管理器 | `NoStretchVerticalLayout` |

---

## 核心组件

### ChatPanel

聊天主面板，组合所有子组件：

```kotlin
class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val titleBar: JPanel
    private val messageListPanel: MessageListPanel
    private val inputPanel: InputPanel
    
    init {
        // 标题栏
        titleBar = createTitleBar()
        
        // 消息列表
        messageListPanel = MessageListPanel()
        val scrollPane = JBScrollPane(messageListPanel).apply {
            border = null
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        
        // 输入面板
        inputPanel = InputPanel(project, this)
        
        // 布局
        add(titleBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }
    
    fun onUserSubmit(prompt: String, contexts: List<CodeContext>, images: List<ImageContext>, files: List<FileAttachment>) {
        // 消费上下文
        val consumedContexts = consumeContexts()
        val consumedImages = consumeImages()
        val consumedFiles = consumeFiles()
        
        // 显示用户消息
        messageListPanel.addUserMessage(prompt, consumedImages, consumedFiles)
        
        // 发送消息
        val chatService = HermesChatService.getInstance(project)
        chatService.sendMessage(prompt, consumedContexts, consumedFiles, consumedImages, this)
    }
    
    fun onTokenReceived(token: String) {
        messageListPanel.streamingBubble?.appendToken(token)
    }
    
    fun onStreamingComplete() {
        messageListPanel.finalizeStreaming()
    }
    
    fun onStreamingError(message: String) {
        messageListPanel.showErrorMessage(message)
    }
}
```

---

### MessageBubble

单条消息气泡组件：

```kotlin
class MessageBubble(
    private val role: String,
    private val messageContent: MessageContent,
    private val isStreaming: Boolean = false
) : JPanel() {
    
    private val avatarPanel = AvatarPanel(role)
    private val contentPanel = JPanel()
    private val actionsPanel = JPanel()
    
    // 内容片段（用于流式消息）
    private val contentSegments = mutableListOf<ContentSegment>()
    
    init {
        layout = NoStretchVerticalLayout()
        
        add(avatarPanel)
        add(contentPanel)
        add(actionsPanel)
        
        // 初始化内容
        if (messageContent.text.isNotBlank()) {
            contentSegments.add(ContentSegment.Text(StringBuilder(messageContent.text)))
        }
        
        // 渲染内容
        renderContent()
    }
    
    /**
     * 流式追加 token
     */
    fun appendToken(token: String) {
        val lastSegment = contentSegments.lastOrNull()
        if (lastSegment is ContentSegment.Text) {
            lastSegment.content.append(token)
        } else {
            contentSegments.add(ContentSegment.Text(StringBuilder(token)))
        }
        renderContent()
    }
    
    /**
     * 显示工具调用
     */
    fun showToolCall(toolCall: StreamDelta.ToolCall) {
        contentSegments.add(ContentSegment.ToolCall(toolCall))
        renderContent()
    }
    
    private fun renderContent() {
        contentPanel.removeAll()
        
        for (segment in contentSegments) {
            when (segment) {
                is ContentSegment.Text -> {
                    val components = MarkdownRenderer.renderToComponents(segment.content.toString())
                    components.forEach { contentPanel.add(it) }
                }
                is ContentSegment.ToolCall -> {
                    contentPanel.add(createToolCallPanel(segment))
                }
                is ContentSegment.Image -> {
                    contentPanel.add(createImagePanel(segment))
                }
                is ContentSegment.File -> {
                    contentPanel.add(createFilePanel(segment))
                }
            }
        }
        
        revalidate()
        repaint()
    }
}
```

**头像渲染**：

```kotlin
private class AvatarPanel(private val role: String) : JPanel() {
    
    private val size = ChatColors.avatarSize
    private val margin = 1
    
    init {
        preferredSize = Dimension(size, size)
        isOpaque = false
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        val color = if (role == "user") ChatColors.userBlue else ChatColors.hermesPurple
        g2d.color = color
        
        // 绘制圆形背景
        g2d.fillOval(margin, margin, size - margin * 2, size - margin * 2)
    }
}
```

---

### InputPanel

消息输入面板：

```kotlin
class InputPanel(
    private val project: Project,
    private val chatPanel: ChatPanel
) : JPanel(BorderLayout()) {
    
    private val textArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
    }
    
    private val chipRow = JPanel(FlowLayout(FlowLayout.LEFT))
    private val sendButton = JButton("发送")
    
    // 待发送的上下文
    private val pendingContexts = mutableListOf<CodeContext>()
    private val pendingImages = mutableListOf<ImageContext>()
    private val pendingFiles = mutableListOf<FileAttachment>()
    
    init {
        // 芯片行（显示待发送的上下文/图片/文件）
        add(chipRow, BorderLayout.NORTH)
        
        // 文本区域
        val scrollPane = JBScrollPane(textArea)
        add(scrollPane, BorderLayout.CENTER)
        
        // 发送按钮
        add(sendButton, BorderLayout.SOUTH)
        
        // 事件监听
        setupEventListeners()
        setupTransferHandler()
    }
    
    private fun setupEventListeners() {
        // Enter 发送，Shift+Enter 换行
        textArea.registerKeyboardAction({
            sendMessage()
        }, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_FOCUSED)
        
        textArea.registerKeyboardAction({
            textArea.append("\n")
        }, KeyStroke.getKeyStroke("shift ENTER"), JComponent.WHEN_FOCUSED)
        
        // Esc 取消
        textArea.registerKeyboardAction({
            clearAll()
        }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_FOCUSED)
        
        // 发送按钮
        sendButton.addActionListener { sendMessage() }
    }
    
    private fun setupTransferHandler() {
        textArea.transferHandler = object : TransferHandler() {
            override fun canImport(info: TransferSupport): Boolean {
                return info.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                       info.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }
            
            override fun importData(info: TransferSupport): Boolean {
                if (info.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    handleImageDrop(info)
                    return true
                }
                if (info.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    handleFileDrop(info)
                    return true
                }
                return false
            }
        }
    }
    
    fun attachContext(context: CodeContext) {
        pendingContexts.add(context)
        addContextChip(context)
    }
    
    fun attachImage(image: ImageContext) {
        pendingImages.add(image)
        addImageChip(image)
    }
    
    fun consumeContexts(): List<CodeContext> {
        val contexts = pendingContexts.toList()
        pendingContexts.clear()
        chipRow.removeAll()
        return contexts
    }
    
    fun consumeImages(): List<ImageContext> {
        val images = pendingImages.toList()
        pendingImages.clear()
        chipRow.removeAll()
        return images
    }
}
```

---

### CodeBlockPanel

代码块渲染，带操作按钮：

```kotlin
class CodeBlockPanel(
    private val language: String,
    private val code: String
) : JPanel(BorderLayout()) {
    
    init {
        background = ChatColors.codeBackground
        
        // 代码显示
        val codeEditor = createCodeEditor(code, language)
        add(codeEditor, BorderLayout.CENTER)
        
        // 操作按钮
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            background = ChatColors.codeBackground
            add(JButton("Copy").apply {
                addActionListener { copyToClipboard(code) }
            })
            add(JButton("Insert").apply {
                addActionListener { insertAtCaret(code) }
            })
            add(JButton("Replace").apply {
                addActionListener { replaceSelection(code) }
            })
        }
        add(actionsPanel, BorderLayout.NORTH)
    }
}
```

---

### MarkdownRenderer

Markdown 文本渲染：

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
                    if (currentParagraph.isNotEmpty()) {
                        components.add(createParagraph(currentParagraph.toString()))
                        currentParagraph.clear()
                    }
                    components.add(createHeader(line))
                }
                else -> {
                    currentParagraph.appendLine(line)
                }
            }
        }
        
        if (currentParagraph.isNotEmpty()) {
            components.add(createParagraph(currentParagraph.toString()))
        }
        
        return components
    }
}
```

---

### ChatColors

颜色系统（支持亮/暗主题）：

```kotlin
object ChatColors {
    // 头像尺寸
    const val avatarSize = 32
    
    // 用户消息气泡
    val userBubble = JBColor(Color(0x4A90D9), Color(0x357ABD))
    val userText = JBColor(Color.WHITE, Color.WHITE)
    val userBlue = JBColor(Color(0x4A90D9), Color(0x357ABD))
    
    // AI 消息气泡
    val assistantBubble = JBColor(Color(0xF0F0F0), Color(0x3C3C3C))
    val assistantText = JBColor(Color(0x333333), Color(0xE0E0E0))
    val hermesPurple = JBColor(Color(0x8B5CF6), Color(0x7C3AED))
    
    // 代码块
    val codeBackground = JBColor(Color(0xF8F8F8), Color(0x2B2B2B))
    val codeText = JBColor(Color(0x333333), Color(0xE0E0E0))
    
    // 文本颜色
    val primaryText = JBColor(Color(0x333333), Color(0xE0E0E0))
    val secondaryText = JBColor(Color(0x666666), Color(0x999999))
    
    // 分隔线
    val separator = JBColor(Color(0xE0E0E0), Color(0x404040))
    
    // 操作图标
    val actionIcon = JBColor(Color(0x666666), Color(0xAAAAAA))
}
```

---

## 相关文档

- [../architecture/LAYERS.md](../architecture/LAYERS.md) - 分层职责
- [../ui/COLORS.md](../ui/COLORS.md) - 颜色系统
- [../ui/MESSAGE_BUBBLE.md](../ui/MESSAGE_BUBBLE.md) - 消息气泡渲染
- [../ui/MARKDOWN.md](../ui/MARKDOWN.md) - Markdown 渲染
