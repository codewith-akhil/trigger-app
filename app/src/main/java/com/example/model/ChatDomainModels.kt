package com.example.model

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    LOCATION,
    CONTACT,
    CALL_LOG,
    SYSTEM
}

enum class MessageStatus {
    SENDING,   // ◌ small spinning/circle indicator
    SENT,      // ✓ single grey check
    DELIVERED, // ✓✓ double grey checks
    READ,      // ✓✓ double blue checks (#53BDEB)
    FAILED     // ! red error icon with retry
}

enum class PresenceStatus {
    ONLINE,
    OFFLINE,
    TYPING,
    RECORDING_AUDIO,
    CALLING,
    RECONNECTING
}

enum class CallState {
    IDLE,
    CALLING,
    RINGING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ENDED,
    DECLINED,
    MISSED,
    FAILED
}

enum class CallType {
    AUDIO,
    VIDEO
}

enum class DisappearingDuration(val displayName: String, val millis: Long) {
    OFF("Off", 0L),
    HOURS_24("24 hours", 24L * 60 * 60 * 1000),
    DAYS_7("7 days", 7L * 24 * 60 * 60 * 1000),
    DAYS_90("90 days", 90L * 24 * 60 * 60 * 1000)
}

data class MessageReaction(
    val emoji: String,
    val count: Int,
    val userReacted: Boolean = false
)

data class DomainMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val type: MessageType = MessageType.TEXT,
    val text: String = "",
    val mediaUrl: String? = null,
    val mediaThumbnail: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val mediaDurationSec: Int = 0,
    val isViewOnce: Boolean = false,
    val isViewed: Boolean = false,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = true,
    val isStarred: Boolean = false,
    val isPinned: Boolean = false,
    val isDeletedForEveryone: Boolean = false,
    val editedAt: Long? = null,
    val seq: Long = 0L,
    val idempotencyKey: String? = null,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAddress: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val uploadProgress: Int? = null, // 0-100 when uploading
    val uploadSpeed: String? = null,
    val remainingTimeText: String? = null
)

data class DomainConversation(
    val id: String,
    val name: String,
    val avatarRes: Int? = null,
    val initialColor: Long = 0xFF00A884,
    val lastMessage: String = "",
    val timestamp: String = "",
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val hasStatusUpdate: Boolean = false,
    val isGroup: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeenText: String = "online",
    val presence: PresenceStatus = PresenceStatus.ONLINE,
    val disappearingDuration: DisappearingDuration = DisappearingDuration.OFF,
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false
)

data class UploadTask(
    val id: String,
    val messageId: String,
    val conversationId: String,
    val fileName: String,
    val fileType: MessageType,
    val totalBytes: Long,
    val uploadedBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val remainingSeconds: Int = 0,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val filePath: String? = null,
    val mimeType: String? = null,
    val mediaUrl: String? = null,
    val bucket: String? = null
)
