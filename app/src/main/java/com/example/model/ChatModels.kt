package com.example.model

data class ChatItem(
    val id: String,
    val name: String,
    val avatarRes: Int? = null,
    val initialColor: Long = 0xFF00A884,
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
    val initialChats = listOf(
        ChatItem(
            id = "darling",
            name = "darling",
            avatarRes = com.example.R.drawable.img_darling_avatar,
            lastMessage = "See you soon! ❤️",
            timestamp = "10:42 AM",
            unreadCount = 0,
            isPinned = true,
            hasStatusUpdate = false,
            isGroup = false,
            isOnline = true,
            isReadByMe = true,
            hasCheckmarks = true
        ),
        ChatItem(
            id = "besties",
            name = "Besties",
            avatarRes = com.example.R.drawable.img_besties_avatar,
            initialColor = 0xFF5C6BC0,
            lastMessage = "Sarah: For tn: 👠 or 👟?",
            timestamp = "11:26 AM",
            unreadCount = 0,
            isPinned = true,
            hasStatusUpdate = false,
            isGroup = true
        ),
        ChatItem(
            id = "jonathan",
            name = "Jonathan Miller",
            initialColor = 0xFF43A047,
            lastMessage = "Sticker",
            timestamp = "9:28 AM",
            unreadCount = 4,
            isPinned = false,
            hasStatusUpdate = true,
            isGroup = false
        ),
        ChatItem(
            id = "maya",
            name = "Maya Townsend",
            initialColor = 0xFFE65100,
            lastMessage = "Dinner soon? 🍣🍷",
            timestamp = "8:15 AM",
            unreadCount = 0,
            isPinned = false,
            hasStatusUpdate = false,
            isGroup = false,
            hasCheckmarks = true
        ),
        ChatItem(
            id = "lillian",
            name = "Lillian Evaro",
            initialColor = 0xFF00ACC1,
            lastMessage = "GIF",
            timestamp = "8:03 AM",
            unreadCount = 2,
            isPinned = false,
            hasStatusUpdate = true,
            isGroup = false
        ),
        ChatItem(
            id = "cristiano",
            name = "Cristiano Alvés",
            initialColor = 0xFF546E7A,
            lastMessage = "pls tell me you follow SingleCatClu...",
            timestamp = "Yesterday",
            unreadCount = 0,
            isPinned = false,
            hasStatusUpdate = false,
            isGroup = false,
            hasCheckmarks = true
        ),
        ChatItem(
            id = "hendricks",
            name = "The Hendricks",
            initialColor = 0xFF8D6E63,
            lastMessage = "Mom: 🖼️ How was this 10 yrs a...",
            timestamp = "Yesterday",
            unreadCount = 0,
            isPinned = false,
            hasStatusUpdate = false,
            isGroup = true
        )
    )

    // The exact sequence of messages shown in Chat Box.jfif screenshot
    val defaultDarlingMessages = listOf(
        ChatMessage("m1", "Hey sweetie, are we still having lunch together today?", "10:32 AM", isOutgoing = false),
        ChatMessage("m2", "Yes absolutely! Just wrapping up this task right now.", "10:33 AM", isOutgoing = true),
        ChatMessage("m3", "Yay! Where would you like to go?", "10:33 AM", isOutgoing = false),
        ChatMessage("m4", "That Italian spot around the corner has fresh pasta today.", "10:35 AM", isOutgoing = true),
        ChatMessage("m5", "That sounds perfect! What time should we meet?", "10:36 AM", isOutgoing = false),
        ChatMessage("m6", "Let's do 12:30, that gives us plenty of time.", "10:36 AM", isOutgoing = true),
        ChatMessage("m7", "Should I reserve an outdoor table for us?", "10:38 AM", isOutgoing = false),
        ChatMessage("m8", "Yes please, the weather outside is gorgeous!", "10:39 AM", isOutgoing = true),
        ChatMessage("m9", "Reserved! Can't wait to see you ❤️", "10:41 AM", isOutgoing = false),
        ChatMessage("m10", "Heading out in a few minutes, see you soon! 🥰", "10:42 AM", isOutgoing = true)
    )
}
