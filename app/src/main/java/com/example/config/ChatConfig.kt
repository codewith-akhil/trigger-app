package com.example.config

object ChatConfig {
    // File size limits — enforced BOTH client-side AND server-side (edge function).
    // Server-side is authoritative; client-side is for instant UX feedback.
    const val MAX_IMAGE_SIZE_MB = 50L       // 50 MB per image
    const val MAX_VIDEO_SIZE_MB = 250L      // 250 MB per video
    const val MAX_AUDIO_SIZE_MB = 55L       // 55 MB per audio/voice note
    const val MAX_DOCUMENT_SIZE_MB = 55L    // 55 MB per document

    const val MAX_IMAGE_SIZE = MAX_IMAGE_SIZE_MB * 1024 * 1024
    const val MAX_VIDEO_SIZE = MAX_VIDEO_SIZE_MB * 1024 * 1024
    const val MAX_AUDIO_SIZE = MAX_AUDIO_SIZE_MB * 1024 * 1024
    const val MAX_DOCUMENT_SIZE = MAX_DOCUMENT_SIZE_MB * 1024 * 1024

    // Storage bucket names (match the SQL migration)
    const val BUCKET_CHAT_MEDIA = "chat_media"
    const val BUCKET_VOICE_NOTES = "voice_notes"
    const val BUCKET_DOCUMENTS = "documents"

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
