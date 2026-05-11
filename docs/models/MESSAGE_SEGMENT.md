
---

## 11. 消息分段存储设计 (MessageSegment)

### 11.1 问题背景

**当前问题**: 历史消息中工具调用 (Tool Calls) 只能显示在文本末尾，无法还原流式响应时的交错顺序（如：文本→工具调用→文本→工具调用）。

**根因分析**: 当前 `ChatMessage` 的持久化结构将文本和工具调用分离存储：

```kotlin
data class ChatMessage(
    val role: String,
    val content: String,                          // 完整文本
    val toolCalls: List<ToolCallRecord>? = null   // 工具调用列表（无顺序信息）
)
```

`toolCalls` 只是一个列表，无法表达 "文本→工具→文本→工具" 的时间顺序关系。

---

### 11.2 设计方案：分段存储 (Segmented Storage)

#### 11.2.1 核心设计思想

将消息内容表示为**有序的 segment 列表**，每个 segment 代表一个内容单元（文本、工具调用、图片等），直接对应 UI 渲染顺序。

```
消息内容 = [Text, ToolCall, Text, ToolCall, Image, Text, ...]
           ↑                                        ↑
         段 1                                      段 N
```

#### 11.2.2 数据结构定义

**MessageSegment 密封类**:

```kotlin
/**
 * 消息内容段 - 统一表示消息中的各种内容单元
 * 用于持久化存储，保留内容的精确顺序
 */
@Serializable
sealed class MessageSegment {
    
    /**
     * 文本段 - 连续的文本内容
     */
    @Serializable
    @SerialName("text")
    data class Text(
        val content: String
    ) : MessageSegment()
    
    /**
     * 工具调用段 - 单次工具调用及其状态
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val name: String,
        val id: String? = null,
        val arguments: String? = null,
        val status: String = "completed",  // "pending" | "running" | "completed" | "failed"
        val result: String? = null,
        val completed: Boolean = true
    ) : MessageSegment()
    
    /**
     * 图片段 - 内联图片引用
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val base64Data: String,
        val mimeType: String = "image/png",
        val thumbnail: Boolean = false
    ) : MessageSegment()
    
    /**
     * 文件段 - 文件附件引用
     */
    @Serializable
    @SerialName("file")
    data class File(
        val filePath: String,
        val fileName: String,
        val lineRange: String? = null,
        val language: String = ""
    ) : MessageSegment()
}
```

**ChatMessage 扩展**:

```kotlin
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,
    
    // === 向后兼容字段 ===
    val content: String = "",                          // 保留：完整文本用于兼容旧代码和 API
    val toolCalls: List<ToolCallRecord>? = null,       // 保留：向后兼容
    val contentParts: List<ContentPart>? = null,       // 保留：多模态内容
    val fileAttachments: List<FileAttachmentData>? = null,
    
    // === 新增字段 ===
    val segments: List<MessageSegment>? = null,        // 新增：有序内容段（优先级最高）
    
    // === 元数据 ===
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null
) {
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
        
        /**
         * 从 segments 构建 assistant 消息
         */
        fun fromSegments(
            segments: List<MessageSegment>,
            sessionId: String? = null
        ): ChatMessage {
            // 提取完整文本用于 content 字段（向后兼容）
            val fullText = segments.filterIsInstance<MessageSegment.Text>()
                .joinToString("") { it.content }
            
            // 提取工具调用用于 toolCalls 字段（向后兼容）
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
}
```

---

### 11.3 序列化策略

#### 11.3.1 自定义序列化器

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
            
            // 优先序列化 segments（新格式）
            if (value.segments != null) {
                put("segments", Json.encodeToJsonElement(value.segments))
            }
            
            // 保留 toolCalls 用于向后兼容
            if (value.toolCalls != null) {
                put("tool_calls", Json.encodeToJsonElement(value.toolCalls))
            }
            
            // 保留 contentParts
            if (value.contentParts != null) {
                put("content_parts", Json.encodeToJsonElement(value.contentParts))
            }
            
            // 保留 fileAttachments
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
        
        // 尝试读取 segments（新格式优先）
        val segments = json["segments"]?.let {
            Json.decodeFromJsonElement<List<MessageSegment>>(it)
        }
        
        // 读取 toolCalls（向后兼容）
        val toolCalls = json["tool_calls"]?.let {
            Json.decodeFromJsonElement<List<ToolCallRecord>>(it)
        }
        
        // 读取 contentParts（向后兼容）
        val contentParts = json["content_parts"]?.let {
            Json.decodeFromJsonElement<List<ContentPart>>(it)
        }
        
        // 读取 fileAttachments（向后兼容）
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

---

### 11.4 流式消息构建

#### 11.4.1 MessageBubble 扩展方法

