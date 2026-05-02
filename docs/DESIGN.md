# Hermes IntelliJ Plugin - 详细设计文档

## 目录

1. [系统架构](#1-系统架构)
2. [模块设计](#2-模块设计)
3. [数据模型](#3-数据模型)
4. [API 设计](#4-api-设计)
5. [UI 设计](#5-ui-设计)
6. [持久化设计](#6-持久化设计)
7. [关键流程](#7-关键流程)
8. [错误处理](#8-错误处理)
9. [性能优化](#9-性能优化)
10. [安全考虑](#10-安全考虑)

---

## 1. 系统架构

### 1.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          IntelliJ IDEA Host                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Presentation Layer (UI)                      │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐    │   │
│  │  │ ChatPanel   │  │ InputPanel   │  │ MessageBubble       │    │   │
│  │  │ - titleBar  │  │ - textArea   │  │ - avatar           │    │   │
│  │  │ - msgList   │  │ - chips      │  │ - content          │    │   │
│  │  │ - input     │  │ - sendBtn    │  │ - actions          │    │   │
│  │  └─────────────┘  └──────────────┘  └─────────────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Business Layer (Services)                    │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐  │   │
│  │  │ HermesChatSvc   │  │ ConversationMgr │  │ SelectionSvc   │  │   │
│  │  │ - sendMessage() │  │ - buildRequest()│  │ - listen()     │  │   │
│  │  │ - cancel()      │  │ - save/load()   │  │ - debounce()   │  │   │
│  │  └─────────────────┘  └─────────────────┘  └────────────────┘  │   │
│  │  ┌─────────────────┐  ┌─────────────────┐                      │   │
│  │  │ ConversationStore│  │ ImageStore      │                      │   │
│  │  │ - XML persist   │  │ - disk storage  │                      │   │
│  │  └─────────────────┘  └─────────────────┘                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      Data Layer (API)                           │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐  │   │
│  │  │ HermesApiClient │  │ SseStreamParser │  │ Data Models    │  │   │
│  │  │ - HTTP client   │  │ - SSE parse     │  │ - Request      │  │   │
│  │  │ - streamChat()  │  │ - Flow<Delta>   │  │ - Response     │  │   │
│  │  └─────────────────┘  └─────────────────┘  └────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
                    ┌───────────────────────────────┐
                    │    Hermes Gateway (Local)     │
                    │    http://127.0.0.1:8642      │
                    └───────────────────────────────┘
```

### 1.2 分层职责

| 层级 | 职责 | 关键组件 |
|------|------|----------|
| **Presentation** | UI 渲染、用户交互、事件处理 | ChatPanel, InputPanel, MessageBubble |
| **Business** | 业务逻辑、状态管理、数据协调 | HermesChatService, ConversationManager |
| **Data** | 网络通信、数据序列化、持久化 | HermesApiClient, ConversationStore |

---

## 2. 模块设计

### 2.1 Actions 模块

**职责**: 处理用户触发的各种操作

#### 2.1.1 HermesPasteAction

```kotlin
class HermesPasteAction(private val originalPaste: AnAction?) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val inputPanel = SwingUtilities.getAncestorOfClass(InputPanel::class.java, focused)
        
        if (inputPanel != null) {
            // 焦点在 InputPanel，由 KeyEventDispatcher 处理
            return
        }
        // 焦点在其他地方，调用原始 paste action
        originalPaste?.actionPerformed(e)
    }
}
```

**设计要点**:
- 不直接处理粘贴逻辑，只做焦点判断
- 避免与 KeyEventDispatcher 冲突

#### 2.1.2 SendSelectionAction

```kotlin
class SendSelectionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        
        val startOffset = selectionModel.selectionStart
        val startLine = editor.document.getLineNumber(startOffset)
        val endLine = editor.document.getLineNumber(selectionModel.selectionEnd)
        
        val context = CodeContext(
            filePath = editor.virtualFile.path,
            content = selectedText,
            lineRange = "${startLine + 1}-${endLine + 1}",
            language = editor.virtualFile.fileType.name
        )
        
        // 附加到 ChatPanel
        HermesToolWindowFactory.getChatPanel(project)?.attachContext(context)
    }
}
```

### 2.2 API 模块

#### 2.2.1 HermesApiClient

```kotlin
@Service(Service.Level.APP)
class HermesApiClient : Disposable {
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val settings = HermesSettingsState.getInstance()
    
    /**
     * 流式聊天，返回 SessionResult 包含 sessionId 和 Flow<StreamDelta>
     */
    suspend fun streamChatWithSession(
        request: ChatRequest,
        currentSessionId: String?
    ): SessionResult {
        val url = settings.apiEndpoint.trimEnd('/') + "/chat"
        val httpRequest = buildHttpRequest(url, request, currentSessionId)
        
        val response = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines()).await()
        
        // 从响应头提取 sessionId
        val sessionId = response.headers().firstValue("X-Session-ID").orElse(null)
        
        val lines = response.body().let { body ->
            body.asStream().collect(Collectors.toList()).asFlow()
                .map { it.toString(Charsets.UTF_8) }
        }
        
        val deltas = SseStreamParser.parse(lines)
        return SessionResult(sessionId, deltas)
    }
    
    private fun buildHttpRequest(
        url: String,
        request: ChatRequest,
        sessionId: String?
    ): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(request)))
        
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        
        if (sessionId != null) {
            builder.header("X-Session-ID", sessionId)
        }
        
        return builder.build()
    }
}
```

#### 2.2.2 SseStreamParser

```kotlin
object SseStreamParser {
    fun parse(lines: Flow<String>): Flow<StreamDelta> = flow {
        var buffer = StringBuilder()
        
        lines.collect { line ->
            if (line.isEmpty()) {
                // 空行表示一个完整的 SSE 事件
                if (buffer.isNotEmpty()) {
                    val event = parseEvent(buffer.toString())
                    if (event != null) {
                        emit(event)
                    }
                    buffer.clear()
                }
            } else {
                buffer.appendLine(line)
            }
        }
    }
    
    private fun parseEvent(eventText: String): StreamDelta? {
        val dataLine = eventText.lines()
            .find { it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?: return null
        
        return try {
            Json.decodeFromString(StreamDelta.serializer(), dataLine)
        } catch (e: Exception) {
            null
        }
    }
}
```

### 2.3 Services 模块

#### 2.3.1 HermesChatService

```kotlin
@Service(Service.Level.PROJECT)
class HermesChatService(private val project: Project) {
    
    private val logger = Logger.getInstance(HermesChatService::class.java)
    private val conversationManager = ConversationManager(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    
    fun sendMessage(
        prompt: String,
        contexts: List<CodeContext>,
        files: List<FileAttachment>,
        images: List<ImageContext>,
        chatPanel: ChatPanel
    ) {
        // 1. 转换上下文格式
        val codeContextInfos = contexts.map { ... }
        val fileInfos = files.map { ... }
        val imageBase64Urls = images.map { it.base64Data }
        
        // 2. 构建请求消息
        val (messages, mergedUserText) = conversationManager.buildRequestMessages(
            prompt, codeContextInfos + fileInfos, imageBase64Urls
        )
        
        // 3. 检查请求大小
        val requestJson = Json.encodeToString(messages)
        if (requestJson.length > 15 * 1024 * 1024) {
            chatPanel.onStreamingError("Request too large (>15MB)")
            return
        }
        
        // 4. 发起流式请求
        currentJob = scope.launch {
            try {
                val apiClient = HermesApiClient.getInstance()
                val currentSessionId = conversationManager.getCurrentSessionId()
                val result = apiClient.streamChatWithSession(request, currentSessionId)
                
                // 5. 保存新的 sessionId
                if (result.sessionId != null) {
                    conversationManager.setHermesSessionId(result.sessionId)
                }
                
                // 6. 收集流式响应
                val responseBuilder = StringBuilder()
                result.deltas.collect { delta ->
                    for (choice in delta.choices) {
                        val content = choice.delta.content
                        if (content != null) {
                            responseBuilder.append(content)
                            ApplicationManager.getApplication().invokeLater {
                                chatPanel.onTokenReceived(content)
                            }
                        }
                    }
                }
                
                // 7. 保存完整响应
                conversationManager.addAssistantMessage(responseBuilder.toString())
                conversationManager.saveCurrentConversation()
                
                ApplicationManager.getApplication().invokeLater {
                    chatPanel.onStreamingComplete()
                }
            } catch (e: CancellationException) {
                // 处理取消
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }
    
    fun cancelCurrentRequest() {
        currentJob?.cancel()
        currentJob = null
    }
}
```

#### 2.3.2 ConversationManager

```kotlin
class ConversationManager(private val project: Project) {
    
    private val store = ConversationStore.getInstance(project)
    private val messages = mutableListOf<ChatMessage>()
    private var currentConversationId: String? = null
    
    /**
     * 构建 API 请求消息
     * 将代码上下文合并到用户消息中，避免连续 user 角色
     */
    fun buildRequestMessages(
        userPrompt: String,
        codeContexts: List<CodeContextInfo> = emptyList(),
        imageBase64Urls: List<String> = emptyList()
    ): Pair<List<ChatMessage>, String> {
        val requestMessages = mutableListOf<ChatMessage>()
        
        // System message
        requestMessages.add(ChatMessage.system(buildSystemPrompt()))
        
        // Conversation history
        requestMessages.addAll(messages)
        
        // Current user message with merged context
        val mergedUserText = buildUserText(userPrompt, codeContexts)
        
        if (imageBase64Urls.isNotEmpty()) {
            requestMessages.add(ChatMessage.userWithImages(mergedUserText, imageBase64Urls))
        } else {
            requestMessages.add(ChatMessage.user(mergedUserText))
        }
        
        return requestMessages to mergedUserText
    }
    
    private fun buildUserText(
        userPrompt: String,
        codeContexts: List<CodeContextInfo>
    ): String {
        if (codeContexts.isEmpty()) return userPrompt
        
        return buildString {
            append("## User Request\n")
            append(userPrompt)
            append("\n\n## Code Context\n")
            
            for ((index, ctx) in codeContexts.withIndex()) {
                val fileInfo = if (ctx.lineRange != null) {
                    "${ctx.filePath} (lines ${ctx.lineRange})"
                } else {
                    ctx.filePath
                }
                
                append("\n### Context ${index + 1}: `$fileInfo`\n")
                append("```language:${ctx.language}\n")
                append(ctx.content)
                append("\n```\n")
            }
        }
    }
}
```

---

## 3. 数据模型

### 3.1 核心数据类

#### ChatMessage

```kotlin
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,              // "system" | "user" | "assistant"
    val content: String,           // 纯文本内容（用于显示）
    val contentParts: List<ContentPart>? = null,  // 多模态内容
    val fileAttachments: List<FileAttachmentData>? = null  // 文件附件元数据
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
            return ChatMessage("user", text, parts)
        }
    }
}
```

#### ContentPart (多模态)

```kotlin
@Serializable
sealed class ContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart()
    
    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        @SerialName("image_url")
        val imageUrl: ImageUrlData
    ) : ContentPart()
}

@Serializable
data class ImageUrlData(
    val url: String,
    val detail: String = "auto"
)
```

#### FileAttachmentData

```kotlin
@Serializable
data class FileAttachmentData(
    val filePath: String,
    val lineRange: String? = null,
    val language: String = ""
)
```

### 3.2 UI 模型

#### CodeContext

```kotlin
data class CodeContext(
    val filePath: String,
    val content: String,
    val lineRange: String?,
    val language: String
)
```

#### ImageContext

```kotlin
data class ImageContext(
    val base64Data: String,
    val thumbnail: BufferedImage
)
```

#### MessageContent

```kotlin
data class MessageContent(
    val text: String,
    val images: List<ImageAttachment> = emptyList(),
    val files: List<FileAttachment> = emptyList()
)
```

---

## 4. API 设计

### 4.1 请求格式

```json
{
  "model": "hermes-agent",
  "messages": [
    {
      "role": "system",
      "content": "You are Hermes, an AI coding assistant..."
    },
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "解释这段代码"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
      ]
    }
  ],
  "stream": true
}
```

### 4.2 响应格式 (流式)

```
data: {"id":"chat-123","choices":[{"delta":{"content":"H"},"index":0}]}

