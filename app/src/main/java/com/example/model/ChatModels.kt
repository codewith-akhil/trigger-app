package com.example.model

data class ChatItem(
    val id: String,
    /** The OTHER user's auth uuid (H4/H5) — passed to the chat route. */
    val peerId: String? = null,
    val name: String,
    val avatarRes: Int? = null,
    /** The peer's profile photo URL (Room peerAvatarUrl) — rendered with
     *  AsyncImage; takes precedence over the legacy local drawable. */
    val avatarUrl: String? = null,
    val initialColor: Long = 0xFFD81B60,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val hasStatusUpdate: Boolean = false,
    val isGroup: Boolean = false,
    val isOnline: Boolean = false,
    val isReadByMe: Boolean = false,
    val hasCheckmarks: Boolean = false
)

data class ChatMessage(
    val id: String,
    val text: String,
    val timestamp: String,
    val isOutgoing: Boolean,
    val isRead: Boolean = true
)

object ChatRepository {
    // Empty by default; populated dynamically from Supabase database & local Room persistence
    val initialChats = emptyList<ChatItem>()

}

