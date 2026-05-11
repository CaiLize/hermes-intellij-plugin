# SSE 流式响应协议

## 协议说明

使用 Server-Sent Events (SSE) 协议进行流式响应，每行格式为：

```
data: <JSON 对象>
```

空行表示一个完整的事件结束。

## 流式响应格式

### 文本增量

```
data: {"id":"chat-123","choices":[{"delta":{"content":"H"},"index":0}]}

data: {"id":"chat-123","choices":[{"delta":{"content":"ello"},"index":0}]}

data: {"id":"chat-123","choices":[{"delta":{"content":" World"},"index":0}]}
```

### 工具调用

```
data: {"id":"chat-123","choices":[{"delta":{"toolCalls":[{"id":"call_1","type":"function","function":{"name":"search","arguments":"{\"query\": \"..."}}]},"index":0}]}
```

### 流式结束

```
data: [DONE]
```

## StreamDelta 结构

```kotlin
@Serializable
data class StreamDelta(
    val id: String,
    val choices: List<Choice>,
    val created: Long
) {
    @Serializable
    data class Choice(
        val delta: Delta,
        val index: Int
    )
    
    @Serializable
    data class Delta(
        val content: String? = null,
        val toolCalls: List<ToolCallDelta>? = null
    )
    
    @Serializable
    data class ToolCallDelta(
        val id: String,
        val type: String = "function",
        val function: FunctionDelta
    )
    
    @Serializable
    data class FunctionDelta(
        val name: String,
        val arguments: String
    )
}
```

## SSE 解析器

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
        
        if (dataLine == "[DONE]") {
            return null  // 结束标记
        }
        
        return try {
            Json.decodeFromString(StreamDelta.serializer(), dataLine)
        } catch (e: Exception) {
            null
        }
    }
}
```

## 客户端处理

```kotlin
currentJob = scope.launch {
    try {
        val result = apiClient.streamChatWithSession(request, sessionId)
        
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
        
        // 流式完成
        conversationManager.saveCurrentConversation()
        
    } catch (e: Exception) {
        // 错误处理
    }
}
```

## 相关文档

- [REQUEST_FORMAT.md](REQUEST_FORMAT.md) - 请求格式
- [RESPONSE_FORMAT.md](RESPONSE_FORMAT.md) - 响应格式
- [../modules/API.md](../modules/API.md) - API 客户端实现
