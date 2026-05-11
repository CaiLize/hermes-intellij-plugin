package com.hermes.intellij.services

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Manages image persistence on disk with optimized storage.
 * Images are saved to {projectDir}/.hermes/images/{conversationId}/
 * 
 * Optimizations:
 * - Content-based hashing (SHA-256) for deduplication without file scanning
 * - Index file for fast lookup
 * - Total size limit per conversation
 * - Lazy image loading support
 */
object ImageStore {
    private val LOG = Logger.getInstance(ImageStore::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    
    private const val IMAGES_DIR = ".hermes"
    private const val INDEX_FILE = "image_index.json"
    private const val MAX_IMAGES_PER_CONVERSATION = 50
    private const val MAX_TOTAL_SIZE_BYTES = 50 * 1024 * 1024L // 50MB per conversation
    
    /**
     * Index entry for a single image.
     */
    @Serializable
    data class ImageIndexEntry(
        val fileName: String,
        val contentHash: String,      // SHA-256 hash of base64 content
        val fileSize: Long,
        val createdAt: Long,
        val mimeType: String
    )
    
    /**
     * Index file structure for a conversation.
     */
    @Serializable
    data class ImageIndex(
        val version: Int = 1,
        val entries: MutableList<ImageIndexEntry> = mutableListOf(),
        var totalSizeBytes: Long = 0L
    )
    
    // In-memory cache of indices (conversationId -> Index)
    private val indexCache = mutableMapOf<String, ImageIndex>()
    
    // Lock for thread-safe index operations
    private val indexLock = Any()
    
    /**
     * Get the images directory for a conversation.
     * Uses project base path with proper fallback.
     */
    private fun getImagesDir(project: Project, conversationId: String): File {
        val projectPath = project.basePath 
            ?: run {
                val wsFile = project.workspaceFile
                if (wsFile != null && wsFile.isValid) {
                    wsFile.path.substringBeforeLast("/")
                } else {
                    System.getProperty("user.home")
                }
            }
        val baseDir = File(projectPath)
        val dir = File(baseDir, "$IMAGES_DIR/images/$conversationId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Get the index file path for a conversation.
     */
    private fun getIndexFile(project: Project, conversationId: String): File {
        return File(getImagesDir(project, conversationId), INDEX_FILE)
    }
    
    /**
     * Load or create the image index for a conversation.
     * Thread-safe with caching.
     */
    private fun loadIndex(project: Project, conversationId: String): ImageIndex {
        synchronized(indexLock) {
            // Check cache first
            indexCache[conversationId]?.let { return it }
            
            val indexFile = getIndexFile(project, conversationId)
            val index = if (indexFile.exists()) {
                try {
                    json.decodeFromString<ImageIndex>(indexFile.readText())
                } catch (e: Exception) {
                    LOG.warn("[ImageStore] Failed to load index, creating new one: ${e.message}")
                    ImageIndex()
                }
            } else {
                ImageIndex()
            }
            
            indexCache[conversationId] = index
            return index
        }
    }
    
    /**
     * Save the image index to disk.
     */
    private fun saveIndex(project: Project, conversationId: String, index: ImageIndex) {
        synchronized(indexLock) {
            val indexFile = getIndexFile(project, conversationId)
            try {
                indexFile.writeText(json.encodeToString(ImageIndex.serializer(), index))
                indexCache[conversationId] = index
            } catch (e: Exception) {
                LOG.warn("[ImageStore] Failed to save index: ${e.message}", e)
            }
        }
    }
    
    /**
     * Compute SHA-256 hash of base64 content for deduplication.
     */
    private fun computeContentHash(base64Content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(base64Content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Extract MIME type from data URL.
     */
    private fun extractMimeType(dataUrl: String): String {
        val header = dataUrl.substringBefore(',')
        return when {
            header.contains("png") -> "image/png"
            header.contains("jpeg") || header.contains("jpg") -> "image/jpeg"
            header.contains("gif") -> "image/gif"
            header.contains("webp") -> "image/webp"
            else -> "image/png"
        }
    }
    
    /**
     * Get file extension from MIME type.
     */
    private fun getExtensionFromMimeType(mimeType: String): String {
        return when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "dat"
        }
    }

    /**
     * Save a base64 image to disk, return the relative path from conversation images dir.
     * Returns null if the data URL cannot be parsed or size limit exceeded.
     * Uses content hash as filename for O(1) deduplication.
     */
    fun saveImage(project: Project, conversationId: String, base64DataUrl: String): String? {
        return try {
            // Parse data:image/png;base64,XXXXX
            val commaIdx = base64DataUrl.indexOf(',')
            if (commaIdx < 0) {
                LOG.warn("[ImageStore] Invalid data URL, no comma found")
                return null
            }
            val base64Content = base64DataUrl.substring(commaIdx + 1)
            
            val mimeType = extractMimeType(base64DataUrl)
            val ext = getExtensionFromMimeType(mimeType)
            val contentHash = computeContentHash(base64Content)
            val fileName = "$contentHash.$ext"
            
            val dir = getImagesDir(project, conversationId)
            val file = File(dir, fileName)
            
            // Check if file already exists (hash-based dedup)
            if (file.exists()) {
                LOG.info("[ImageStore] Image already exists (hash match): $fileName")
                return fileName
            }
            
            // Check limits before saving
            val index = loadIndex(project, conversationId)
            val decodedSize = Base64.getDecoder().decode(base64Content).size.toLong()
            
            if (index.entries.size >= MAX_IMAGES_PER_CONVERSATION) {
                LOG.warn("[ImageStore] Max images limit reached ($MAX_IMAGES_PER_CONVERSATION)")
                return null
            }
            
            if (index.totalSizeBytes + decodedSize > MAX_TOTAL_SIZE_BYTES) {
                LOG.warn("[ImageStore] Total size limit exceeded (${MAX_TOTAL_SIZE_BYTES / 1024 / 1024}MB)")
                return null
            }
            
            // Save the file
            file.writeBytes(Base64.getDecoder().decode(base64Content))
            
            // Update index
            val entry = ImageIndexEntry(
                fileName = fileName,
                contentHash = contentHash,
                fileSize = decodedSize,
                createdAt = System.currentTimeMillis(),
                mimeType = mimeType
            )
            index.entries.add(entry)
            index.totalSizeBytes += decodedSize
            saveIndex(project, conversationId, index)
            
            LOG.info("[ImageStore] Saved image: $fileName ($decodedSize bytes, total: ${index.totalSizeBytes / 1024}KB)")
            fileName
        } catch (e: Exception) {
            LOG.warn("[ImageStore] Failed to save image: ${e.message}", e)
            null
        }
    }

    /**
     * Load a base64 image from disk, restore to full data URL format.
     * Returns null if file not found or cannot be read.
     * Supports lazy loading - only loads when explicitly requested.
     */
    fun loadImage(project: Project, conversationId: String, fileName: String): String? {
        val path = getImagePath(project, conversationId, fileName) ?: return null
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                LOG.warn("[ImageStore] Image not found: $fileName")
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
     * Check if an image exists without loading it (for lazy loading support).
     */
    fun imageExists(project: Project, conversationId: String, fileName: String): Boolean {
        return getImagePath(project, conversationId, fileName) != null
    }
    
    /**
     * Get image metadata without loading the full data.
     * Returns null if image not found in index.
     */
    fun getImageMetadata(project: Project, conversationId: String, fileName: String): ImageIndexEntry? {
        val index = loadIndex(project, conversationId)
        return index.entries.find { it.fileName == fileName }
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
     * Also removes the index file and clears cache.
     */
    fun deleteConversationImages(project: Project, conversationId: String) {
        synchronized(indexLock) {
            try {
                val dir = getImagesDir(project, conversationId)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    LOG.info("[ImageStore] Deleted images for conversation: $conversationId")
                }
                indexCache.remove(conversationId)
            } catch (e: Exception) {
                LOG.warn("[ImageStore] Failed to delete images: ${e.message}", e)
            }
        }
    }
    
    /**
     * Get storage statistics for a conversation.
     */
    fun getStorageStats(project: Project, conversationId: String): StorageStats {
        val index = loadIndex(project, conversationId)
        return StorageStats(
            imageCount = index.entries.size,
            totalSizeBytes = index.totalSizeBytes,
            maxImages = MAX_IMAGES_PER_CONVERSATION,
            maxSizeBytes = MAX_TOTAL_SIZE_BYTES
        )
    }
    
    @Serializable
    data class StorageStats(
        val imageCount: Int,
        val totalSizeBytes: Long,
        val maxImages: Int,
        val maxSizeBytes: Long
    ) {
        val usagePercent: Double get() = (totalSizeBytes.toDouble() / maxSizeBytes) * 100
        val countPercent: Double get() = (imageCount.toDouble() / maxImages) * 100
    }

    /**
     * Save all image contentParts from a message, replacing base64 data URLs with file references.
     * Returns the updated JSON string for the content parts.
     * 
     * Uses content-based hashing (SHA-256) for O(1) deduplication.
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

            result = regex.replace(result) { match ->
                val fullMatch = match.value
                // Only process data:image URLs
                if (!match.groupValues[2].startsWith("data:image/")) return@replace fullMatch
                
                val dataUrl = match.groupValues[2]
                val fileName = saveImage(project, conversationId, dataUrl)
                
                if (fileName != null) {
                    "${match.groupValues[1]}\"hermes-image:$fileName\""
                } else {
                    fullMatch // Keep original if save failed (limit reached, etc.)
                }
            }
            result
        } catch (e: Exception) {
            LOG.warn("[ImageStore] Failed to process message images: ${e.message}")
            contentPartsJson
        }
    }
    
    /**
     * Clear the in-memory index cache.
     * Call this when project is closed.
     */
    fun clearCache() {
        synchronized(indexLock) {
            indexCache.clear()
        }
    }
}
