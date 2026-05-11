# 图片存储管理

## 存储位置

```
{project}/.hermes/{conversationId}/{timestamp}_{hash}.png
```

## ImageStore 实现

```kotlin
class ImageStore(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): ImageStore {
            return project.getService(ImageStore::class.java)
        }
    }
    
    /**
     * 保存图片到磁盘
     * @param conversationId 对话 ID
     * @param base64DataUrl base64 编码的图片数据（data:image/png;base64,xxx）
     * @return 图片引用（hermes-image:xxx）
     */
    fun saveImage(conversationId: String, base64DataUrl: String): String? {
        try {
            val fileName = "${System.currentTimeMillis()}_${generateHash()}.png"
            val imageDir = File(project.basePath, ".hermes/$conversationId")
            imageDir.mkdirs()
            
            val base64Data = base64DataUrl.removePrefix("data:image/png;base64,")
            val imageBytes = Base64.getDecoder().decode(base64Data)
            
            val imageFile = File(imageDir, fileName)
            imageFile.writeBytes(imageBytes)
            
            // 返回引用，不存储完整 base64
            return "hermes-image:$fileName"
        } catch (e: Exception) {
            Logger.getInstance(ImageStore::class.java).warn("Failed to save image", e)
            return null
        }
    }
    
    /**
     * 从磁盘加载图片
     * @param conversationId 对话 ID
     * @param fileName 文件名
     * @return base64 编码的图片数据
     */
    fun loadImage(conversationId: String, fileName: String): String? {
        try {
            val imageFile = File(project.basePath, ".hermes/$conversationId/$fileName")
            if (!imageFile.exists()) return null
            
            val base64Data = Base64.getEncoder().encodeToString(imageFile.readBytes())
            return "data:image/png;base64,$base64Data"
        } catch (e: Exception) {
            Logger.getInstance(ImageStore::class.java).warn("Failed to load image", e)
            return null
        }
    }
    
    /**
     * 清理对话图片
     */
    fun cleanupConversationImages(conversationId: String) {
        val imageDir = File(project.basePath, ".hermes/$conversationId")
        if (imageDir.exists()) {
            imageDir.deleteRecursively()
        }
    }
    
    private fun generateHash(): String {
        return UUID.randomUUID().toString().take(8)
    }
}
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

### 自适应压缩

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

## 图片引用格式

### 存储格式（XML）

```xml
<message role="user">
    <content>这是图片 hermes-image:1715500000_abc12345.png</content>
</message>
```

### 加载后格式（内存）

```
data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...
```

## 清理策略

```kotlin
// 删除对话时清理图片
fun deleteConversation(id: String) {
    // 1. 删除 XML 记录
    conversations.removeIf { it.id == id }
    saveAll(conversations)
    
    // 2. 清理图片文件
    ImageStore.getInstance(project).cleanupConversationImages(id)
}
```

## 相关文档

- [CONVERSATION_STORE.md](CONVERSATION_STORE.md) - 对话存储
- [../architecture/DATA_FLOW.md](../architecture/DATA_FLOW.md) - 图片处理流程
- [../models/CONTENT_PART.md](../models/CONTENT_PART.md) - 多模态内容
