# 分层职责说明

## 1. Presentation Layer (UI 层)

### 职责
- 用户界面渲染
- 用户交互事件处理
- 视觉反馈（加载状态、错误提示）

### 核心组件

#### ChatPanel
聊天主面板，组合所有子组件：
- `titleBar` - 标题栏与对话切换下拉框
- `messageListPanel` - 消息列表滚动面板
- `inputPanel` - 输入面板

#### InputPanel
消息输入面板：
- `textArea` - 多行文本输入
- `chipRow` - 上下文/图片/文件预览芯片
- `sendButton` - 发送按钮
- `TransferHandler` - 拖拽粘贴处理
- `KeyEventDispatcher` - 键盘事件拦截

#### MessageBubble
单条消息气泡：
- `avatarPanel` - 头像显示
- `contentPanel` - 内容渲染（文本/代码块/图片）
- `actionsPanel` - 操作按钮（Copy/Insert/Replace）

#### 其他 UI 组件
- `CodeBlockPanel` - 代码块渲染，带语法高亮
- `MarkdownRenderer` - Markdown 文本解析
- `ChatColors` - 颜色系统（支持亮/暗主题）
- `NoStretchVerticalLayout` - 自定义布局管理器

---

## 2. Business Layer (服务层)

### 职责
- 业务逻辑处理
- 状态管理与协调
- 数据持久化

### 核心服务

#### HermesChatService (Project 级)
聊天核心服务，协调 UI 与 API：
```kotlin
fun sendMessage(
    prompt: String,
    contexts: List<CodeContext>,
    files: List<FileAttachment>,
    images: List<ImageContext>,
    chatPanel: ChatPanel
)
fun cancelCurrentRequest()
```

#### ConversationManager (Project 级)
对话生命周期管理：
```kotlin
fun buildRequestMessages(
    userPrompt: String,
    codeContexts: List<CodeContextInfo>,
    imageBase64Urls: List<String>
): Pair<List<ChatMessage>, String>
fun addAssistantMessage(content: String)
fun saveCurrentConversation()
```

#### ConversationStore (Project 级)
对话持久化存储（XML）：
```kotlin
fun saveConversation(conversation: Conversation)
fun loadConversation(id: String): Conversation?
```

#### ImageStore (Project 级)
图片磁盘存储管理：
```kotlin
fun saveImage(project: Project, conversationId: String, base64DataUrl: String): String?
fun loadImage(project: Project, conversationId: String, fileName: String): String?
```

#### SelectionContextService (Project 级)
编辑器选区监听服务：
```kotlin
fun initialize()
// 使用 Coroutine 防抖（500ms）
```

#### HermesSettingsState (Application 级)
应用设置持久化：
```kotlin
var apiEndpoint: String
var modelName: String
var apiKey: String
```

---

## 3. Data Layer (API 层)

### 职责
- HTTP 通信
- 数据序列化/反序列化
- SSE 流式响应解析

### 核心组件

#### HermesApiClient (Application 级)
HTTP 客户端：
```kotlin
suspend fun streamChatWithSession(
    request: ChatRequest,
    currentSessionId: String?
): SessionResult
suspend fun testConnection(): Boolean
```

#### SseStreamParser
SSE 流解析器：
```kotlin
fun parse(lines: Flow<String>): Flow<StreamDelta>
```

#### 数据模型
- `ChatRequest` - 请求模型
- `ChatResponse` - 非流式响应
- `StreamDelta` - 流式响应增量

---

## 层间交互

```
UI Layer
   ↓ (用户操作)
Business Layer
   ↓ (业务处理)
Data Layer
   ↓ (HTTP 请求)
Hermes Gateway
```

## 相关文档

- [OVERVIEW.md](OVERVIEW.md) - 架构概览
- [DATA_FLOW.md](DATA_FLOW.md) - 数据流
- [../modules/](../modules/) - 模块详细设计
