package com.hermes.intellij.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null
)

/**
 * Chat message supporting both plain text and multimodal content (text + images).
 * Uses custom serializer to handle OpenAI's two content formats:
 * - Plain text: {"role": "user", "content": "hello"}
 * - Multimodal: {"role": "user", "content": [{"type":"text","text":"..."}, {"type":"image_url","image_url":{"url":"..."}}]}
 * Also supports file_attachments for persisting file context metadata (not sent to API).
 */
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,
    val content: String,
    val contentParts: List<ContentPart>? = null,
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
 * Custom serializer for ChatMessage that outputs:
 * - "content": "string" when contentParts is null
 * - "content": [...] when contentParts is present
 * - "file_attachments": [...] when fileAttachments is present (for persistence only)
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

        return ChatMessage(role, text, contentParts, fileAttachments)
    }
}
