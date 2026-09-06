package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DisappearingDuration
import com.example.model.DomainConversation
import com.example.model.PresenceStatus

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val avatarRes: Int? = null,
    val initialColor: Long = 0xFF00A884,
    val lastMessage: String = "",
    val timestamp: String = "",
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val hasStatusUpdate: Boolean = false,
    val isGroup: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeenText: String = "online",
    val disappearingDuration: String = "OFF",
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false,
    val isArchived: Boolean = false,
    /** The OTHER user's auth UUID (1:1 chats). Refreshed from sync-conversations. */
    val peerId: String? = null
) {
    fun toDomain(): DomainConversation {
        return DomainConversation(
            id = id,
            peerId = peerId,
            name = name,
            avatarRes = avatarRes,
            initialColor = initialColor,
            lastMessage = lastMessage,
            timestamp = timestamp,
            unreadCount = unreadCount,
            isPinned = isPinned,
            hasStatusUpdate = hasStatusUpdate,
            isGroup = isGroup,
            isOnline = isOnline,
            lastSeenText = lastSeenText,
            presence = if (isOnline) PresenceStatus.ONLINE else PresenceStatus.OFFLINE,
            disappearingDuration = try {
                DisappearingDuration.valueOf(disappearingDuration)
            } catch (e: Exception) {
                DisappearingDuration.OFF
            },
            isMuted = isMuted,
            isBlocked = isBlocked,
            isArchived = isArchived
        )
    }

    companion object {
        fun fromDomain(d: DomainConversation): ConversationEntity {
            return ConversationEntity(
                id = d.id,
                peerId = d.peerId,
                name = d.name,
                avatarRes = d.avatarRes,
                initialColor = d.initialColor,
                lastMessage = d.lastMessage,
                timestamp = d.timestamp,
                unreadCount = d.unreadCount,
                isPinned = d.isPinned,
                hasStatusUpdate = d.hasStatusUpdate,
                isGroup = d.isGroup,
                isOnline = d.isOnline,
                lastSeenText = d.lastSeenText,
                disappearingDuration = d.disappearingDuration.name,
                isMuted = d.isMuted,
                isBlocked = d.isBlocked,
                isArchived = d.isArchived
            )
        }
    }
}