```kotlin
class MessageBubble(...) : JPanel() {
    
    private val contentSegments = mutableListOf<ContentSegment>()  // UI 内部使用
    
    /**
     * 将 UI 的 contentSegments 转换为持久化的 MessageSegment 列表
     * 在流式响应完成时调用
     */
    fun getMessageSegments(): List<MessageSegment> {
        return contentSegments.map { segment ->
            when (segment) {
                is ContentSegment.Text -> MessageSegment.Text(
                    content = segment.content.toString()
                )
                is ContentSegment.ToolCall -> MessageSegment.ToolCall(
                    name = segment.name,
                    id = segment.id,
                    arguments = segment.arguments?.toString(),
                    status = if (segment.completed) "completed" else segment.status,
                    result = segment.result?.toString(),
                    completed = segment.completed
                )
                is ContentSegment.Image -> MessageSegment.Image(
                    base64Data = segment.base64Data,
                    mimeType = segment.mimeType,
                    thumbnail = segment.thumbnail
                )
                is ContentSegment.File -> MessageSegment.File(
                    filePath = segment.filePath,
                    fileName = segment.fileName,
                    lineRange = segment.lineRange,
                    language = segment.language
                )
            }
        }
    }
}
```

#### 11.4.2 ConversationManager 保存逻辑

```kotlin
class ConversationManager(private val project: Project) {
    
    /**
     * 添加助手消息（支持 segments）
     */
    fun addAssistantMessageWithSegments(
        segments: List<MessageSegment>,
        sessionId: String? = null
    ) {
        val message = ChatMessage.fromSegments(segments, sessionId)
        messages.add(message)
    }
    
    /**
     * 保存当前对话（使用 segments 格式）
     */
    fun saveCurrentConversation() {
        val conversationId = currentConversationId ?: return
        val conversation = Conversation(
            id = conversationId,
            messages = messages,  // 包含 segments 字段
            createdAt = conversationCreatedAt,
            updatedAt = System.currentTimeMillis()
        )
        store.saveConversation(conversation)
    }
}
```

#### 11.4.3 HermesChatService 流式处理

```kotlin
class HermesChatService(private val project: Project) {
    
    fun sendMessage(...) {
        currentJob = scope.launch {
            try {
                val apiClient = HermesApiClient.getInstance()
                val result = apiClient.streamChatWithSession(request, currentSessionId)
                
                // 获取当前流式消息气泡
                val streamingBubble = chatPanel.messageListPanel.streamingBubble
                
                // 收集流式响应
                result.deltas.collect { delta ->
                    for (choice in delta.choices) {
                        // 处理文本 token
                        choice.delta.content?.let { content ->
                            ApplicationManager.getApplication().invokeLater {
                                streamingBubble?.appendToken(content)
                            }
                        }
                        
                        // 处理工具调用
                        choice.delta.toolCalls?.forEach { toolCall ->
                            ApplicationManager.getApplication().invokeLater {
                                streamingBubble?.showToolCall(toolCall)
                            }
                        }
                    }
                }
                
                // 流式完成，获取 segments 并保存
                val segments = streamingBubble?.getMessageSegments() ?: emptyList()
                if (segments.isNotEmpty()) {
                    conversationManager.addAssistantMessageWithSegments(
                        segments = segments,
                        sessionId = result.sessionId
                    )
                    conversationManager.saveCurrentConversation()
                }
                
                ApplicationManager.getApplication().invokeLater {
                    chatPanel.onStreamingComplete()
                }
            } catch (e: Exception) {
                // 错误处理
            }
        }
    }
}
```

---

### 11.5 历史消息加载

#### 11.5.1 MessageListPanel 解析逻辑

```kotlin
class MessageListPanel(...) : JPanel() {
    
    private fun convertToMessageContent(msg: ChatMessage): MessageContent {
        // === 优先级 1: 从 segments 提取（新格式） ===
        if (msg.segments != null) {
            val text = msg.segments.filterIsInstance<MessageSegment.Text>()
                .joinToString("") { it.content }
            
            val images = msg.segments.filterIsInstance<MessageSegment.Image>()
                .map { ImageAttachment(it.base64Data, it.mimeType) }
            
            val files = msg.segments.filterIsInstance<MessageSegment.File>()
                .map { FileAttachment(it.filePath, it.fileName, it.lineRange, it.language) }
            
            // 恢复工具调用到 bubble（按顺序）
            val toolCallsInOrder = msg.segments.filterIsInstance<MessageSegment.ToolCall>()
                .map { /* 转换为 UI ToolCall */ }
            
            return MessageContent(
                text = text,
                images = images,
                files = files,
                toolCalls = toolCallsInOrder  // 保持原始顺序
            )
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
    
    /**
     * 加载历史消息
     */
    fun loadHistoryMessages(messages: List<ChatMessage>) {
        messages.forEach { msg ->
            val content = convertToMessageContent(msg)
            val bubble = MessageBubble(msg.role, content, ...)
            
            // 如果有 segments，按顺序渲染工具调用
            if (msg.segments != null) {
                msg.segments.filterIsInstance<MessageSegment.ToolCall>().forEach { tc ->
                    bubble.addToolCall(tc.toUiToolCall())
                }
            } else if (msg.toolCalls != null) {
                // 兼容旧格式：工具调用显示在末尾
                msg.toolCalls.forEach { tc ->
                    bubble.addToolCall(tc.toUiToolCall())
                }
            }
            
            add(bubble)
        }
    }
}
```