data: {"id":"chat-123","choices":[{"delta":{"content":"ello"},"index":0}]}

data: [DONE]
```

### 4.3 响应头

| Header | 说明 |
|--------|------|
| `X-Session-ID` | 会话 ID，用于后续请求保持上下文 |

---

## 5. UI 设计

### 5.1 组件层次结构

```
ChatPanel (BorderLayout)
├── titleBar (BorderLayout)
│   ├── titleLabel (JBLabel)
│   └── dropdownButton (JBLabel + MouseListener)
├── messageListPanel (JScrollPane)
│   └── MessageBubble[] (BoxLayout.Y_AXIS)
│       ├── avatarPanel
│       ├── contentPanel
│       │   └── JEditorPane (HTML) / CodeBlockPanel[]
│       └── actionsPanel (Copy/Insert/Replace)
└── inputPanel (BorderLayout)
    ├── chipRow (FlowLayout)
    │   └── Chip[] (CodeContext / FileAttachment / ImageContext)
    ├── textScrollPane (JScrollPane)
    │   └── textArea (JBTextArea)
    └── sendButton (JButton)
```

### 5.2 颜色方案

```kotlin
object ChatColors {
    // 用户消息气泡
    val userBubble = JBColor(Color(0x4A90D9), Color(0x357ABD))
    
