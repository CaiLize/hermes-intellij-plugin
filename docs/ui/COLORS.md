# 颜色系统

## ChatColors 对象

支持亮/暗主题的 JBColor 颜色定义：

```kotlin
object ChatColors {
    // 头像尺寸
    const val avatarSize = 32
    
    // === 用户消息 ===
    val userBubble = JBColor(Color(0x4A90D9), Color(0x357ABD))
    val userText = JBColor(Color.WHITE, Color.WHITE)
    val userBlue = JBColor(Color(0x4A90D9), Color(0x357ABD))
    
    // === AI 消息 ===
    val assistantBubble = JBColor(Color(0xF0F0F0), Color(0x3C3C3C))
    val assistantText = JBColor(Color(0x333333), Color(0xE0E0E0))
    val hermesPurple = JBColor(Color(0x8B5CF6), Color(0x7C3AED))
    
    // === 代码块 ===
    val codeBackground = JBColor(Color(0xF8F8F8), Color(0x2B2B2B))
    val codeText = JBColor(Color(0x333333), Color(0xE0E0E0))
    
    // === 文本颜色 ===
    val primaryText = JBColor(Color(0x333333), Color(0xE0E0E0))
    val secondaryText = JBColor(Color(0x666666), Color(0x999999))
    
    // === 分隔线 ===
    val separator = JBColor(Color(0xE0E0E0), Color(0x404040))
    
    // === 操作图标 ===
    val actionIcon = JBColor(Color(0x666666), Color(0xAAAAAA))
}
```

## 颜色值参考

### 亮色主题

| 用途 | 颜色值 | 预览 |
|------|--------|------|
| 用户气泡 | `#4A90D9` | 蓝色 |
| AI 气泡 | `#F0F0F0` | 浅灰 |
| Hermes 紫色 | `#8B5CF6` | 紫色 |
| 代码背景 | `#F8F8F8` | 极浅灰 |
| 主文本 | `#333333` | 深灰 |
| 次文本 | `#666666` | 中灰 |
| 分隔线 | `#E0E0E0` | 浅灰 |

### 暗色主题

| 用途 | 颜色值 | 预览 |
|------|--------|------|
| 用户气泡 | `#357ABD` | 深蓝 |
| AI 气泡 | `#3C3C3C` | 深灰 |
| Hermes 紫色 | `#7C3AED` | 深紫 |
| 代码背景 | `#2B2B2B` | 极深灰 |
| 主文本 | `#E0E0E0` | 浅灰 |
| 次文本 | `#999999` | 中灰 |
| 分隔线 | `#404040` | 深灰 |

## 使用示例

### 消息气泡背景

```kotlin
class MessageBubble(private val role: String) : JPanel() {
    
    init {
        background = when (role) {
            "user" -> ChatColors.userBubble
            "assistant" -> ChatColors.assistantBubble
            else -> ChatColors.assistantBubble
        }
    }
}
```

### 头像颜色

```kotlin
private class AvatarPanel(private val role: String) : JPanel() {
    
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
        
        g2d.fillOval(0, 0, size.width, size.height)
    }
}
```

### 代码块样式

```kotlin
class CodeBlockPanel(...) : JPanel() {
    
    init {
        background = ChatColors.codeBackground
        foreground = ChatColors.codeText
    }
}
```

## 相关文档

- [../modules/TOOLWINDOW.md](../modules/TOOLWINDOW.md) - UI 组件实现
- [MESSAGE_BUBBLE.md](MESSAGE_BUBBLE.md) - 消息气泡渲染
- [MARKDOWN.md](MARKDOWN.md) - Markdown 渲染
