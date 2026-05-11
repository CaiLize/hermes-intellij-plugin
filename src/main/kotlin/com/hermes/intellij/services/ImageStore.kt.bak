package com.hermes.intellij.services

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.Base64

/**
 * Manages image persistence on disk.
 * Images are saved to {projectDir}/.hermes/images/{conversationId}/
 * Each image is named by a short UUID with .dat extension.
 */
object ImageStore {
    private val LOG = Logger.getInstance(ImageStore::class.java)
    private const val IMAGES_DIR = ".hermes"
    private const val MAX_IMAGES_PER_CONVERSATION = 50

    /**
     * Get the images directory for a conversation.
     */
    private fun getImagesDir(project: Project, conversationId: String): File {
        val baseDir = File(project.basePath ?: project.workspaceFile?.path?.substringBeforeLast("/") ?: ".")
        val dir = File(baseDir, "$IMAGES_DIR/images/$conversationId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Save a base64 image to disk, return the relative path from conversation images dir.
     * Returns null if the data URL cannot be parsed.
     */
    fun saveImage(project: Project, conversationId: String, base64DataUrl: String): String? {
        return try {
            // Parse data:image/png;base64,XXXXX
            val commaIdx = base64DataUrl.indexOf(',')
            if (commaIdx < 0) {
                LOG.warn("[ImageStore] Invalid data URL, no comma found")
                return null
            }
            val header = base64DataUrl.substring(0, commaIdx) // e.g. "data:image/png;base64"
            val base64Content = base64DataUrl.substring(commaIdx + 1)

            // Generate unique filename
            val ext = when {
                header.contains("png") -> "png"
                header.contains("jpeg") || header.contains("jpg") -> "jpg"
                header.contains("gif") -> "gif"
                header.contains("webp") -> "webp"
                else -> "dat"
            }
            val fileName = "${UUID.randomUUID().toString().take(8)}.$ext"

            val dir = getImagesDir(project, conversationId)
            val file = File(dir, fileName)
            file.writeBytes(Base64.getDecoder().decode(base64Content))

            LOG.info("[ImageStore] Saved image: ${file.name} (${file.length()} bytes)")
            fileName
        } catch (e: Exception) {
            LOG.warn("[ImageStore] Failed to save image: ${e.message}", e)
            null
        }
    }

    /**
     * Load a base64 image from disk, restore to full data URL format.
     * Returns null if file not found or cannot be read.
     */
    fun loadImage(project: Project, conversationId: String, fileName: String): String? {
        val path = getImagePath(project, conversationId, fileName) ?: return null
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                LOG.warn("[ImageStore] Image not found: ${file.name}")
                return null
            }

            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/png"
            }

            val bytes = file.readBytes()
            val base64 = Base64.getEncoder().encodeToString(bytes)
            "data:$mimeType;base64,$base64"
        } catch (e: Exception) {
            LOG.warn("[ImageStore] Failed to load image: ${e.message}", e)
            null
        }
    }

    /**
     * Get the absolute file path for an image.
     * Returns null if file not found.
     */
    fun getImagePath(project: Project, conversationId: String, fileName: String): String? {
        val dir = getImagesDir(project, conversationId)
        val file = File(dir, fileName)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Delete all images for a conversation.
     */
    fun deleteConversationImages(project: Project, conversationId: String) {
        try {
            val dir = getImagesDir(project, conversationId)
            if (dir.exists()) {
                dir.deleteRecursively()
                LOG.info("[ImageStore] Deleted images for conversation: $conversationId")
            }
        } catch (e: Exception) {
            LOG.warn("[ImageStore] Failed to delete images: ${e.message}", e)
        }
    }

    /**
     * Save all image contentParts from a message, replacing base64 data URLs with file references.
     * Returns the updated JSON string for the content parts.
     * 
     * Uses content-based hashing to avoid duplicate saves: if an image with the same
     * base64 content already exists in the conversation directory, reuses the existing file.
     */
    fun processMessageImages(
        project: Project,
        conversationId: String,
        contentPartsJson: String
    ): String {
        // Find all data:image URLs and replace with file references
        // Format: {"type":"image_url","image_url":{"url":"data:image/...;base64,..."}}
        return try {
            val regex = Regex("""("url"\s*:\s*)"([^"]+)"""")
            var result = contentPartsJson
            var matchCount = 0

            // Cache to track already-processed images in this call (base64 -> fileName)
            val processedImages = mutableMapOf<String, String?>()

            result = regex.replace(result) { match ->
                val fullMatch = match.value
                // Only process data:image URLs
                if (!match.groupValues[2].startsWith("data:image/")) return@replace fullMatch
                
                if (matchCount >= MAX_IMAGES_PER_CONVERSATION) return@replace fullMatch
                matchCount++
                val dataUrl = match.groupValues[2]
                
                // Check if we already processed this exact image in this call
                val fileName = processedImages.getOrPut(dataUrl) {
                    // Find existing file with same content, or save new one
                    findOrCreateImageFile(project, conversationId, dataUrl)
                }
                
                if (fileName != null) {
                    "${match.groupValues[1]}\"hermes-image:$fileName\""
                } else {
                    fullMatch
                }
            }
            result
        } catch (e: Exception) {
            LOG.warn("[ImageStore] Failed to process message images: ${e.message}")
            contentPartsJson
        }
    }

    /**
     * Find an existing image file with matching content, or save a new one.
     * Uses base64 hash to detect duplicates and avoid creating redundant files.
     */
    private fun findOrCreateImageFile(
        project: Project,
        conversationId: String,
        base64DataUrl: String
    ): String? {
        // Extract base64 content for comparison (skip data URL prefix)
        val commaIdx = base64DataUrl.indexOf(',')
        if (commaIdx < 0) return null
        
        val base64Content = base64DataUrl.substring(commaIdx + 1)
        
        // Check existing files in the conversation directory
        val imagesDir = getImagesDir(project, conversationId)
        val existingFile = imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "png", "gif", "webp") }
            ?.find { file ->
                try {
                    // Read file and compare base64 content
                    val fileBase64 = Base64.getEncoder().encodeToString(file.readBytes())
                    fileBase64 == base64Content
                } catch (e: Exception) {
                    false
                }
            }
        
        // Return existing filename if found (avoid duplicate save)
        if (existingFile != null) {
            LOG.info("[ImageStore] Reusing existing image: ${existingFile.name}")
            return existingFile.name
        }
        
        // Save new image
        return saveImage(project, conversationId, base64DataUrl)
    }
}
