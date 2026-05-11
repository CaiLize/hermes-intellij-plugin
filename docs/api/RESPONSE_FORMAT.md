# 响应格式规范

## 非流式响应

```json
{
  "id": "chat-123",
  "object": "chat.completion",
  "created": 1715500000,
  "model": "hermes-agent",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "当然可以！以下是优化建议..."
      },
      "finishReason": "stop"
    }
  ],
  "usage": {
    "promptTokens": 100,
    "completionTokens": 200,
    "totalTokens": 300
  }
}
```

## 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 响应 ID |
| `object` | String | 对象类型 |
| `created` | Integer | 创建时间戳 |
| `model` | String | 使用的模型 |
| `choices` | Array | 选择列表 |
| `usage` | Object | token 使用统计 |

## Choice 结构

```json
{
  "index": 0,
  "message": {
    "role": "assistant",
    "content": "响应内容"
  },
  "finishReason": "stop"
}
```

### finishReason 取值

| 值 | 说明 |
|-----|------|
| `stop` | 正常完成 |
| `length` | 达到 maxTokens 限制 |
| `tool_calls` | 触发了工具调用 |

## 响应头

| Header | 说明 |
|--------|------|
| `X-Session-ID` | 会话 ID，用于后续请求保持上下文 |
| `Content-Type` | `application/json` |

## 错误响应

### 401 Unauthorized

```json
{
  "error": {
    "message": "Invalid API key",
    "type": "authentication_error",
    "code": 401
  }
}
```

### 400 Bad Request

```json
{
  "error": {
    "message": "Invalid request format",
    "type": "invalid_request_error",
    "code": 400
  }
}
```

### 500 Internal Server Error

```json
{
  "error": {
    "message": "Internal server error",
    "type": "server_error",
    "code": 500
  }
}
```

## 相关文档

- [REQUEST_FORMAT.md](REQUEST_FORMAT.md) - 请求格式
- [SSE_STREAM.md](SSE_STREAM.md) - SSE 流式协议
- [../modules/API.md](../modules/API.md) - API 客户端实现
