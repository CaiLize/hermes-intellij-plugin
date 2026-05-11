# Hermes IntelliJ Plugin - 项目架构文档 (AGENTS.md)

## 1. 项目概览

| 属性 | 值 |
|------|-----|
| **项目名称** | Hermes AI Assistant |
| **项目 ID** | com.hermes.intellij |
| **版本** | 1.0.0 |
| **描述** | 连接本地部署的 Hermes Agent，提供 AI 驱动的编码辅助功能 |
| **核心特性** | 聊天面板、代码上下文集成、流式响应、代码插入/替换、多模态支持 |

---

## 2. 项目结构

```
hermes-intellij-plugin/
├── build.gradle.kts              # Gradle 构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # 属性配置（版本、兼容性）
├── AGENTS.md                     # 本文档
├── README.md                     # 项目说明
├── src/main/
│   ├── kotlin/com/hermes/intellij/
│   │   ├── actions/              # 用户操作（6 文件）
│   │   │   ├── HermesPasteAction.kt
│   │   │   ├── InsertCodeAction.kt
│   │   │   ├── NewChatAction.kt
│   │   │   ├── ReplaceSelectionAction.kt
│   │   │   ├── SendFileAction.kt
│   │   │   └── SendSelectionAction.kt
│   │   ├── api/                  # API 通信层（5 文件）
│   │   │   ├── HermesApiClient.kt
│   │   │   ├── SseStreamParser.kt
│   │   │   └── models/
│   │   │       ├── ChatRequest.kt
│   │   │       ├── ChatResponse.kt
│   │   │       └── StreamDelta.kt
│   │   ├── model/                # 数据模型（2 文件）
│   │   │   ├── ContextModels.kt
│   │   │   └── Conversation.kt
│   │   ├── services/             # 业务服务层（6 文件）
│   │   │   ├── ConversationManager.kt
│   │   │   ├── ConversationStore.kt
│   │   │   ├── HermesChatService.kt
│   │   │   ├── HermesSettingsState.kt
│   │   │   ├── ImageStore.kt
│   │   │   └── SelectionContextService.kt
│   │   ├── settings/             # 设置配置（1 文件）
│   │   │   └── HermesSettingsConfigurable.kt
│   │   └── toolwindow/           # 工具窗口 UI（9 文件）
│   │       ├── ChatColors.kt
│   │       ├── ChatPanel.kt
│   │       ├── CodeBlockPanel.kt
│   │       ├── HermesToolWindowFactory.kt
│   │       ├── InputPanel.kt
│   │       ├── MarkdownRenderer.kt
│   │       ├── MessageBubble.kt
│   │       ├── MessageListPanel.kt
│   │       └── NoStretchVerticalLayout.kt
│   └── resources/
│       ├── META-INF/plugin.xml   # 插件配置
│       ├── icons/                # SVG 图标（16 个）
│       └── messages/             # 国际化资源
└── .hermes/                      # 运行时图片存储目录
```

---

## 3. 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| **语言** | Kotlin | 1.9.25 |
| **平台** | IntelliJ Platform Plugin | 2.2.1 |
| **目标 IDE** | IntelliJ IDEA Community | 2024.1.7+ |
| **序列化** | kotlinx.serialization | 1.6.3 |
| **异步** | Kotlin Coroutines | 1.8.1 |
| **HTTP** | java.net.http.HttpClient | - |
| **UI** | Swing (IntelliJ UI) | - |
| **构建** | Gradle | 8.14.4 |
| **JDK** | JetBrains Runtime | 17 |

### 3.1 兼容性范围

- **最低版本**: 241 (IntelliJ IDEA 2024.1)
- **最高版本**: 273.*
- **JDK 目标**: 17

---

## 4. 模块职责划分

### 4.1 actions 包 - 用户交互操作

| 文件 | 职责 | 关键方法 |
|------|------|----------|
| `HermesPasteAction.kt` | 包装原始粘贴操作，支持 InputPanel 内图片/文件粘贴 | `actionPerformed()`, `update()` |
| `InsertCodeAction.kt` | 在编辑器光标位置插入代码 | `actionPerformed()` |
| `NewChatAction.kt` | 开始新对话 | `actionPerformed()` |
| `ReplaceSelectionAction.kt` | 替换编辑器选中内容 | `actionPerformed()` |
| `SendFileAction.kt` | 发送当前文件到聊天面板 | `actionPerformed()` |
| `SendSelectionAction.kt` | 发送选中代码到聊天面板 | `actionPerformed()`, 快捷键 `Alt+Shift+H` |

