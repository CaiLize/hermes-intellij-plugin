# Hermes IntelliJ Plugin - 变更日志

## Bug 修复记录

### Bug #1: 图片颜色空间异常

**问题**: 处理某些 JPEG 图片时抛出 "Bogus input colorspace" CMM 异常

**根因**: ImageIO 读取某些 YCbCr 色彩空间的 JPEG 时，Java CMM 库无法正确处理

**修复方案**: 
- `InputPanel.kt` 添加 `normalizeColorSpaceSafe()` 方法
- 强制转换为 `TYPE_INT_RGB` 格式
- 添加异常捕获回退

**影响文件**: 
- `toolwindow/InputPanel.kt` (`handleImageFile()`, `handleImageFromClipboardImage()`, `handleImagePaste()`)

**状态**: ✅ 已修复

---

### Bug #2: Markdown 渲染 NPE

**问题**: `JEditorPane` CSS 解析导致空指针异常，消息内容不显示

**根因**: 外部 CSS 样式表加载失败时，`StyleSheet` 为 null

**修复方案**: 
- 使用完全内联样式，不依赖外部 CSS
- 添加异常捕获，回退到 `JLabel` 渲染
- 简化 HTML 结构

**影响文件**: 
- `toolwindow/MarkdownRenderer.kt`
- `toolwindow/MessageBubble.kt`

**状态**: ✅ 已修复

---

### Bug #3: 请求体大小限制

**问题**: 对话历史很长时请求体超过 API 限制，导致请求被拒绝

**根因**: 未对请求体大小进行检查

**修复方案**: 
- `HermesChatService.sendMessage()` 添加 15MB 限制检查
- 在发送前序列化并检查大小
- 超出限制时提前返回并提示用户

**影响文件**: 
- `services/HermesChatService.kt`

**状态**: ✅ 已修复

---

### Bug #4: 上下文重复发送

**问题**: 代码上下文作为单独消息发送，导致连续 user 角色消息，API 拒绝处理

**根因**: `ConversationManager` 将代码上下文作为独立消息添加到消息列表

**修复方案**: 
- `buildRequestMessages()` 将上下文合并到用户消息文本中
- 使用结构化格式（`## User Request` + `## Code Context`）
- 保持消息角色交替

**影响文件**: 
- `services/ConversationManager.kt`

**状态**: ✅ 已修复

---

### Bug #5: 对话切换时消息丢失

**问题**: 切换对话时当前对话的消息未保存，导致数据丢失

**根因**: `switchConversation()` 直接加载新对话，未保存当前对话

**修复方案**: 
- 切换前先调用 `saveCurrentConversation()`
- 保存后再加载新对话

**影响文件**: 
- `services/ConversationManager.kt`

**状态**: ✅ 已修复

---

### Bug #6: Paste 操作冲突

**问题**: 替换全局 `$Paste` action 后，在 InputPanel 外无法粘贴

**根因**: `HermesPasteAction` 拦截了所有粘贴事件，未正确判断焦点位置

**修复方案**: 
- `HermesPasteAction` 检查焦点是否在 InputPanel 内
- 焦点在 InputPanel 时由 `KeyEventDispatcher` 处理
- 焦点在其他地方时调用原始 `$Paste` action

**影响文件**: 
- `actions/HermesPasteAction.kt`
- `toolwindow/HermesToolWindowFactory.kt`

**状态**: ✅ 已修复

---

### Bug #7: 图片持久化导致 XML 过大

**问题**: base64 图片直接存入 XML，导致文件过大、加载缓慢

**根因**: `ConversationStore` 将完整 base64 数据存入 XML

**修复方案**: 
- 创建 `ImageStore` 将图片保存到磁盘 `{project}/.hermes/{conversationId}/`
- XML 只存储引用 `hermes-image:filename`
- 加载时从磁盘读取并还原 base64

**影响文件**: 
- `services/ImageStore.kt` (新增)
- `services/ConversationStore.kt`
- `services/ConversationManager.kt`

