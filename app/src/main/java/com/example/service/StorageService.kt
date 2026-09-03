package com.example.service

import com.example.config.ChatConfig
import com.example.model.MessageType
import java.io.File

interface StorageService {
    fun validateUpload(fileSize: Long, type: MessageType): Pair<Boolean, String?>
    suspend fun saveCachedMedia(fileName: String, data: ByteArray): File
    fun getLocalCachePath(fileName: String): String
}

class StorageServiceImpl(private val cacheDir: File) : StorageService {
    override fun validateUpload(fileSize: Long, type: MessageType): Pair<Boolean, String?> {
        val (limit, name) = when (type) {
            MessageType.IMAGE -> ChatConfig.MAX_IMAGE_SIZE to "image"
            MessageType.VIDEO -> ChatConfig.MAX_VIDEO_SIZE to "video"
            MessageType.AUDIO -> ChatConfig.MAX_AUDIO_SIZE to "audio"
            MessageType.DOCUMENT -> ChatConfig.MAX_DOCUMENT_SIZE to "document"
            else -> Long.MAX_VALUE to "file"
        }
        return ChatConfig.validateFileSize(fileSize, limit, name)
    }

    override suspend fun saveCachedMedia(fileName: String, data: ByteArray): File {
        val file = File(cacheDir, fileName)
        file.writeBytes(data)
        return file
    }

    override fun getLocalCachePath(fileName: String): String {
        return File(cacheDir, fileName).absolutePath
    }
}
