# 数据迁移策略

## 向后兼容设计

### 版本兼容性

| 消息格式版本 | 特征 | 处理方式 |
|-------------|------|----------|
| v1 | 仅 `content` 字段 | 直接使用 |
| v2 | 添加 `contentParts` | 支持多模态 |
| v3 | 添加 `segments` | 支持工具调用交错 |

### 加载策略

```kotlin
private fun convertToMessageContent(msg: ChatMessage): MessageContent {
    // === 优先级 1: 从 segments 提取（新格式） ===
    if (msg.segments != null) {
        val text = msg.segments.filterIsInstance<MessageSegment.Text>()
            .joinToString("") { it.content }
        
        val images = msg.segments.filterIsInstance<MessageSegment.Image>()
            .map { ImageAttachment(it.base64Data, it.mimeType) }
        
        val files = msg.segments.filterIsInstance<MessageSegment.File>()
            .map { FileAttachment(it.filePath, it.fileName, it.lineRange, it.language) }
        
        return MessageContent(text = text, images = images, files = files)
    }
    
    // === 优先级 2: 从 contentParts 提取（兼容格式） ===
    if (msg.contentParts != null) {
        val text = msg.contentParts.filterIsInstance<ContentPart.Text>()
            .joinToString("") { it.text }
        
        val images = msg.contentParts.filterIsInstance<ContentPart.ImageUrl>()
            .map { ImageAttachment(it.imageUrl.url) }
        
        return MessageContent(text = text, images = images)
    }
    
    // === 优先级 3: 从 content 提取（最简格式） ===
    return MessageContent(text = msg.content.ifBlank { "" })
}
```

## 可选：历史消息迁移

### 迁移触发条件

```kotlin
fun loadConversation(id: String): Conversation? {
    val conversation = readFromXml(id) ?: return null
    
    // 检查是否需要迁移
    val needsMigration = conversation.messages.any { 
        it.segments == null && it.toolCalls != null 
    }
    
    if (needsMigration) {
        val migratedMessages = conversation.messages.map { msg ->
            if (msg.segments == null && msg.toolCalls != null) {
                // 将旧格式转换为 segments 格式
                val segments = buildList {
                    // 文本段
                    if (msg.content.isNotBlank()) {
                        add(MessageSegment.Text(msg.content))
                    }
                    // 工具调用段（追加到末尾）
                    addAll(msg.toolCalls.map { it.toMessageSegment() })
                }
                msg.copy(segments = segments)
            } else {
                msg
            }
        }
        
        // 保存迁移后的数据
        val migrated = conversation.copy(messages = migratedMessages)
        saveConversation(migrated)
        
        return migrated
    }
    
    return conversation
}
```

### ToolCallRecord 转 MessageSegment

```kotlin
fun ToolCallRecord.toMessageSegment(): MessageSegment.ToolCall {
    return MessageSegment.ToolCall(
        name = this.name,
        id = this.id,
        arguments = this.arguments,
        status = if (this.completed) "completed" else "pending",
        completed = this.completed
    )
}
```

## 双写策略

保存新消息时同时写入新旧格式：

```kotlin
fun addAssistantMessageWithSegments(
    segments: List<MessageSegment>,
    sessionId: String? = null
) {
    // 从 segments 提取 content 和 toolCalls（向后兼容）
    val fullText = segments.filterIsInstance<MessageSegment.Text>()
        .joinToString("") { it.content }
    
    val toolCalls = segments.filterIsInstance<MessageSegment.ToolCall>()
        .map { ToolCallRecord(it.name, it.status, it.arguments, it.completed) }
    
    // 创建同时包含新旧字段的 ChatMessage
    val message = ChatMessage(
        role = "assistant",
        content = fullText,
        segments = segments,
        toolCalls = if (toolCalls.isNotEmpty()) toolCalls else null,
        sessionId = sessionId
    )
    
    messages.add(message)
}
```

## 迁移检查点

```kotlin
class MigrationManager(private val project: Project) {
    
    companion object {
        private const val CURRENT_SCHEMA_VERSION = 3
    }
    
    fun checkAndMigrate() {
        val store = ConversationStore.getInstance(project)
        val storedVersion = getStoredSchemaVersion()
        
        if (storedVersion < CURRENT_SCHEMA_VERSION) {
            migrateFrom(storedVersion)
            saveSchemaVersion(CURRENT_SCHEMA_VERSION)
        }
    }
    
    private fun migrateFrom(version: Int) {
        when (version) {
            1 -> migrateV1toV2()
            2 -> migrateV2toV3()
        }
    }
    
    private fun migrateV2toV3() {
        // 添加 segments 字段支持
        // 不需要实际数据迁移，因为新代码支持 fallback
    }
}
```

## 回滚策略

如果需要回滚到旧版本：

```kotlin
fun rollbackToV2(message: ChatMessage): ChatMessage {
    return message.copy(
        segments = null,  // 移除新字段
        content = message.content,  // 保留兼容字段
        toolCalls = message.toolCalls  // 保留兼容字段
    )
}
```

## 相关文档

- [CONVERSATION_STORE.md](CONVERSATION_STORE.md) - 对话存储
- [../models/CHAT_MESSAGE.md](../models/CHAT_MESSAGE.md) - ChatMessage 模型
- [../models/MESSAGE_SEGMENT.md](../models/MESSAGE_SEGMENT.md) - 消息分段存储
