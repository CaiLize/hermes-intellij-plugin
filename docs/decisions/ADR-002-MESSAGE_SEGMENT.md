# ADR-002: 消息分段存储方案选择

## 状态

✅ 已采纳（2026-05-12）

## 背景

历史消息中工具调用（Tool Calls）只能显示在文本末尾，无法还原流式响应时的交错顺序（如：文本→工具调用→文本→工具调用）。

**根因**：当前 `ChatMessage` 的持久化结构将文本和工具调用分离存储：

```kotlin
data class ChatMessage(
    val role: String,
    val content: String,                          // 完整文本
    val toolCalls: List<ToolCallRecord>? = null   // 工具调用列表（无顺序信息）
)
```

`toolCalls` 只是一个列表，无法表达 "文本→工具→文本→工具" 的时间顺序关系。

## 方案对比

### 方案 A：完整时间线序列化

将每个 token 和工具调用按时间顺序序列化：

```json
{
  "timeline": [
    {"type": "text", "content": "让我查"},
    {"type": "text", "content": "一下..."},
    {"type": "tool_call", "name": "search", "arguments": "..."},
    {"type": "text", "content": "根据搜索结果..."}
  ]
}
```

**优点**：
- 完整保留时间顺序
- 数据冗余低

**缺点**：
- 改动范围大
- 需要迁移旧数据
- 向后兼容性差

### 方案 B：添加 timeline 字段

在 `ChatMessage` 中添加 `timeline` 字段：

```kotlin
data class ChatMessage(
    val role: String,
    val content: String,
    val timeline: List<TimelineEvent>? = null
)
```

**优点**：
- 向后兼容（旧代码忽略 timeline）
- 扩展性好

**缺点**：
- 数据冗余（content + timeline 重复存储文本）
- 解析逻辑复杂

### 方案 C：文本嵌入标记

在 `content` 中嵌入特殊标记：

```
让我查询一下...
__TOOL_CALL_START__{"name":"search",...}__TOOL_CALL_END__
根据搜索结果...
```

**优点**：
- 改动最小
- 无需修改数据结构

**缺点**：
- 污染纯文本内容
- 解析脆弱（标记可能被修改）
- 扩展性差

### 方案 D：分段存储 Segments（✅ 推荐）

将消息内容表示为有序的 segment 列表：

```kotlin
sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class ToolCall(val name: String, ...) : MessageSegment()
    data class Image(val base64Data: String) : MessageSegment()
    data class File(val filePath: String) : MessageSegment()
}

data class ChatMessage(
    val role: String,
    val content: String,                          // 保留：向后兼容
    val segments: List<MessageSegment>? = null,   // 新增：有序内容段
    val toolCalls: List<ToolCallRecord>? = null   // 保留：向后兼容
)
```

**优点**：
- UI 与数据结构直接对应
- 扩展性极佳（可添加新 segment 类型）
- 向后兼容（双写 + fallback）
- 代码清晰

**缺点**：
- 开发成本中等（约 4-6 小时）
- 数据略有冗余（segments + content/toolCalls 双写）

## 决策

选择 **方案 D：分段存储 Segments**

## 理由

1. **扩展性最佳**：未来可轻松添加新的内容类型（如音频、视频）
2. **UI 对应**：`MessageSegment` 直接对应 `MessageBubble` 的 `ContentSegment`
3. **向后兼容**：通过双写策略，旧代码可继续工作
4. **代码清晰**：数据结构与渲染逻辑一致

## 实施计划

| 阶段 | 任务 | 文件 |
|------|------|------|
| 1 | 定义 `MessageSegment` 密封类 | `models/MessageSegment.kt` |
| 2 | 修改 `ChatMessage` 添加 `segments` 字段 | `models/ChatMessage.kt` |
| 3 | 更新序列化器 | `models/ChatMessage.kt` |
| 4 | `MessageBubble` 添加 `getMessageSegments()` | `toolwindow/MessageBubble.kt` |
| 5 | `ConversationManager` 支持保存 segments | `services/ConversationManager.kt` |
| 6 | `MessageListPanel` 支持从 segments 恢复 | `toolwindow/MessageListPanel.kt` |
| 7 | 可选：数据迁移 | `services/ConversationStore.kt` |

## 向后兼容策略

```kotlin
// 保存时：双写
val message = ChatMessage(
    role = "assistant",
    content = fullText,           // 旧格式
    segments = segments,          // 新格式
    toolCalls = toolCalls         // 旧格式
)

// 加载时：优先级读取
val text = if (msg.segments != null) {
    msg.segments.filterIsInstance<MessageSegment.Text>().joinToString("") { it.content }
} else {
    msg.content  // fallback 到旧格式
}
```

## 影响

### 正面影响

- ✅ 历史消息工具调用顺序完美恢复
- ✅ 支持未来新内容类型
- ✅ UI 与数据层一致性提升

### 负面影响

- ⚠️ 需要修改多个文件
- ⚠️ 数据体积略增（双写）

## 后续

- 考虑添加 `Audio`、`Video` segment 类型
- 优化双写策略，减少冗余

## 相关文档

- [../models/MESSAGE_SEGMENT.md](../models/MESSAGE_SEGMENT.md) - 消息分段存储设计
- [../models/CHAT_MESSAGE.md](../models/CHAT_MESSAGE.md) - ChatMessage 模型
- [../persistence/MIGRATION.md](../persistence/MIGRATION.md) - 数据迁移
