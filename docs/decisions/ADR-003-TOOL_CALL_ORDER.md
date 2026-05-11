# ADR-003: 工具调用顺序恢复方案 - 完整分段存储

## 状态

✅ 已实施（2026-05-12）

## 背景

参见 [ADR-002](ADR-002-MESSAGE_SEGMENT.md) - 消息分段存储方案选择。

本 ADR 记录工具调用顺序恢复的**完整实施方案**，包括文本、工具调用、图片、文件的完美交错渲染。

---

## 决策内容

### 核心设计：统一分段存储（Unified Segments）

将所有消息内容表示为有序的 `MessageSegment` 列表，直接对应 UI 渲染顺序。

### 支持的 Segment 类型

```kotlin
@Serializable
sealed class MessageSegment {
    @SerialName("text")
    data class Text(val content: String) : MessageSegment()
    
    @SerialName("tool_call")
    data class ToolCall(
        val name: String,
        val status: String = "completed",
        val arguments: String? = null,
        val completed: Boolean = true
    ) : MessageSegment()
    
    @SerialName("image")
    data class Image(
        val base64Data: String,
        val mimeType: String = "image/png"
    ) : MessageSegment()
    
    @SerialName("file")
    data class File(
        val filePath: String,
        val fileName: String,
        val lineRange: String? = null,
        val language: String = ""
    ) : MessageSegment()
}
```

### 内容顺序示例

**用户消息**（文本 → 图片 → 文件 → 代码上下文）：
```
[Text("帮我分析这个图片"), Image(...), File("main.py"), File("test.py", lineRange="10-20")]
```

**助手消息**（文本 → 工具调用 → 文本 → 工具调用）：
```
[Text("让我搜索一下..."), ToolCall("web_search"), Text("根据搜索结果..."), ToolCall("read_file")]
```

---

## 实施方案

### 1. 数据模型层 (`ChatMessage.kt`)

- 添加 `MessageSegment` 密封类（4 种子类型）
- `ChatMessage` 添加 `segments: List<MessageSegment>?` 字段
- 自定义序列化器支持新格式

### 2. UI 渲染层 (`MessageBubble.kt`)

- `ContentSegment` 密封类（与 `MessageSegment` 对应）
- `contentSegments: MutableList<ContentSegment>` 统一存储
- `renderStreamingContent()` 按顺序渲染所有 segment 类型
- `getMessageSegments()` 转换为持久化格式
- `restoreFromSegments()` 从持久化恢复

### 3. 列表管理 (`MessageListPanel.kt`)

- `addHistoricalMessage()` 支持 segments 恢复
- `convertToMessageContent()` 从 segments 提取文本
- `restoreFromSegments()` 委托给 `MessageBubble`

### 4. 业务服务层 (`HermesChatService.kt`)

- 发送时构建 `userMessageSegments`（文本 → 图片 → 文件 → 上下文）
- 流式完成时从 `MessageBubble` 获取 `segments`
- 保存时优先使用 `addUserMessageWithSegments()` / `addAssistantMessageWithSegments()`

### 5. 对话管理 (`ConversationManager.kt`)

- `addUserMessageWithSegments()` 保存用户消息
- `addAssistantMessageWithSegments()` 保存助手消息
- 向后兼容：fallback 到旧格式

---

## 向后兼容策略

### 双写策略

新消息同时保存：
- `segments` 字段（新格式，完美顺序）
- `toolCalls` 字段（旧格式，向后兼容）

### 加载策略

```kotlin
if (msg.segments != null && msg.segments.isNotEmpty()) {
    restoreFromSegments(bubble, msg.segments)  // 新格式：完美顺序
} else if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
    restoreToolCallHistory(toolCalls)  // 旧格式：工具调用在末尾
}
```

### 数据迁移

**不实现自动迁移**。旧对话保持原样，新对话使用新格式。理由：
- 旧对话数据量小，迁移成本高
- 新旧格式共存无冲突
- 用户逐渐产生新数据

---

## 技术细节

### 图片处理

```kotlin
// 保存时：直接使用 base64 数据
MessageSegment.Image(base64Data = img.base64Data, mimeType = "image/png")

// 渲染时：解码并创建缩略图
val imageBytes = Base64.getDecoder().decode(base64Data)
val image = Toolkit.getDefaultToolkit().createImage(imageBytes)
val thumbnail = ImageIcon(image.getScaledInstance(...))
```

### 文件处理

```kotlin
// 保存时：记录路径和范围
MessageSegment.File(
    filePath = "/path/to/file.py",
    fileName = "file.py",
    lineRange = "10-20",
    language = "python"
)

// 渲染时：创建可点击的 chip
JLabel(fileText).addMouseListener { openSourceFile(filePath, lineRange) }
```

### 流式构建

```kotlin
// 文本追加：合并到最后一个 Text segment
if (lastSegment is ContentSegment.Text) {
    lastSegment.content.append(token)
} else {
    contentSegments.add(ContentSegment.Text(StringBuilder(token)))
}

// 工具调用：追加到末尾
contentSegments.add(ContentSegment.ToolCall(toolName, status, ...))
```

---

## 测试结果

### 预期行为

| 场景 | 预期 | 状态 |
|------|------|------|
| 新用户消息（文本 + 图片 + 文件） | 按顺序渲染 | ✅ |
| 新助手消息（文本 → 工具 → 文本） | 交错渲染 | ✅ |
| 历史消息加载（新格式） | 完美还原顺序 | ✅ |
| 历史消息加载（旧格式） | fallback 到末尾 | ✅ |
| 取消响应 | 保存当前 segments | ✅ |
| 错误处理 | 错误追加到末尾 | ✅ |

---

## 后续优化

### 潜在改进

1. **图片存储优化**：当前使用 base64，可改为 `hermes-image:xxx` 引用
2. **文件内容存储**：可选保存文件内容快照
3. **Segment 压缩**：长文本可分段存储
4. **增量保存**：流式过程中定期保存 segments

### 已知限制

1. **图片大小**：base64 编码增加 33% 体积
2. **文件路径**：依赖本地文件系统，跨设备可能失效
3. **工具参数**：`arguments` 字段未完全利用

---

## 相关文件

- `src/main/kotlin/com/hermes/intellij/api/models/ChatMessage.kt` - 数据模型
- `src/main/kotlin/com/hermes/intellij/toolwindow/MessageBubble.kt` - UI 渲染
- `src/main/kotlin/com/hermes/intellij/toolwindow/MessageListPanel.kt` - 列表管理
- `src/main/kotlin/com/hermes/intellij/services/HermesChatService.kt` - 业务逻辑
- `src/main/kotlin/com/hermes/intellij/services/ConversationManager.kt` - 对话管理
- [docs/models/MESSAGE_SEGMENT.md](../models/MESSAGE_SEGMENT.md) - 详细设计文档

---

## 参考

- [ADR-001](ADR-001-SVG_ICON_PATH.md) - SVG 图标使用 path 而非 text
- [ADR-002](ADR-002-MESSAGE_SEGMENT.md) - 消息分段存储方案选择