### 4.2 api 包 - API 通信层

| 文件 | 职责 | 关键方法 |
|------|------|----------|
| `HermesApiClient.kt` | HTTP 客户端，发送聊天请求，处理流式响应 | `streamChat()`, `testConnection()` |
| `SseStreamParser.kt` | 解析 SSE (Server-Sent Events) 流 | `parse()` |
| `ChatRequest.kt` | 请求模型，支持多模态内容 | `ChatMessage.userWithImages()` |
| `ChatResponse.kt` | 非流式响应模型 | `ChatResponse`, `Choice` |
| `StreamDelta.kt` | 流式响应增量模型 | `StreamDelta`, `Delta` |

### 4.3 model 包 - 数据模型

| 文件 | 职责 | 关键类 |
|------|------|--------|
| `ContextModels.kt` | UI 层使用的数据结构 | `CodeContext`, `ImageContext`, `FileAttachment` |
| `Conversation.kt` | 对话会话数据模型 | `Conversation`, `ConversationSummary` |

### 4.4 services 包 - 业务服务层

| 文件 | 职责 | 关键方法 |
|------|------|----------|
| `ConversationManager.kt` | 对话生命周期管理，消息构建与持久化 | `buildRequestMessages()`, `saveCurrentConversation()` |
| `ConversationStore.kt` | 对话持久化存储 (XML) | `saveConversationMessages()`, `loadConversation()` |
| `HermesChatService.kt` | 聊天核心服务，协调 UI 与 API | `sendMessage()`, `cancelCurrentRequest()` |
| `HermesSettingsState.kt` | 应用设置持久化 | `apiEndpoint`, `modelName`, `apiKey` |
| `ImageStore.kt` | 图片磁盘存储管理 | `saveImage()`, `loadImage()` |
| `SelectionContextService.kt` | 编辑器选区监听服务 | `initialize()`, `registerSelectionListener()` |

### 4.5 toolwindow 包 - 工具窗口 UI

| 文件 | 职责 | 关键组件 |
|------|------|----------|
| `ChatColors.kt` | 颜色常量定义 (支持亮/暗主题) | `ChatColors` |
| `ChatPanel.kt` | 聊天主面板，组合所有子组件 | `ChatPanel` |
| `CodeBlockPanel.kt` | 代码块渲染，带 Copy/Insert/Replace 按钮 | `CodeBlockPanel` |
| `HermesToolWindowFactory.kt` | 工具窗口工厂，注册服务 | `createToolWindowContent()` |
| `InputPanel.kt` | 消息输入面板，支持文件/图片粘贴 | `InputPanel`, `SendButton` |
| `MarkdownRenderer.kt` | Markdown 文本渲染 | `renderToComponents()` |
| `MessageBubble.kt` | 单条消息气泡组件 | `MessageBubble`, `State` |
| `MessageListPanel.kt` | 消息列表滚动面板 | `MessageListPanel` |

---

## 5. 核心架构设计

### 5.1 服务层级

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer (Swing)                       │
│  ChatPanel → MessageListPanel → MessageBubble               │
│  InputPanel → TransferHandler + KeyEventDispatcher          │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Service Layer (Business)                  │
│  HermesChatService (Project)                                │
│  ConversationManager → ConversationStore → ImageStore       │
│  SelectionContextService                                    │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      API Layer (HTTP)                       │
│  HermesApiClient (Application) → SseStreamParser            │
│  ChatRequest / ChatResponse / StreamDelta                   │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 服务作用域

| 服务 | 作用域 | 单例模式 |
|------|--------|----------|
| `HermesApiClient` | Application | `getInstance()` |
| `HermesSettingsState` | Application | `getInstance()` |
| `HermesChatService` | Project | `getInstance(project)` |
| `SelectionContextService` | Project | `getInstance(project)` |

### 5.3 插件扩展点 (plugin.xml)

