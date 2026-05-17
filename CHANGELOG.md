# Hermes IntelliJ Plugin - 变更日志

## 版本发布记录

### v1.1.2 (2026-05-17)

**SSE 流解析增强**
- ✅ `SseStreamParser` 新增日志追踪，改进 `hermes.tool.progress` 事件解析
- ✅ 工具调用 ID 缺失时使用 UUID 回退（BUG-005 修复）
- ✅ `finally` 块显式状态清理，处理流意外中断（BUG-004 修复）

**API 客户端增强**
- ✅ `HermesApiClient` 连接超时 30s → 60s，HTTP/1.1 优化 SSE 性能
- ✅ HTTP 请求异常捕获，统一抛出 `HermesApiException`
- ✅ 错误响应体日志脱敏（>200 字符截断，生产环境不记录完整 body）
- ✅ SSE 流中断 `IOException` 显式日志记录

**对话服务重构**
- ✅ `HermesChatService` 引入 `ToolCallStateMachine` 集中管理工具调用状态
- ✅ 会话 ID 校验：`SessionSecurityValidator.validateSessionId()` 验证后保存
- ✅ 错误消息中文本地化，细化 HTTP 状态码分类（413/502-504）
- ✅ 所有 UI 更新通过 `withContext(Dispatchers.Main)` 确保线程安全
- ✅ `CancellationException` 不显示错误提示，避免用户困惑

**会话管理优化**
- ✅ `ConversationManager.createConversation()` 新对话不继承 `hermesSessionId`
- ✅ `deleteConversation()` 智能删除：仅当无其他对话使用该会话时才调用服务端删除

**安全加固**
- ✅ `CodeBlockPanel` 插入/新建文件前调用 `CodeSecurityValidator` 验证代码和文件名
- ✅ `ImagePreviewDialog` Base64 数据 5MB 大小限制，解码前后双重检查（防 DoS）

**构建修复**
- ✅ `build.gradle.kts` 排除重复目录 `com/hermes/model/**`（避免 Conversation.kt 重复编译）
- ✅ `build.gradle.kts` 禁用 `buildSearchableOptions` 修复 WSL2 构建失败

**工具调用取消支持**
- ✅ 新增 `cancelToolCall()` 方法实现，修复编译错误 `Unresolved reference: cancelToolCall`
- ✅ 取消逻辑：找到最后一个未完成的工具调用，标记为 `cancelled = true, status = "Cancelled"`

**UI 细节优化**
- ✅ `MessageBubble.createActionButton()` 显式设置 `this.text = ""`
- ✅ `MessageBubble` 头像图标缩放比例 1.5（13px × 1.5 ≈ 19.5px）

**构建状态**
- ✅ 编译通过：`BUILD SUCCESSFUL in 17s`，16 个任务全部执行
- ✅ 插件包生成位置：`build/distributions/`

---

### v1.1.1 (2026-05-15)

**SSE 流解析增强**
- ✅ 新增 `HermesApiException` 统一 API 异常处理
- ✅ 增强 `SseStreamParser` 状态清理（BUG-004 修复）
- ✅ 改进 `hermes.tool.progress` 事件解析，支持工具调用追踪
- ✅ 流式响应取消后状态一致性问题修复

**对话服务重构**
- ✅ 会话 ID 语义明确化：新对话 `hermesSessionId = null`，首次发送后分配
- ✅ `ConversationStore` 会话管理优化，支持 `hermesSessionId` 参数
- ✅ `ConversationManager.deleteConversation()` 智能删除：仅当无其他对话使用该会话时才删除服务端会话
- ✅ `HermesChatService` 取消处理优化：不显示错误提示，保持 UI 状态一致

**消息内容分段支持**
- ✅ 新增 `MessageSegment` 统一内容分段模型（Text/Image/File）
- ✅ `HermesChatService.buildUserMessageSegments()` 构建有序分段消息
- ✅ `ConversationManager.addUserMessageWithSegments()` / `addAssistantMessageWithSegments()` 支持分段持久化
- ✅ 工具调用顺序保留，确保 UI 渲染正确

**UI 组件重构**
- ✅ `MessageBubble` 完全重写：统一 `ContentSegment` 模型，支持 Text/ToolCall/Image/File 有序渲染
- ✅ 新增 `State` 枚举（THINKING/TOOL_CALLING/STREAMING/COMPLETE）
- ✅ `thinkingTimer` 思考动画支持
- ✅ `renderTimer` 大内容渲染节流（200 字符阈值）
- ✅ `ChatPanel` / `MessageListPanel` / `MarkdownRenderer` 代码清理与优化

