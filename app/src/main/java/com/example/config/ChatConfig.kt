package com.example.config

object ChatConfig {
    const val MAX_IMAGE_SIZE_MB = 16L
    const val MAX_VIDEO_SIZE_MB = 64L
    const val MAX_AUDIO_SIZE_MB = 16L
    const val MAX_DOCUMENT_SIZE_MB = 100L

    const val MAX_IMAGE_SIZE = MAX_IMAGE_SIZE_MB * 1024 * 1024
    const val MAX_VIDEO_SIZE = MAX_VIDEO_SIZE_MB * 1024 * 1024
    const val MAX_AUDIO_SIZE = MAX_AUDIO_SIZE_MB * 1024 * 1024
    const val MAX_DOCUMENT_SIZE = MAX_DOCUMENT_SIZE_MB * 1024 * 1024

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun validateFileSize(bytes: Long, maxBytes: Long, typeName: String): Pair<Boolean, String?> {
        return if (bytes > maxBytes) {
            val maxMb = maxBytes / (1024 * 1024)
            Pair(false, "File too large. Maximum allowed size for $typeName is ${maxMb} MB.")
        } else {
            Pair(true, null)
        }
    }
}
