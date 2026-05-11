# 多模态内容（图片、文件）

## ContentPart 密封类

用于表示消息中的多模态内容（文本、图片）：

```kotlin
@Serializable
sealed class ContentPart {
    
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart()
    
    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        @SerialName("image_url")
        val imageUrl: ImageUrlData
    ) : ContentPart()
}

@Serializable
data class ImageUrlData(
    val url: String,
    val detail: String = "auto"
)
```

## FileAttachmentData

文件附件元数据：

```kotlin
@Serializable
data class FileAttachmentData(
    val filePath: String,
    val lineRange: String? = null,
    val language: String = ""
)
```

## UI 模型

### ImageContext

```kotlin
data class ImageContext(
    val base64Data: String,      // base64 编码的图片数据
    val thumbnail: BufferedImage  // 缩略图用于 UI 显示
)
```

### ImageAttachment

```kotlin
data class ImageAttachment(
    val base64Data: String,
    val mimeType: String = "image/png"
)
```

### FileAttachment

```kotlin
data class FileAttachment(
    val filePath: String,
    val content: String,
    val language: String = ""
)
```

## 图片处理流程

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

### 图片压缩

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

### 图片安全转换

```kotlin
private fun toBufferedImageSafe(image: Image): BufferedImage {
    val bufferedImage = BufferedImage(
        image.getWidth(null),
        image.getHeight(null),
        BufferedImage.TYPE_INT_RGB
    )
    
    val g2d = bufferedImage.createGraphics()
    g2d.drawImage(image, 0, 0, null)
    g2d.dispose()
    
    return bufferedImage
}
```

## 请求格式示例

```json
{
  "model": "hermes-agent",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "解释这段代码"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
      ]
    }
  ],
  "stream": true
}
```

## 相关文档

- [CHAT_MESSAGE.md](CHAT_MESSAGE.md) - ChatMessage 模型
- [../persistence/IMAGE_STORE.md](../persistence/IMAGE_STORE.md) - 图片存储
- [../architecture/DATA_FLOW.md](../architecture/DATA_FLOW.md) - 图片处理流程