    // AI 消息气泡
    val assistantBubble = JBColor(Color(0xF0F0F0), Color(0x3C3C3C))
    
    // 代码块背景
    val codeBackground = JBColor(Color(0xF8F8F8), Color(0x2B2B2B))
    
    // 文本颜色
    val primaryText = JBColor(Color(0x333333), Color(0xE0E0E0))
    val secondaryText = JBColor(Color(0x666666), Color(0x999999))
    
    // 分隔线
    val separator = JBColor(Color(0xE0E0E0), Color(0x404040))
    
    // 操作图标
    val actionIcon = JBColor(Color(0x666666), Color(0xAAAAAA))
}
```

### 5.3 Markdown 渲染

```kotlin
object MarkdownRenderer {
    fun renderToComponents(text: String): List<JComponent> {
        val components = mutableListOf<JComponent>()
        val lines = text.split("\n")
        var currentParagraph = StringBuilder()
        
        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    // 代码块
                    if (currentParagraph.isNotEmpty()) {
                        components.add(createParagraph(currentParagraph.toString()))
                        currentParagraph.clear()
                    }
                    components.add(CodeBlockPanel(line, lines))
                }
                line.startsWith("#") -> {
                    // 标题
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

## 6. 持久化设计

### 6.1 对话存储 (XML)

**位置**: `{project}/.idea/hermes-conversations.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<application>
  <component name="ConversationStore">
    <conversations>
      <conversation id="uuid-123" title="代码优化建议" active="true">
        <messages>
          <message role="user">
            <content>帮我优化这段代码</content>
            <file_attachments>
              <attachment filePath="/src/main.kt" lineRange="10-50" language="kotlin"/>
            </file_attachments>
          </message>
          <message role="assistant">
            <content>当然可以！以下是优化建议...</content>
          </message>
        </messages>
        <hermes_session_id>session-456</hermes_session_id>
      </conversation>
    </conversations>
    <last_active_conversation_id>uuid-123</last_active_conversation_id>
  </component>
</application>
```

### 6.2 图片存储 (文件系统)

**位置**: `{project}/.hermes/{conversationId}/{timestamp}_{hash}.png`

```kotlin
object ImageStore {
    fun saveImage(project: Project, conversationId: String, base64DataUrl: String): String? {
        val fileName = "${System.currentTimeMillis()}_${generateHash()}.png"
        val imageDir = File(project.basePath, ".hermes/$conversationId")
        imageDir.mkdirs()
        
        val base64Data = base64DataUrl.removePrefix("data:image/png;base64,")
        val imageBytes = Base64.getDecoder().decode(base64Data)
        
        val imageFile = File(imageDir, fileName)
        imageFile.writeBytes(imageBytes)
        
        // 返回引用，不存储完整 base64
        return "hermes-image:$fileName"
    }
    
    fun loadImage(project: Project, conversationId: String, fileName: String): String? {
        val imageFile = File(project.basePath, ".hermes/$conversationId/$fileName")
        if (!imageFile.exists()) return null
        
        val base64Data = Base64.getEncoder().encodeToString(imageFile.readBytes())
        return "data:image/png;base64,$base64Data"
    }
}
```

---

## 7. 关键流程

### 7.1 消息发送时序图

```
User          InputPanel      ChatPanel      HermesChatService    ConversationMgr    HermesApiClient    Hermes Gateway
 │                │               │                 │                   │                   │                   │
 │  Enter 键     │               │                 │                   │                   │                   │
 ├───────────────>│               │                 │                   │                   │                   │
 │                │  onUserSubmit()                 │                   │                   │                   │
 │                ├──────────────>│                 │                   │                   │                   │
 │                │               │  consumeContexts()                  │                   │                   │
 │                │               ├──────────────>│                   │                   │                   │
 │                │               │                 │  sendMessage()    │                   │                   │
 │                │               │                 ├──────────────────>│                   │                   │
 │                │               │                 │                   │  buildRequestMessages()               │
 │                │               │                 │                   ├──────────────────>│                   │
 │                │               │                 │                   │                   │  streamChat()     │
 │                │               │                 │                   │                   ├──────────────────>│
 │                │               │                 │                   │                   │                   │
 │                │               │                 │                   │                   │  Flow<StreamDelta>│
 │                │               │                 │                   │                   <───────────────────│
 │                │               │                 │  collect deltas   │                   │                   │
 │                │               │                 <───────────────────│                   │                   │
 │                │               │  onTokenReceived(token)            │                   │                   │
 │                │               <─────────────────│                   │                   │                   │
 │                │  appendToken()                  │                   │                   │                   │
 │                <───────────────│                 │                   │                   │                   │
 │  流式显示     │               │                 │                   │                   │                   │
 <───────────────────────────────────────────────────────────────────────────────────────────────────────────────│
 │                │               │                 │                   │                   │                   │
 │                │               │                 │  saveConversation()                   │                   │
 │                │               │                 │                   ├──────────────────>│                   │
 │                │               │                 │                   │  XML persist      │                   │
 │                │               │                 │                   │                   │                   │
```

### 7.2 图片粘贴流程

```
User          InputPanel      ImageStore      ConversationStore
 │                │               │                   │
 │  Ctrl+V      │               │                   │
 ├─────────────>│               │                   │
 │                │  clipboardHasSpecialContent()    │
 │                │  (检测 imageFlavor)              │
 │                │               │                   │
 │                │  handleImageFromClipboardImage() │
 │                │               │                   │
 │                │  toBufferedImageSafe()           │
 │                │  (转换为 TYPE_INT_RGB)           │
 │                │               │                   │
 │                │  compressImageAdaptive()         │
 │                │  (自适应压缩 <2MB)               │
 │                │               │                   │
 │                │  saveImage()                     │
 │                ├──────────────>│                   │
 │                │               │  磁盘存储         │
 │                │               │  hermes-image:xxx │
 │                │<──────────────│                   │
 │                │               │                   │
 │                │  添加 ImageContext Chip           │
 │                │               │                   │
 │  显示缩略图   │               │                   │
 <────────────────────────────────────────────────────│
 │                │               │                   │
 │  发送消息     │               │                   │
 │                │  consumeImages()                  │
 │                │  (获取 base64Data)                │
 │                │               │                   │
 │                │               │  保存引用到 XML   │
 │                │               ├──────────────────>│
```

### 7.3 对话切换流程

```
User          ChatPanel      ConversationMgr    ConversationStore
 │                │                 │                   │
 │  选择对话    │                 │                   │
 ├─────────────>│                 │                   │
 │                │  switchConversation(id)           │
 │                ├────────────────>│                   │
 │                │                 │  saveCurrentConversation()
 │                │                 ├──────────────────>│
 │                │                 │  XML persist      │
 │                │                 │<──────────────────│
 │                │                 │                   │
 │                │                 │  loadConversation(id)
 │                │                 ├──────────────────>│
 │                │                 │  XML read         │
 │                │                 │  resolve images   │
 │                │                 │<──────────────────│
 │                │                 │                   │
 │                │  clear()       │                   │
 │                │  loadMessages()│                   │
 │                <─────────────────│                   │
 │                │                 │                   │
 │  显示历史消息 │                 │                   │
 <──────────────────────────────────────────────────────│
```

---

## 8. 错误处理

### 8.1 错误类型与处理

| 错误类型 | 触发条件 | 处理方式 | 用户提示 |
|----------|----------|----------|----------|
| `ConnectException` | Hermes Gateway 未启动 | 捕获异常，显示连接错误 | "无法连接到 Hermes，请检查 Gateway 是否运行" |
| `HermesApiException(401)` | API Key 无效 | 捕获异常，提示检查配置 | "认证失败，请检查 API Key" |
| `HermesApiException(4xx)` | 其他 API 错误 | 显示错误码和消息 | "API 错误 (4xx): [详情]" |
| `RequestTooLarge` | 请求体 >15MB | 发送前检查，提前拦截 | "请求过大，请减少上下文或压缩图片" |
| `CancellationException` | 用户取消 | 保存部分响应 | "(响应已取消)" |
| `IOException` | 图片加载失败 | 保留引用，记录日志 | "图片加载失败" |

### 8.2 异常处理模式

```kotlin
currentJob = scope.launch {
    try {
        val result = apiClient.streamChatWithSession(request, sessionId)
        
        result.deltas
            .catch { e ->
                val errorMsg = when (e) {
                    is ConnectException -> "无法连接到 Hermes..."
                    is HermesApiException -> "API 错误 (${e.statusCode})..."
                    else -> "错误：${e.message}"
                }
                
                logger.warn("Streaming error", e)
                ApplicationManager.getApplication().invokeLater {
                    chatPanel.onStreamingError(errorMsg)
                }
            }
            .collect { delta ->
                // 处理流式数据
            }
            
    } catch (e: CancellationException) {
        // 保存部分响应
        val partial = responseBuilder.toString()
        if (partial.isNotEmpty()) {
            conversationManager.addAssistantMessage(partial + "\n\n(Response cancelled)")
            conversationManager.saveCurrentConversation()
        }
    } catch (e: Exception) {
        logger.warn("Unexpected error", e)
        ApplicationManager.getApplication().invokeLater {
            chatPanel.onStreamingError("意外错误：${e.message}")
        }
    }
}
```

---

## 9. 性能优化

### 9.1 异步初始化

```kotlin
private fun initializeConversationAsync() {
    Thread {
        try {
            // 后台线程：加载对话数据（I/O + 图片解码）
            val chatService = HermesChatService.getInstance(project)
            chatService.ensureConversation()
            val title = chatService.getCurrentTitle()
            val messages = chatService.getCurrentConversationMessages()
            
            // EDT 线程：更新 UI
            SwingUtilities.invokeLater {
                if (!isDisplayable) return@invokeLater
                updateTitleDisplay(title)
                messageListPanel.loadMessages(messages)
            }
        } catch (e: Exception) {
            logger.warn("Failed to initialize conversation", e)
        }
    }.apply {
        isDaemon = true
        name = "Hermes-Init-Conversation"
        start()
    }
}
```

### 9.2 图片压缩

```kotlin
private fun compressImageAdaptive(image: BufferedImage, targetSizeMB: Double = 2.0): String {
    var quality = 0.95
    var base64Data: String
    
    do {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", ImageOutputStreamImpl(outputStream))
        base64Data = Base64.getEncoder().encodeToString(outputStream.toByteArray())
        
        val sizeMB = base64Data.length.toDouble() / (1024 * 1024)
        if (sizeMB <= targetSizeMB) break
        
        quality -= 0.05
    } while (quality > 0.1)
    
    return "data:image/jpeg;base64,$base64Data"
}
```

### 9.3 选区监听防抖

```kotlin
class SelectionContextService(private val project: Project) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var debounceJob: Job? = null
    private val debounceDelay = 500L
    
    fun initialize() {
        ConnectionManager.getInstance(project).subscribe(
            SelectionListener.TOPIC,
            SelectionListener { editor, oldSelection, newSelection ->
                // 防抖处理
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debounceDelay)
                    // 更新上下文
                    updateContext(editor, newSelection)
                }
            }
        )
    }
}
```

---

## 10. 安全考虑

### 10.1 API 密钥存储

- API Key 存储在 `HermesSettingsState` 中
- 使用 IntelliJ 的 `PasswordSafe`（可选）
- 不记录到日志

### 10.2 文件访问

- 仅访问用户明确选择的文件
- 文件路径存储在本地 XML，不上传
- 图片存储在 `.hermes/` 目录，项目隔离

### 10.3 网络通信

- 默认本地连接 (127.0.0.1)
- 支持 HTTPS（用户配置）
- 请求体大小限制 (15MB)

### 10.4 权限最小化

- 不需要网络权限（本地通信）
- 不需要文件系统权限（使用 IDEA API）
- 不需要管理员权限

---

## 附录 A: 快捷键参考

| 快捷键 | 上下文 | 功能 |
|--------|--------|------|
| `Enter` | InputPanel | 发送消息 |
| `Shift + Enter` | InputPanel | 换行 |
| `Esc` | InputPanel | 取消/清空 |
| `Ctrl + V` | InputPanel | 粘贴（文本/图片/文件） |
| `Alt + Shift + H` | 编辑器 | 发送选中代码 |

## 附录 B: 文件清单

| 包 | 文件 | 行数 | 职责 |
|-----|------|------|------|
| `actions` | 6 文件 | ~800 | 用户操作 |
| `api` | 5 文件 | ~600 | API 通信 |
| `model` | 2 文件 | ~200 | 数据模型 |
| `services` | 6 文件 | ~1500 | 业务逻辑 |
| `settings` | 1 文件 | ~300 | 设置配置 |
| `toolwindow` | 9 文件 | ~2500 | UI 组件 |
| **总计** | **29 文件** | **~5900** | - |

## 附录 C: 修订历史

| 版本 | 日期 | 作者 | 变更说明 |
|------|------|------|----------|
| 1.0.0 | 2026-05-02 | Hermes Team | 初始版本 |

---

*文档结束*
