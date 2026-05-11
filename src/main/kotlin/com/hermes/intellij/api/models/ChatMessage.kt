package com.hermes.intellij.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Tool call record for persistence.
 * Stores the tool name and status for displaying in historical messages.
 */
@Serializable
data class ToolCallRecord(
    val name: String,
    val status: String = "completed",  // "running" or "completed"
    val arguments: String? = null,
    val completed: Boolean = true
)

/**
 * Chat message supporting both plain text and multimodal content (text + images).
 * Uses custom serializer to handle OpenAI's two content formats:
 * - Plain text: {"role": "user", "content": "hello"}
 * - Multimodal: {"role": "user", "content": [{"type":"text","text":"..."}, {"type":"image_url","image_url":{"url":"..."}}]}
 * Also supports file_attachments for persisting file context metadata (not sent to API).
 * And tool_calls for persisting tool call history in assistant messages.
 * And segments for persisting ordered content segments (text + tool calls interleaved).
 */
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,
    val content: String,
    val contentParts: List<ContentPart>? = null,
    val fileAttachments: List<FileAttachmentData>? = null,
    val toolCalls: List<ToolCallRecord>? = null,
    val segments: List<MessageSegment>? = null  // NEW: Ordered content segments for perfect interleaving
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
        fun assistant(content: String, toolCalls: List<ToolCallRecord>) = 
            ChatMessage("assistant", content, null, null, toolCalls)
        fun userWithImages(text: String, imageBase64Urls: List<String>): ChatMessage {
            val parts = mutableListOf<ContentPart>()
            parts.add(ContentPart.Text(text))
            for (url in imageBase64Urls) {
                parts.add(ContentPart.ImageUrl(ImageUrlData(url)))
            }
            return ChatMessage("user", text, parts)
        }
        fun userWithAttachments(
            text: String,
            imageBase64Urls: List<String> = emptyList(),
            files: List<FileAttachmentData> = emptyList()
        ): ChatMessage {
            val parts = if (imageBase64Urls.isNotEmpty()) {
                val p = mutableListOf<ContentPart>()
                p.add(ContentPart.Text(text))
                for (url in imageBase64Urls) {
                    p.add(ContentPart.ImageUrl(ImageUrlData(url)))
                }
                p
            } else null
            val fileAtts = if (files.isNotEmpty()) files else null
            return ChatMessage("user", text, parts, fileAtts)
        }
    }
}

/**
 * Serializable file attachment metadata for persistence.
 * Not sent to API — only used for UI rendering of historical messages.
 */
@Serializable
data class FileAttachmentData(
    val filePath: String,
    val lineRange: String? = null,
    val language: String = ""
)

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

/**
 * Ordered message content segment for perfect interleaving of text, tool calls, images, and files.
 * Used for persistence to maintain chronological order of all content types.
 */
@Serializable
sealed class MessageSegment {
    @Serializable
    @SerialName("text")
    data class Text(val content: String) : MessageSegment()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val name: String,
        val status: String = "completed",
        val arguments: String? = null,
        val completed: Boolean = true
    ) : MessageSegment()
    
    /**
     * Image segment for inline image references in chronological order.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val base64Data: String,
        val mimeType: String = "image/png"
    ) : MessageSegment()
    
    /**
     * File segment for inline file attachment references in chronological order.
     */
    @Serializable
    @SerialName("file")
    data class File(
        val filePath: String,
        val fileName: String,
        val lineRange: String? = null,
        val language: String = ""
    ) : MessageSegment()
}

/**
 * Custom serializer for ChatMessage that outputs:
 * - "content": "string" when contentParts is null
 * - "content": [...] when contentParts is present
 * - "file_attachments": [...] when fileAttachments is present (for persistence only)
 * - "tool_calls": [...] when toolCalls is present (for persistence only)
 * - "segments": [...] when segments is present (for perfect interleaving)
 */
object ChatMessageSerializer : kotlinx.serialization.KSerializer<ChatMessage> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("ChatMessage") {
        element("role", kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("role", kotlinx.serialization.descriptors.PrimitiveKind.STRING))
        element("content", kotlinx.serialization.json.JsonElement.serializer().descriptor)
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ChatMessage) {
        val jsonEncoder = encoder as kotlinx.serialization.json.JsonEncoder
        val jsonObject = buildJsonObject {
            put("role", JsonPrimitive(value.role))
            if (value.contentParts != null) {
                put("content", jsonEncoder.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ContentPart.serializer()),
                    value.contentParts
                ))
            } else {
                put("content", JsonPrimitive(value.content))
            }
            if (value.fileAttachments != null) {
                put("file_attachments", jsonEncoder.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(FileAttachmentData.serializer()),
                    value.fileAttachments
                ))
            }
            if (value.toolCalls != null) {
                put("tool_calls", jsonEncoder.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ToolCallRecord.serializer()),
                    value.toolCalls
                ))
            }
            if (value.segments != null) {
                put("segments", jsonEncoder.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(MessageSegment.serializer()),
                    value.segments
                ))
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ChatMessage {
        val jsonDecoder = decoder as kotlinx.serialization.json.JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val role = jsonObject["role"]!!.jsonPrimitive.content
        val contentElement = jsonObject["content"]!!
        val contentParts: List<ContentPart>? = if (contentElement is JsonArray) {
            jsonDecoder.json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ContentPart.serializer()),
                contentElement
            )
        } else null

        val text = contentParts?.filterIsInstance<ContentPart.Text>()?.firstOrNull()?.text
            ?: contentElement.jsonPrimitive.content

        val fileAttachments: List<FileAttachmentData>? = jsonObject["file_attachments"]?.let { faElement ->
            jsonDecoder.json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(FileAttachmentData.serializer()),
                faElement
            )
        }

        val toolCalls: List<ToolCallRecord>? = jsonObject["tool_calls"]?.let { tcElement ->
            jsonDecoder.json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ToolCallRecord.serializer()),
                tcElement
            )
        }

        val segments: List<MessageSegment>? = jsonObject["segments"]?.let { segElement ->
            jsonDecoder.json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(MessageSegment.serializer()),
                segElement
            )
        }

        return ChatMessage(role, text, contentParts, fileAttachments, toolCalls, segments)
    }
}
