# 对话持久化存储

## 存储位置

```
{project}/.idea/hermes-conversations.xml
```

## XML 格式

```xml
<?xml version="1.0" encoding="UTF-8"?>
<application>
  <component name="ConversationStore">
    <conversations>
      <conversation id="uuid-123" title="代码优化建议" active="true">
        <messages>
          <message role="user">
            <content>帮我优化这段代码</content>
            <file_attachments>
              <attachment filePath="/src/main.kt" lineRange="10-50" language="kotlin"/>
            </file_attachments>
          </message>
          <message role="assistant">
            <content>当然可以！以下是优化建议...</content>
          </message>
        </messages>
        <hermes_session_id>session-456</hermes_session_id>
      </conversation>
    </conversations>
    <last_active_conversation_id>uuid-123</last_active_conversation_id>
  </component>
</application>
```

## 数据结构

### Conversation

```kotlin
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val hermesSessionId: String? = null
)
```

### ConversationSummary

```kotlin
data class ConversationSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAt: Long
)
```

## ConversationStore 实现

```kotlin
class ConversationStore(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): ConversationStore {
            return project.getService(ConversationStore::class.java)
        }
    }
    
    private val storagePath: Path
        get() = project.basePath?.let { Paths.get(it, ".idea/hermes-conversations.xml") }
            ?: throw IllegalStateException("No project base path")
    
    fun saveConversation(conversation: Conversation) {
        val xml = buildXml(conversation)
        Files.writeString(storagePath, xml, StandardOpenOption.CREATE)
    }
    
    fun loadConversation(id: String): Conversation? {
        if (!Files.exists(storagePath)) return null
        
        val xml = Files.readString(storagePath)
        return parseXml(xml, id)
    }
    
    fun listConversations(): List<ConversationSummary> {
        if (!Files.exists(storagePath)) return emptyList()
        
        val xml = Files.readString(storagePath)
        return parseSummaries(xml)
    }
    
    fun deleteConversation(id: String) {
        val conversations = listConversations()
            .filter { it.id != id }
            .map { loadConversation(it.id) }
            .filterNotNull()
        
        saveAll(conversations)
    }
}
```

## XML 构建与解析

### 构建 XML

```kotlin
private fun buildXml(conversation: Conversation): String {
    return buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<application>""")
        appendLine("""  <component name="ConversationStore">""")
        appendLine("""    <conversations>""")
        appendLine("""      <conversation id="${conversation.id}" title="${conversation.title}" active="true">""")
        appendLine("""        <messages>""")
        
        for (message in conversation.messages) {
            appendLine("""          <message role="${message.role}">""")
            appendLine("""            <content><![CDATA[${escapeXml(message.content)}]]></content>""")
            
            if (message.fileAttachments != null) {
                appendLine("""            <file_attachments>""")
                for (attachment in message.fileAttachments) {
                    appendLine("""              <attachment filePath="${attachment.filePath}" lineRange="${attachment.lineRange}" language="${attachment.language}"/>""")
                }
                appendLine("""            </file_attachments>""")
            }
            
            appendLine("""          </message>""")
        }
        
        appendLine("""        </messages>""")
        
        if (conversation.hermesSessionId != null) {
            appendLine("""        <hermes_session_id>${conversation.hermesSessionId}</hermes_session_id>""")
        }
        
        appendLine("""      </conversation>""")
        appendLine("""    </conversations>""")
        appendLine("""    <last_active_conversation_id>${conversation.id}</last_active_conversation_id>""")
        appendLine("""  </component>""")
        appendLine("""</application>""")
    }
}

private fun escapeXml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
```

### 解析 XML

```kotlin
private fun parseXml(xml: String, id: String): Conversation? {
    val document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(InputSource(xml.byteInputStream()))
    
    val conversationElement = document.getElementsByTagName("conversation")
        .asSequence()
        .find { it.getAttribute("id") == id }
        ?: return null
    
    val title = conversationElement.getAttribute("title")
    val createdAt = conversationElement.getAttribute("createdAt").toLongOrNull() ?: System.currentTimeMillis()
    val hermesSessionId = conversationElement.getElementsByTagName("hermes_session_id")
        .item(0)
        ?.textContent
    
    val messages = parseMessages(conversationElement)
    
    return Conversation(
        id = id,
        title = title,
        messages = messages,
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis(),
        hermesSessionId = hermesSessionId
    )
}
```

## 图片引用解析

加载对话时，需要将 `hermes-image:xxx` 引用解析为实际的 base64 数据：

```kotlin
fun loadConversation(id: String): Conversation? {
    val conversation = readFromXml(id) ?: return null
    
    // 解析图片引用
    val resolvedMessages = conversation.messages.map { message ->
        val resolvedContent = message.content.replace(Regex("hermes-image:(\\S+)")) { match ->
            val fileName = match.groupValues[1]
            ImageStore.getInstance(project).loadImage(id, fileName) ?: match.value
        }
        message.copy(content = resolvedContent)
    }
    
    return conversation.copy(messages = resolvedMessages)
}
```

## 相关文档

- [IMAGE_STORE.md](IMAGE_STORE.md) - 图片存储
- [MIGRATION.md](MIGRATION.md) - 数据迁移
- [../modules/SERVICES.md](../modules/SERVICES.md) - ConversationStore 服务
