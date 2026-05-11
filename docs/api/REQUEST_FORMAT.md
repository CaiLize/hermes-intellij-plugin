# 请求格式规范

## 基础请求结构

```json
{
  "model": "hermes-agent",
  "messages": [...],
  "stream": true,
  "maxTokens": 4096,
  "temperature": 0.7
}
```

## 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | String | 是 | 模型名称 |
| `messages` | Array | 是 | 消息列表 |
| `stream` | Boolean | 否 | 是否流式响应（默认 true） |
| `maxTokens` | Integer | 否 | 最大 token 数 |
| `temperature` | Float | 否 | 温度参数（0-2） |

## Messages 格式

### System Message

```json
{
  "role": "system",
  "content": "You are Hermes, an AI coding assistant..."
}
```

### User Message（纯文本）

```json
{
  "role": "user",
  "content": "解释这段代码"
}
```

### User Message（多模态）

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "解释这段代码"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
  ]
}
```

### User Message（带文件附件）

```json
{
  "role": "user",
  "content": "帮我优化这段代码",
  "fileAttachments": [
    {
      "filePath": "/src/main.kt",
      "lineRange": "10-50",
      "language": "kotlin"
    }
  ]
}
```

### Assistant Message

```json
{
  "role": "assistant",
  "content": "当然可以！以下是优化建议..."
}
```

## 请求大小限制

- **最大请求体**：15MB
- **建议**：超过 10MB 时提示用户减少上下文或压缩图片

## 示例：完整请求

```json
{
  "model": "hermes-agent",
  "messages": [
    {
      "role": "system",
      "content": "You are Hermes, an AI coding assistant. You help users with coding tasks."
    },
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "帮我优化这段代码"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KG..."}}
      ],
      "fileAttachments": [
        {
          "filePath": "/Users/dev/project/src/main.kt",
          "lineRange": "10-50",
          "language": "kotlin"
        }
      ]
    }
  ],
  "stream": true,
  "maxTokens": 4096,
  "temperature": 0.7
}
```

## 相关文档

- [RESPONSE_FORMAT.md](RESPONSE_FORMAT.md) - 响应格式
- [SSE_STREAM.md](SSE_STREAM.md) - SSE 流式协议
- [../models/CHAT_MESSAGE.md](../models/CHAT_MESSAGE.md) - ChatMessage 模型