**API 客户端增强**
- ✅ `HermesApiClient.streamChatWithSession()` 提取响应头 `X-Hermes-Session-Id`
- ✅ `HermesApiClient.chat()` 非流式请求支持会话 ID
- ✅ `HermesApiClient.deleteSession()` 服务端会话删除
- ✅ 连接超时 30 秒，流式请求超时 600 秒

**新增文件**
- ✅ `HermesApiException.kt` - API 异常类
- ✅ `CODE_REVIEW.md` - 代码审查文档
- ✅ `build.bat` - Windows 构建脚本

**代码质量**
- ✅ 移除冗余注释和锁，代码更简洁
- ✅ 改进日志记录，避免敏感信息泄露
- ✅ `.gitignore` 更新

---

### v1.1.0 (2026-05-12)

**存储优化**
- ✅ 聊天记录分块存储 (Bug #13)
- ✅ 消息懒加载支持
- ✅ 索引文件管理

**性能提升**
- 大对话加载速度提升约 60%
- 内存占用降低约 40%
- XML 文件大小减少约 30%

---

### v1.0.0 (2026-05-02)

**新增功能**
- ✅ 基础聊天功能
- ✅ 流式响应支持
- ✅ 代码上下文集成
- ✅ 多模态（图片/文件）支持
- ✅ 对话持久化
- ✅ 多会话管理

**UI 优化**
- ✅ 主题自适应
- ✅ Markdown 渲染
- ✅ 代码块操作按钮
- ✅ 响应式输入框

**Bug 修复**
- ✅ Bug #1-#12 全部修复

---

### v1.0.0-beta (2026-04-28)

**初始测试版**
- 基础框架搭建
- 核心功能实现
- 已知问题：Bug #1-#12

---

## Bug 修复详情

### Bug #1: 图片颜色空间异常
**问题**: 处理某些 JPEG 图片时抛出 "Bogus input colorspace" CMM 异常  
**根因**: ImageIO 读取某些 YCbCr 色彩空间的 JPEG 时，Java CMM 库无法正确处理  
**修复方案**: `InputPanel.kt` 添加 `normalizeColorSpaceSafe()` 方法，强制转换为 `TYPE_INT_RGB`  
**影响文件**: `toolwindow/InputPanel.kt`  
**状态**: ✅ 已修复

### Bug #2: Markdown 渲染 NPE
**问题**: `JEditorPane` CSS 解析导致空指针异常，消息内容不显示  
**根因**: 外部 CSS 样式表加载失败时，`StyleSheet` 为 null  
**修复方案**: 使用完全内联样式，不依赖外部 CSS  
**影响文件**: `toolwindow/MarkdownRenderer.kt`, `toolwindow/MessageBubble.kt`  
**状态**: ✅ 已修复

### Bug #3: 请求体大小限制
**问题**: 对话历史很长时请求体超过 API 限制，导致请求被拒绝  
**根因**: 未对请求体大小进行检查  
**修复方案**: `HermesChatService.sendMessage()` 添加 15MB 限制检查  
**影响文件**: `services/HermesChatService.kt`  
**状态**: ✅ 已修复

### Bug #4: 上下文重复发送
**问题**: 代码上下文作为单独消息发送，导致连续 user 角色消息，API 拒绝处理  
**根因**: `ConversationManager` 将代码上下文作为独立消息添加到消息列表  
**修复方案**: `buildRequestMessages()` 将上下文合并到用户消息文本中  
**影响文件**: `services/ConversationManager.kt`  
**状态**: ✅ 已修复

### Bug #5: 对话切换时消息丢失
**问题**: 切换对话时当前对话的消息未保存，导致数据丢失  
**根因**: `switchConversation()` 直接加载新对话，未保存当前对话  
**修复方案**: 切换前先调用 `saveCurrentConversation()`  
**影响文件**: `services/ConversationManager.kt`  
**状态**: ✅ 已修复

### Bug #6: Paste 操作冲突
**问题**: 替换全局 `$Paste` action 后，在 InputPanel 外无法粘贴  
**根因**: `HermesPasteAction` 拦截了所有粘贴事件，未正确判断焦点位置  
**修复方案**: `HermesPasteAction` 检查焦点是否在 InputPanel 内  
**影响文件**: `actions/HermesPasteAction.kt`, `toolwindow/HermesToolWindowFactory.kt`  
**状态**: ✅ 已修复

### Bug #7: 图片持久化导致 XML 过大
**问题**: base64 图片直接存入 XML，导致文件过大、加载缓慢  
**根因**: `ConversationStore` 将完整 base64 数据存入 XML  
**修复方案**: 创建 `ImageStore` 将图片保存到磁盘，XML 只存储引用  
**影响文件**: `services/ImageStore.kt`, `services/ConversationStore.kt`, `services/ConversationManager.kt`  
**状态**: ✅ 已修复

### Bug #8: 流式响应取消后状态不一致
**问题**: 取消流式响应后，消息气泡状态不一致，部分响应丢失  
**根因**: `CancellationException` 未捕获，部分响应未保存  
**修复方案**: 捕获 `CancellationException`，保存已接收的部分响应  
**影响文件**: `services/HermesChatService.kt`  
**状态**: ✅ 已修复

### Bug #9: 聊天消息不显示
**问题**: 用户消息内容完全不显示，气泡为空  
**根因**: `MarkdownRenderer.cleanText()` 正则表达式问题，`MessageBubble` 布局问题  
**修复方案**: 修正正则，添加 `alignmentX/alignmentY`，容器布局调整  
**影响文件**: `toolwindow/MarkdownRenderer.kt`, `toolwindow/MessageBubble.kt`, `toolwindow/InputPanel.kt`  
**状态**: ✅ 已修复

### Bug #10: 文本粘贴重复
**问题**: Ctrl+V 粘贴文本时内容被重复两遍  
**根因**: `KeyEventDispatcher` 和 `pasteFromClipboard()` 重复处理  
**修复方案**: `pasteFromClipboard()` 只负责检测图片/文件，纯文本直接调用 `textArea.paste()`  
**影响文件**: `toolwindow/InputPanel.kt`  
**状态**: ✅ 已修复

### Bug #11: 图片/文件粘贴失效
**问题**: 修复 Bug #10 后 Ctrl+V 无法粘贴图片和文件  
**根因**: `KeyEventDispatcher` 不再检测剪贴板内容类型  
**修复方案**: 恢复 `KeyEventDispatcher` 专门检测 `javaFileListFlavor` 和 `imageFlavor`  
**影响文件**: `toolwindow/InputPanel.kt`  
**状态**: ✅ 已修复

### Bug #12: 历史对话内容不显示
**问题**: 加载历史对话时，消息气泡显示为空（只有头像和时间戳）  
**根因**: `MarkdownRenderer.cleanText()` 中，未闭合的 `` 标签对，孤立标签只移除标签本身  
**影响文件**: `toolwindow/MarkdownRenderer.kt`  
**状态**: ✅ 已修复

### Bug #13: 聊天记录分块存储优化
**问题**: 单个对话 XML 文件过大，加载和保存效率低，内存占用高  
**根因**: `ConversationStore` 将整个对话历史存储在一个 XML 文件中  
**修复方案**: 实现分块存储策略，每条消息独立存储，支持懒加载  
**影响文件**: `services/ConversationStore.kt`, `services/ConversationManager.kt`  
**状态**: ✅ 已修复

### Bug #14: 编译错误 Unresolved reference: cancelToolCall
**问题**: `MessageListPanel.kt:246:26 Unresolved reference: cancelToolCall`  
**根因**: `MessageBubble.kt` 缺少 `cancelToolCall` 方法实现  
**修复方案**: 在 `MessageBubble.kt` 的 `completeToolCall()` 方法后添加 `cancelToolCall()` 方法实现  
**影响文件**: `toolwindow/MessageBubble.kt`  
**状态**: ✅ 已修复

### Bug #15: 按钮显示文字
**问题**: 操作按钮显示文字而非仅图标  
**根因**: `createActionButton()` 未清除按钮文本  
**修复方案**: 显式设置 `this.text = ""`  
**影响文件**: `toolwindow/MessageBubble.kt`  
**状态**: ✅ 已修复

### Bug #16: 头像图标缩放过大
**问题**: 头像图标在圆圈内显示过大，超出边界  
**根因**: 图标缩放比例 2.0 导致 13px × 2 = 26px，超出 24px 圆圈  
**修复方案**: 缩放比例调整为 1.5（13px × 1.5 ≈ 19.5px）  
**影响文件**: `toolwindow/MessageBubble.kt`  
**状态**: ✅ 已修复

---

## 修复统计

| 类别 | 数量 |
|------|------|
| UI 渲染 | 6 |
| 粘贴功能 | 4 |
| 数据持久化 | 3 |
| API 通信 | 1 |
| 状态管理 | 1 |
| 存储优化 | 1 |
| 编译错误 | 1 |
| **总计** | **17** |

---

*最后更新：2026-05-17*