# 核心数据流

## 1. 消息发送流程

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

### 时序图

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
```

---

## 2. 图片处理流程

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

### 时序图

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

### 图片压缩策略

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

---

## 3. 代码上下文流程

```
编辑器选中文本 → SendSelectionAction
    ↓
创建 CodeContext → 附加到 InputPanel
    ↓
用户发送 → ChatPanel.consumeContexts()
    ↓
ConversationManager.buildRequestMessages() → 合并到用户消息
    ↓
发送到 Hermes Gateway
```

### 上下文合并逻辑

```kotlin
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
```

---

## 4. 对话切换流程

```
用户选择对话 → ChatPanel.switchConversation(id)
    ↓
ConversationManager.saveCurrentConversation()
    ↓
ConversationStore.loadConversation(id)
    ↓
解析 XML + 加载图片引用
    ↓
MessageListPanel.loadMessages() → 渲染历史消息
```

### 时序图

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

## 5. 流式响应处理

### 客户端流式处理

```kotlin
currentJob = scope.launch {
    try {
        val result = apiClient.streamChatWithSession(request, sessionId)
        
        result.deltas.collect { delta ->
            for (choice in delta.choices) {
                choice.delta.content?.let { content ->
                    ApplicationManager.getApplication().invokeLater {
                        streamingBubble?.appendToken(content)
                    }
                }
                
                choice.delta.toolCalls?.forEach { toolCall ->
                    ApplicationManager.getApplication().invokeLater {
                        streamingBubble?.showToolCall(toolCall)
                    }
                }
            }
        }
        
        // 流式完成，保存消息
        conversationManager.addAssistantMessage(responseBuilder.toString())
        conversationManager.saveCurrentConversation()
        
    } catch (e: Exception) {
        // 错误处理
    }
}
```

---

## 相关文档

- [OVERVIEW.md](OVERVIEW.md) - 架构概览
- [../persistence/CONVERSATION_STORE.md](../persistence/CONVERSATION_STORE.md) - 持久化存储
- [../api/SSE_STREAM.md](../api/SSE_STREAM.md) - SSE 流式协议
