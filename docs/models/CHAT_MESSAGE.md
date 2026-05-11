# ChatMessage 模型与序列化

## 数据结构

```kotlin
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,              // "system" | "user" | "assistant"
    val content: String,           // 纯文本内容（用于显示）
    val contentParts: List<ContentPart>? = null,  // 多模态内容
    val fileAttachments: List<FileAttachmentData>? = null,  // 文件附件
    val segments: List<MessageSegment>? = null,  // 有序内容段（新格式）
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null
)
```

## 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | String | 消息角色：system/user/assistant |
| `content` | String | 完整文本内容（向后兼容） |
| `contentParts` | List<ContentPart> | 多模态内容（文本 + 图片） |
| `fileAttachments` | List<FileAttachmentData> | 文件附件元数据 |
| `segments` | List<MessageSegment> | 有序内容段（支持工具调用交错） |
| `timestamp` | Long | 消息创建时间戳 |
| `sessionId` | String? | Hermes 会话 ID |

## 工厂方法

```kotlin
companion object {
    fun system(content: String) = ChatMessage("system", content)
    fun user(content: String) = ChatMessage("user", content)
    fun assistant(content: String) = ChatMessage("assistant", content)
    
    fun userWithImages(text: String, imageBase64Urls: List<String>): ChatMessage {
        val parts = mutableListOf<ContentPart>()
        parts.add(ContentPart.Text(text))
        for (url in imageBase64Urls) {
            parts.add(ContentPart.ImageUrl(ImageUrlData(url)))
        }
        return ChatMessage("user", text, parts = parts)
    }
    
    fun fromSegments(segments: List<MessageSegment>, sessionId: String? = null): ChatMessage {
        val fullText = segments.filterIsInstance<MessageSegment.Text>()
            .joinToString("") { it.content }
        
        val toolCalls = segments.filterIsInstance<MessageSegment.ToolCall>()
            .map { ToolCallRecord(it.name, it.status, it.arguments, it.completed) }
        
        return ChatMessage(
            role = "assistant",
            content = fullText,
            segments = segments,
            toolCalls = if (toolCalls.isNotEmpty()) toolCalls else null,
            sessionId = sessionId
        )
    }
}
```

## 序列化策略

```kotlin
object ChatMessageSerializer : KSerializer<ChatMessage> {
    
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatMessage") {
        element<String>("role")
        element<String>("content")
        element<List<MessageSegment>?>("segments")
        element<List<ToolCallRecord>?>("tool_calls")
        element<List<ContentPart>?>("content_parts")
        element<List<FileAttachmentData>?>("file_attachments")
        element<Long>("timestamp")
        element<String?>("session_id")
    }
    
    override fun serialize(encoder: Encoder, value: ChatMessage) {
        val jsonObject = buildJsonObject {
            put("role", JsonPrimitive(value.role))
            put("content", JsonPrimitive(value.content))
            put("timestamp", JsonPrimitive(value.timestamp))
            
            if (value.segments != null) {
                put("segments", Json.encodeToJsonElement(value.segments))
            }
            
            if (value.toolCalls != null) {
                put("tool_calls", Json.encodeToJsonElement(value.toolCalls))
            }
            
            if (value.contentParts != null) {
                put("content_parts", Json.encodeToJsonElement(value.contentParts))
            }
            
            if (value.fileAttachments != null) {
                put("file_attachments", Json.encodeToJsonElement(value.fileAttachments))
            }
            
            if (value.sessionId != null) {
                put("session_id", JsonPrimitive(value.sessionId))
            }
        }
        encoder.encodeJsonElement(jsonObject)
    }
    
    override fun deserialize(decoder: Decoder): ChatMessage {
        val json = decoder.decodeJsonElement().jsonObject
        
        val role = json["role"]?.jsonPrimitive?.content ?: "assistant"
        val content = json["content"]?.jsonPrimitive?.content ?: ""
        val timestamp = json["timestamp"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
        val sessionId = json["session_id"]?.jsonPrimitive?.content
        
        val segments = json["segments"]?.let {
            Json.decodeFromJsonElement<List<MessageSegment>>(it)
        }
        
        val toolCalls = json["tool_calls"]?.let {
            Json.decodeFromJsonElement<List<ToolCallRecord>>(it)
        }
        
        val contentParts = json["content_parts"]?.let {
            Json.decodeFromJsonElement<List<ContentPart>>(it)
        }
        
        val fileAttachments = json["file_attachments"]?.let {
            Json.decodeFromJsonElement<List<FileAttachmentData>>(it)
        }
        
        return ChatMessage(
            role = role,
            content = content,
            segments = segments,
            toolCalls = toolCalls,
            contentParts = contentParts,
            fileAttachments = fileAttachments,
            timestamp = timestamp,
            sessionId = sessionId
        )
    }
}
```

## 向后兼容策略

| 场景 | 处理方式 |
|------|----------|
| **加载旧消息** | `segments` 为 null 时，fallback 到 `content` + `toolCalls` |
| **保存新消息** | 同时写入 `segments` 和 `content`/`toolCalls`（双写） |
| **API 通信** | 使用 `content` 字段（API 层不需要 segments） |

## 相关文档

- [CONTENT_PART.md](CONTENT_PART.md) - 多模态内容
- [MESSAGE_SEGMENT.md](MESSAGE_SEGMENT.md) - 消息分段存储
- [../persistence/MIGRATION.md](../persistence/MIGRATION.md) - 数据迁移