---

### 11.6 数据迁移策略

#### 11.6.1 向后兼容设计

| 场景 | 处理方式 |
|------|----------|
| **加载旧消息** | `segments` 为 null 时，fallback 到 `content` + `toolCalls` |
| **保存新消息** | 同时写入 `segments` 和 `content`/`toolCalls`（双写） |
| **API 通信** | 使用 `content` 字段（API 层不需要 segments） |

#### 11.6.2 可选：历史消息迁移

```kotlin
class ConversationStore(...) {
    
    /**
     * 可选：加载时迁移旧消息格式
     */
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
            
            // 保存迁移后的消息
            saveConversation(conversation.copy(messages = migratedMessages))
        }
        
        return conversation
    }
}
```

---

### 11.7 实施计划

#### 阶段 1: 核心数据结构 (2-3 小时)

| 任务 | 文件 | 说明 |
|------|------|------|
| 定义 `MessageSegment` 密封类 | `api/models/MessageSegment.kt` | 新建文件 |
| 扩展 `ChatMessage` 添加 `segments` 字段 | `api/models/ChatMessage.kt` | 修改现有 |
| 实现 `ChatMessageSerializer` | `api/models/ChatMessage.kt` | 修改现有 |
| 添加 `ToolCallRecord` 与 `MessageSegment.ToolCall` 转换 | `api/models/ToolCallRecord.kt` | 新建/修改 |

#### 阶段 2: 流式消息构建 (2-3 小时)

| 任务 | 文件 | 说明 |
|------|------|------|
| `MessageBubble.getMessageSegments()` | `toolwindow/MessageBubble.kt` | 新增方法 |
| `ConversationManager.addAssistantMessageWithSegments()` | `services/ConversationManager.kt` | 新增方法 |
| `HermesChatService` 流式完成时保存 segments | `services/HermesChatService.kt` | 修改现有 |

#### 阶段 3: 历史消息加载 (1-2 小时)

| 任务 | 文件 | 说明 |
|------|------|------|
| `MessageListPanel.convertToMessageContent()` 支持 segments | `toolwindow/MessageListPanel.kt` | 修改现有 |
| `MessageListPanel.loadHistoryMessages()` 按顺序恢复工具调用 | `toolwindow/MessageListPanel.kt` | 修改现有 |

#### 阶段 4: 测试与验证 (2-3 小时)

| 任务 | 说明 |
|------|------|
| 新消息 segments 保存测试 | 验证流式响应后 segments 正确持久化 |
| 历史消息加载测试 | 验证工具调用顺序正确恢复 |
| 旧消息兼容测试 | 验证无 segments 的旧消息正常加载 |
| 跨版本升级测试 | 验证插件升级后数据不丢失 |

---

### 11.8 方案优势

| 优势 | 说明 |
|------|------|
| **完美顺序还原** | segments 列表直接对应 UI 渲染顺序，100% 还原流式效果 |
| **向后兼容** | 保留 `content` 和 `toolCalls` 字段，旧代码无需修改 |
| **扩展性强** | 可轻松添加新的 segment 类型（如：思考过程、代码执行结果） |
| **UI 与数据一致** | `ContentSegment` (UI) 与 `MessageSegment` (持久化) 直接映射 |
| **API 隔离** | API 层继续使用 `content` 字段，segments 仅用于本地持久化 |

---

### 11.9 注意事项

1. **文件大小**: segments 会增加 XML 文件大小（约 20-30%），但仍在可接受范围
2. **迁移时机**: 建议在用户首次加载旧对话时按需迁移，避免批量迁移导致启动慢
3. **API 兼容**: 发送给 Hermes Agent 的请求仍使用 `content` 字段，segments 不上传
4. **版本标记**: 可在 `Conversation` 中添加 `schemaVersion` 字段，便于未来升级

---

## 附录 D: 修订历史

| 版本 | 日期 | 作者 | 变更说明 |
|------|------|------|----------|
| 1.0.0 | 2026-05-02 | Hermes Team | 初始版本 |
| 1.1.0 | 2026-05-12 | 薇薇安 | 新增第 11 章：消息分段存储设计 (MessageSegment) |

---

*文档结束*
