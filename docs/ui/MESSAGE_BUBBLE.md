# 消息气泡渲染逻辑

## MessageBubble 组件结构

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
        renderContent()
    }
}
```

## 内容片段类型

```kotlin
sealed class ContentSegment {
    data class Text(val content: StringBuilder) : ContentSegment()
    data class ToolCall(val name: String, val arguments: StringBuilder) : ContentSegment()
    data class Image(val base64Data: String) : ContentSegment()
    data class File(val filePath: String) : ContentSegment()
}
```

## 流式消息处理

### 初始化状态

```kotlin
init {
    if (isStreaming) {
        // 流式消息初始为空，通过 appendToken() 和 showToolCall() 填充
        state = State.Streaming
    } else {
        // 历史消息直接渲染内容
        if (messageContent.text.isNotBlank()) {
            contentSegments.add(ContentSegment.Text(StringBuilder(messageContent.text)))
        }
        renderContent()
        state = State.Complete
    }
}
```

### 追加 Token

```kotlin
fun appendToken(token: String) {
    val lastSegment = contentSegments.lastOrNull()
    
    if (lastSegment is ContentSegment.Text) {
        // 追加到现有文本片段
        lastSegment.content.append(token)
    } else {
        // 创建新文本片段
        contentSegments.add(ContentSegment.Text(StringBuilder(token)))
    }
    
    renderContent()
}
```

### 显示工具调用

```kotlin
fun showToolCall(toolCall: StreamDelta.ToolCall) {
    contentSegments.add(ContentSegment.ToolCall(
        name = toolCall.function.name,
        arguments = StringBuilder(toolCall.function.arguments)
    ))
    renderContent()
}
```

## 内容渲染

```kotlin
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
```

## 头像渲染

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
        
        val color = when (role) {
            "user" -> ChatColors.userBlue
            "assistant" -> ChatColors.hermesPurple
            else -> ChatColors.hermesPurple
        }
        g2d.color = color
        
        // 绘制圆形背景（留 1 像素边距防止边缘裁剪）
        g2d.fillOval(margin, margin, size - margin * 2, size - margin * 2)
    }
}
```

## 工具调用面板

```kotlin
private fun createToolCallPanel(toolCall: ContentSegment.ToolCall): JComponent {
    return JPanel(BorderLayout()).apply {
        background = ChatColors.codeBackground
        border = LineBorder(ChatColors.separator, 1, true)
        
        val label = JBLabel("🔧 ${toolCall.name}")
        label.foreground = ChatColors.secondaryText
        add(label, BorderLayout.WEST)
        
        val statusLabel = JBLabel(if (toolCall.completed) "✓ 完成" else "⏳ 运行中")
        statusLabel.foreground = if (toolCall.completed) Color.GREEN else Color.ORANGE
        add(statusLabel, BorderLayout.EAST)
    }
}
```

## 状态管理

```kotlin
enum class State {
    Streaming,  // 流式接收中
    Complete    // 已完成
}

fun finalizeStreaming() {
    state = State.Complete
    // 添加操作按钮（Copy/Insert/Replace）
    addActionsPanel()
}
```

## 相关文档

- [../modules/TOOLWINDOW.md](../modules/TOOLWINDOW.md) - UI 组件实现
- [COLORS.md](COLORS.md) - 颜色系统
- [MARKDOWN.md](MARKDOWN.md) - Markdown 渲染