```xml
<!-- 通知组 -->
<notificationGroup id="Hermes AI Notifications" displayType="BALLOON"/>

<!-- 工具窗口 -->
<toolWindow id="Hermes AI" anchor="right" 
            factoryClass="com.hermes.intellij.toolwindow.HermesToolWindowFactory"/>

<!-- 应用服务 -->
<applicationService serviceImplementation="...HermesApiClient"/>
<applicationService serviceImplementation="...HermesSettingsState"/>

<!-- 项目服务 -->
<projectService serviceImplementation="...HermesChatService"/>
<projectService serviceImplementation="...SelectionContextService"/>

<!-- 设置配置 -->
<applicationConfigurable id="hermes.settings" displayName="Hermes AI"/>

<!-- 快捷键 -->
<action id="HermesAI.SendSelection">
    <keyboard-shortcut keymap="$default" first-keystroke="alt shift H"/>
</action>
```

---

## 6. 核心数据流

### 6.1 消息发送流程

```
用户输入 → InputPanel
    ↓
ChatPanel.onUserSubmit() → 消费 contexts/images/files
    ↓
HermesChatService.sendMessage() → 构建请求
    ↓
ConversationManager.buildRequestMessages() → 合并上下文
    ↓
HermesApiClient.streamChat() → SSE 流式请求
    ↓
SseStreamParser 解析 → Flow<StreamDelta>
    ↓
ChatPanel.onTokenReceived() → MessageBubble.appendToken()
    ↓
MessageListPanel.finalizeStreaming() → 完整渲染
    ↓
ConversationManager → ConversationStore 持久化
```

### 6.2 图片处理流程

```
粘贴/拖拽图片 → InputPanel
    ↓
toBufferedImageSafe() → TYPE_INT_RGB 转换
    ↓
compressImageAdaptive() → 自适应压缩 (<2MB)
    ↓
ImageStore.saveImage() → 磁盘存储
    ↓
替换为 hermes-image:xxx 引用 → ConversationStore (XML)
```

### 6.3 代码上下文流程

```
编辑器选中文本 → SendSelectionAction
    ↓
创建 CodeContext → 附加到 InputPanel (Chip)
    ↓
发送消息时 → 合并到用户消息文本
    ↓
ConversationManager.buildRequestMessages() → 结构化格式
    ↓
发送到 API → Hermes 理解代码上下文
```

---

## 7. 关键设计模式

### 7.1 服务定位模式
- `HermesApiClient.getInstance()` - 应用级单例
- `HermesSettingsState.getInstance()` - 应用级单例
- `HermesChatService.getInstance(project)` - 项目级单例

### 7.2 持久化模式
- 使用 `PersistentStateComponent` 接口
- 存储位置：`{project}/.idea/hermes-conversations.xml`
- 图片存储：`{project}/.hermes/{conversationId}/`

### 7.3 流式处理模式
- 使用 Kotlin Coroutines `Flow` 处理 SSE 流
- UI 更新通过 `ApplicationManager.invokeLater()` 回到 EDT

### 7.4 观察者模式
- `SelectionContextService` 监听编辑器选区变化
- `ToolWindowManagerListener` 监听工具窗口显示状态

---

## 8. 配置默认值

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| API Endpoint | `http://127.0.0.1:8642/v1` | Hermes Gateway 地址 |
| Model | `hermes-agent` | 模型名称 |
| API Key | (空) | 可选认证 |
| 图片压缩目标 | `< 2MB` base64 | 自适应压缩 |
| 最大附件数 | 3 | 文件 + 图片总数 |
| 最大代码上下文 | 3 | 独立于附件 |
| 选区监听防抖 | 500ms | 避免频繁触发 |
| 请求体限制 | 15MB | 避免 API 拒绝 |

---

## 9. 图标资源

| 文件 | 用途 |
|------|------|
| `hermes_13.svg` | 插件主图标 (紫色圆形 + H) |
| `send_16.svg` | 发送按钮 |
| `stop_16.svg` | 停止按钮 |
| `attach_16.svg` | 附件按钮 |
| `dropdown_16.svg` | 下拉箭头 |
| `copy_16.svg` | 复制按钮 |
| `insert_16.svg` | 插入代码按钮 |
| `delete_16.svg` | 删除按钮 |
| `newchat_16.svg` | 新对话按钮 |
| `close_16.svg` | 关闭/删除图标 |

---

## 10. 修改前评估模板

每次修改功能前，必须填写以下评估：

### 10.1 影响范围分析

| 维度 | 评估项 | 影响文件 |
|------|--------|----------|
| **UI 组件** | Swing 组件、事件处理、布局 | `toolwindow/*.kt` |
| **API 调用** | 请求/响应模型、流式处理 | `api/*.kt`, `services/*.kt` |
| **持久化** | 对话/设置存储 | `services/*Store.kt`, `ImageStore.kt` |
| **编辑器集成** | 编辑器操作、上下文附加 | `actions/*.kt`, `SelectionContextService.kt` |
| **全局状态** | IntelliJ 全局 actions、快捷键 | `HermesToolWindowFactory.kt` |

