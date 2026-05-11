# API 通信层

## 模块职责

负责与 Hermes Gateway 进行 HTTP 通信，包括请求构建、响应解析、SSE 流式处理。

---

## 文件清单

| 文件 | 职责 | 关键方法 |
|------|------|----------|
| `HermesApiClient.kt` | HTTP 客户端，发送聊天请求，处理流式响应 | `streamChatWithSession()`, `testConnection()` |
| `SseStreamParser.kt` | 解析 SSE (Server-Sent Events) 流 | `parse()` |
| `ChatRequest.kt` | 请求模型，支持多模态内容 | `ChatMessage.userWithImages()` |
| `ChatResponse.kt` | 非流式响应模型 | `ChatResponse`, `Choice` |
| `StreamDelta.kt` | 流式响应增量模型 | `StreamDelta`, `Delta` |

---

## 核心实现

### HermesApiClient

HTTP 客户端，使用 Java HttpClient：

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
    
    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean {
        return try {
            val url = settings.apiEndpoint.trimEnd('/') + "/health"
            val response = httpClient.sendAsync(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            ).await()
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }
}
```

---

### SseStreamParser

SSE 流解析器：

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

---

## 数据模型

### ChatRequest

```kotlin
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val maxTokens: Int? = null,
    val temperature: Double? = null
)
```

### ChatMessage

```kotlin
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,              // "system" | "user" | "assistant"
    val content: String,           // 纯文本内容
    val contentParts: List<ContentPart>? = null,  // 多模态内容
    val fileAttachments: List<FileAttachmentData>? = null
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

### StreamDelta

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
}
```

---

## 错误处理

```kotlin
class HermesApiException(
    val statusCode: Int,
    message: String
) : Exception("API Error ($statusCode): $message")

// 使用示例
currentJob = scope.launch {
    try {
        val result = apiClient.streamChatWithSession(request, sessionId)
        // ...
    } catch (e: ConnectException) {
        // 连接失败
        chatPanel.onStreamingError("无法连接到 Hermes，请检查 Gateway 是否运行")
    } catch (e: HermesApiException) {
        // API 错误
        chatPanel.onStreamingError("API 错误 (${e.statusCode}): ${e.message}")
    } catch (e: Exception) {
        // 其他错误
        chatPanel.onStreamingError("错误：${e.message}")
    }
}
```

---

## 响应头

| Header | 说明 |
|--------|------|
| `X-Session-ID` | 会话 ID，用于后续请求保持上下文 |

---

## 相关文档

- [../architecture/DATA_FLOW.md](../architecture/DATA_FLOW.md) - 数据流
- [../api/REQUEST_FORMAT.md](../api/REQUEST_FORMAT.md) - 请求格式
- [../api/RESPONSE_FORMAT.md](../api/RESPONSE_FORMAT.md) - 响应格式
- [../api/SSE_STREAM.md](../api/SSE_STREAM.md) - SSE 协议