**状态**: ✅ 已修复

---

### Bug #8: 流式响应取消后状态不一致

**问题**: 取消流式响应后，消息气泡状态不一致，部分响应丢失

**根因**: `CancellationException` 未捕获，部分响应未保存

**修复方案**: 
- 捕获 `CancellationException`
- 保存已接收的部分响应
- UI 显示 `(Response cancelled)` 提示
- 确保 `finalizeStreaming()` 被调用

**影响文件**: 
- `services/HermesChatService.kt`

**状态**: ✅ 已修复

---

### Bug #9: 聊天消息不显示

**问题**: 用户消息内容完全不显示，气泡为空

**根因**: 
1. `MarkdownRenderer.cleanText()` 正则表达式 ``
3. `MessageBubble` 的 `contentPanel` 缺少 `alignmentX/alignmentY`
4. `InputPanel` 容器布局问题

**修复方案**: 
1. `cleanText` 改为两步：先移除完整 `<think>...</think>`，再处理孤立 `<think>`
2. 修正正则：`Pattern.compile("<think>[\\s\\S]*?" + Pattern.quote("</think>"), ...)`
3. `MessageBubble.kt`：给 `contentPanel` 添加 `alignmentX = CENTER_ALIGNMENT`, `alignmentY = TOP_ALIGNMENT`
4. `InputPanel.kt`：容器从 `BorderLayout` 改为 `FlowLayout`

**影响文件**: 
- `toolwindow/MarkdownRenderer.kt`
- `toolwindow/MessageBubble.kt`
- `toolwindow/InputPanel.kt`

**状态**: ✅ 已修复

---

### Bug #10: 文本粘贴重复

**问题**: Ctrl+V 粘贴文本时内容被重复两遍

**根因**: `KeyEventDispatcher` 调用 `pasteFromClipboard()`，`pasteFromClipboard()` 又调用 `tryPasteAsText()` 和 `textArea.paste()`，导致 TransferHandler 处理两次

**修复方案**: 
- `pasteFromClipboard()` 只负责检测图片/文件
- 纯文本直接调用 `textArea.paste()`，让 Swing 默认机制处理
- 不再重复插入文本

**影响文件**: 
- `toolwindow/InputPanel.kt`

**状态**: ✅ 已修复

---

### Bug #11: 图片/文件粘贴失效

**问题**: 修复 Bug #10 后 Ctrl+V 无法粘贴图片和文件

**根因**: `KeyEventDispatcher` 不再检测剪贴板内容类型

**修复方案**: 
- 恢复 `KeyEventDispatcher` 专门检测 `javaFileListFlavor` 和 `imageFlavor`
- 只在剪贴板有文件/图片时才拦截 Ctrl+V
- 纯文本时完全放行

**影响文件**: 
- `toolwindow/InputPanel.kt`

**状态**: ✅ 已修复

---

### Bug #12: 历史对话内容不显示

**问题**: 加载历史对话时，消息气泡显示为空（只有头像和时间戳）

**根因**: `MarkdownRenderer.cleanText()` 中，当消息包含未闭合的 `<think>` 标签时，会把整个消息内容删掉（`result = result.substring(0, thinkOpenIdx)`）

**修复方案**: 
1. 只移除完整的 `<think>...</think>` 标签对
2. 对于孤立的 `<think>` 标签，只移除标签本身，保留其余内容
3. 添加单元测试验证各种边界情况

**影响文件**: 
- `toolwindow/MarkdownRenderer.kt`

**状态**: ✅ 已修复

---

## 版本发布记录

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

## 修复统计

| 类别 | 数量 |
|------|------|
| UI 渲染 | 4 |
| 粘贴功能 | 4 |
| 数据持久化 | 2 |
| API 通信 | 1 |
| 状态管理 | 1 |
| **总计** | **12** |

---

*最后更新：2026-05-02*
