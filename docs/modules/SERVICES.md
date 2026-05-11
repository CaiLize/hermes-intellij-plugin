# 服务层 - 业务逻辑与状态管理

## 模块职责

实现核心业务逻辑，协调 UI 层与 API 层，管理应用状态与持久化。

---

## 文件清单

| 文件 | 职责 | 关键方法 | 作用域 |
|------|------|----------|--------|
| `HermesChatService.kt` | 聊天核心服务，协调 UI 与 API | `sendMessage()`, `cancelCurrentRequest()` | Project |
| `ConversationManager.kt` | 对话生命周期管理，消息构建与持久化 | `buildRequestMessages()`, `saveCurrentConversation()` | Project |
| `ConversationStore.kt` | 对话持久化存储 (XML) | `saveConversation()`, `loadConversation()` | Project |
| `HermesSettingsState.kt` | 应用设置持久化 | `apiEndpoint`, `modelName`, `apiKey` | Application |
| `ImageStore.kt` | 图片磁盘存储管理 | `saveImage()`, `loadImage()` | Project |
| `SelectionContextService.kt` | 编辑器选区监听服务 | `initialize()`, `registerSelectionListener()` | Project |

---

## 核心服务

### HermesChatService

聊天核心服务，协调 UI 与 API：

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

---

### ConversationManager

对话生命周期管理：

```kotlin
class ConversationManager(private val project: Project) {
    
    private val store = ConversationStore.getInstance(project)
    private val messages = mutableListOf<ChatMessage>()
    private var currentConversationId: String? = null
    private var conversationCreatedAt: Long = System.currentTimeMillis()
    private var hermesSessionId: String? = null
    
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
    
    fun addAssistantMessage(content: String) {
        messages.add(ChatMessage.assistant(content))
    }
    
    fun saveCurrentConversation() {
        val conversationId = currentConversationId ?: return
        val conversation = Conversation(
            id = conversationId,
            messages = messages,
            createdAt = conversationCreatedAt,
            updatedAt = System.currentTimeMillis(),
            hermesSessionId = hermesSessionId
        )
        store.saveConversation(conversation)
    }
    
    fun loadConversation(id: String) {
        val conversation = store.loadConversation(id) ?: return
        messages.clear()
        messages.addAll(conversation.messages)
        currentConversationId = id
        hermesSessionId = conversation.hermesSessionId
    }
}
```

---

### ConversationStore

对话持久化存储（XML）：

```kotlin
class ConversationStore(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): ConversationStore {
            return project.getService(ConversationStore::class.java)
        }
    }
    
    private val storagePath: Path
        get() = project.basePath?.let { Paths.get(it, ".idea/hermes-conversations.xml") }
            ?: throw IllegalStateException("No project base path")
    
    fun saveConversation(conversation: Conversation) {
        val xml = buildXml(conversation)
        Files.writeString(storagePath, xml, StandardOpenOption.CREATE)
    }
    
    fun loadConversation(id: String): Conversation? {
        if (!Files.exists(storagePath)) return null
        
        val xml = Files.readString(storagePath)
        return parseXml(xml, id)
    }
    
    fun listConversations(): List<ConversationSummary> {
        if (!Files.exists(storagePath)) return emptyList()
        
        val xml = Files.readString(storagePath)
        return parseSummaries(xml)
    }
}
```

**XML 格式**：

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

---

### ImageStore

图片磁盘存储管理：

```kotlin
class ImageStore(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): ImageStore {
            return project.getService(ImageStore::class.java)
        }
    }
    
    fun saveImage(conversationId: String, base64DataUrl: String): String? {
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
    
    fun loadImage(conversationId: String, fileName: String): String? {
        val imageFile = File(project.basePath, ".hermes/$conversationId/$fileName")
        if (!imageFile.exists()) return null
        
        val base64Data = Base64.getEncoder().encodeToString(imageFile.readBytes())
        return "data:image/png;base64,$base64Data"
    }
}
```

---

### SelectionContextService

编辑器选区监听服务（带防抖）：

```kotlin
class SelectionContextService(private val project: Project) {
    
    private val logger = Logger.getInstance(SelectionContextService::class.java)
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
                    updateContext(editor, newSelection)
                }
            }
        )
    }
    
    private fun updateContext(editor: Editor, selection: TextRange?) {
        // 更新上下文
    }
}
```

---

### HermesSettingsState

应用设置持久化：

```kotlin
@State(
    name = "HermesSettings",
    storages = [Storage("hermes-settings.xml")]
)
@Service(Service.Level.APP)
class HermesSettingsState : PersistentStateComponent<HermesSettingsState.State> {
    
    data class State(
        var apiEndpoint: String = "http://127.0.0.1:8642",
        var modelName: String = "hermes-agent",
        var apiKey: String = ""
    )
    
    private var state = State()
    
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
    
    companion object {
        fun getInstance(): HermesSettingsState {
            return ServiceManager.getService(HermesSettingsState::class.java)
        }
    }
}
```

---

## 服务作用域

| 服务 | 作用域 | 生命周期 |
|------|--------|----------|
| `HermesApiClient` | Application | 应用启动到关闭 |
| `HermesSettingsState` | Application | 应用启动到关闭 |
| `HermesChatService` | Project | 项目打开到关闭 |
| `ConversationManager` | Project | 项目打开到关闭 |
| `SelectionContextService` | Project | 项目打开到关闭 |

---

## 相关文档

- [../architecture/LAYERS.md](../architecture/LAYERS.md) - 分层职责
- [../persistence/CONVERSATION_STORE.md](../persistence/CONVERSATION_STORE.md) - 持久化存储
- [../persistence/IMAGE_STORE.md](../persistence/IMAGE_STORE.md) - 图片存储
