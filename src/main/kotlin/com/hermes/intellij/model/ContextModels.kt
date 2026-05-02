package com.hermes.intellij.model

import javax.swing.ImageIcon

data class CodeContext(
    val filePath: String,
    val lineRange: String?,
    val content: String,
    val language: String
)

data class ImageContext(
    val base64Data: String,
    val thumbnail: ImageIcon
)

/**
 * File attachment for UI display with clickable link to open source file.
 */
data class FileAttachment(
    val filePath: String,
    val lineRange: String?,
    val language: String,
    val content: String = ""
) {
    val fileName: String get() = filePath.substringAfterLast("/").substringAfterLast("\\")
}

/**
 * Image attachment for UI display.
 */
data class ImageAttachment(
    val base64Data: String,
    val thumbnail: ImageIcon
)

/**
 * Complete message content for UI display.
 * Separates text, images, and files for structured rendering.
 */
data class MessageContent(
    val text: String,
    val images: List<ImageAttachment> = emptyList(),
    val files: List<FileAttachment> = emptyList()
)