### 10.2 风险点预判

```markdown
修改功能：_______________________
涉及的文件：_______________________
风险 1：_______________________ → 回退方案：_______________________
风险 2：_______________________ → 回退方案：_______________________
```

### 10.3 验证清单

- [ ] 文本粘贴正常（短文本、长文本）
- [ ] 图片粘贴正常（截图、文件拖拽）
- [ ] 文件粘贴正常（拖拽文件到输入框）
- [ ] Ctrl+V / Ctrl+Shift+V 行为一致
- [ ] 流式响应正常（发送、接收、取消）
- [ ] 对话持久化正常（切换、保存/加载）
- [ ] 设置页面可正常打开/保存

---

## 11. Bug 修复记录

详见 [CHANGELOG.md](./CHANGELOG.md)，包含 12 个已修复 Bug 的完整记录：

| 类别 | 数量 | 典型问题 |
|------|------|----------|
| UI 渲染 | 4 | Markdown 渲染 NPE、消息不显示 |
| 粘贴功能 | 4 | 文本重复、图片/文件失效 |
| 数据持久化 | 2 | XML 过大、对话切换丢失 |
| API 通信 | 1 | 请求体大小限制 |
| 状态管理 | 1 | 流式取消状态不一致 |

---

## 12. 开发指南

### 12.1 构建命令

```bash
# 构建插件
./gradlew buildPlugin

# 运行 IDE 沙盒
./gradlew runIde

# 输出位置
release/hermes-ai-assistant-1.0.0.zip
```

### 12.2 代码规范

- **命名**: Kotlin 驼峰命名，UI 组件使用描述性名称
- **日志**: 使用 `Logger.getInstance(ClassName::class.java)`，前缀 `[模块名]`
- **异步**: 重 I/O 操作使用 `Thread` 或 `CoroutineScope`，UI 更新用 `invokeLater`
- **资源**: 图标使用 `IconLoader.getIcon("/icons/xxx.svg", ClassName::class.java)`

### 12.3 调试技巧

- **UI 调试**: 在沙盒 IDE 中右键 → Diagnose → Show Layout
- **日志查看**: `Help → Show Log in Explorer`
- **网络调试**: 使用 Hermes Gateway 日志查看 API 请求

---

## 13. 文件清单 (共 29 个 Kotlin 文件)

| 包 | 文件数 | 文件列表 |
|-----|--------|----------|
| `actions` | 6 | HermesPasteAction, InsertCodeAction, NewChatAction, ReplaceSelectionAction, SendFileAction, SendSelectionAction |
| `api` | 5 | HermesApiClient, SseStreamParser, ChatRequest, ChatResponse, StreamDelta |
| `model` | 2 | ContextModels, Conversation |
| `services` | 6 | ConversationManager, ConversationStore, HermesChatService, HermesSettingsState, ImageStore, SelectionContextService |
| `settings` | 1 | HermesSettingsConfigurable |
| `toolwindow` | 9 | ChatColors, ChatPanel, CodeBlockPanel, HermesToolWindowFactory, InputPanel, MarkdownRenderer, MessageBubble, MessageListPanel, NoStretchVerticalLayout |

---

---

## 14. 详细文档

完整的设计文档位于 `docs/` 目录：

| 目录 | 说明 |
|------|------|
| [docs/architecture/](docs/architecture/) | 架构设计（概览、分层、数据流） |
| [docs/modules/](docs/modules/) | 模块设计（Actions、API、Services、ToolWindow） |
| [docs/models/](docs/models/) | 数据模型（ChatMessage、ContentPart、MessageSegment） |
| [docs/api/](docs/api/) | API 接口规范（请求、响应、SSE 协议） |
| [docs/persistence/](docs/persistence/) | 持久化设计（对话存储、图片存储、迁移策略） |
| [docs/ui/](docs/ui/) | UI 设计（颜色、消息气泡、Markdown 渲染） |
| [docs/decisions/](docs/decisions/) | 架构决策记录（ADR） |

详见 [docs/README.md](docs/README.md) 获取完整文档索引。

---

*文档生成时间：2026-05-12 | 版本：1.0.0*
