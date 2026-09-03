package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "whatsapp_chat_db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(DatabaseCallback(context.applicationContext))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val database = getInstance(context)
                    seedInitialData(database)
                }
            }
        }

        suspend fun seedInitialData(database: ChatDatabase) {
            val convDao = database.conversationDao()
            val msgDao = database.messageDao()

            val initialConversations = listOf(
                ConversationEntity(
                    id = "darling",
                    name = "darling",
                    avatarRes = R.drawable.img_darling_avatar,
                    initialColor = 0xFF00A884,
                    lastMessage = "Heading out in a few minutes, see you soon! 🥰",
                    timestamp = "10:42 AM",
                    unreadCount = 0,
                    isPinned = true,
                    hasStatusUpdate = false,
                    isGroup = false,
                    isOnline = true,
                    lastSeenText = "online"
                ),
                ConversationEntity(
                    id = "besties",
                    name = "Besties",
                    avatarRes = R.drawable.img_besties_avatar,
                    initialColor = 0xFF5C6BC0,
                    lastMessage = "Sarah: For tn: 👠 or 👟?",
                    timestamp = "11:26 AM",
                    unreadCount = 0,
                    isPinned = true,
                    hasStatusUpdate = false,
                    isGroup = true,
                    isOnline = false,
                    lastSeenText = "today at 11:26 AM"
                ),
                ConversationEntity(
                    id = "jonathan",
                    name = "Jonathan Miller",
                    initialColor = 0xFF43A047,
                    lastMessage = "Sticker",
                    timestamp = "9:28 AM",
                    unreadCount = 4,
                    isPinned = false,
                    hasStatusUpdate = true,
                    isGroup = false,
                    isOnline = false,
                    lastSeenText = "today at 9:30 AM"
                ),
                ConversationEntity(
                    id = "maya",
                    name = "Maya Townsend",
                    initialColor = 0xFFE65100,
                    lastMessage = "Dinner soon? 🍣🍷",
                    timestamp = "8:15 AM",
                    unreadCount = 0,
                    isPinned = false,
                    hasStatusUpdate = false,
                    isGroup = false,
                    isOnline = false,
                    lastSeenText = "today at 8:15 AM"
                ),
                ConversationEntity(
                    id = "lillian",
                    name = "Lillian Evaro",
                    initialColor = 0xFF00ACC1,
                    lastMessage = "GIF",
                    timestamp = "8:03 AM",
                    unreadCount = 2,
                    isPinned = false,
                    hasStatusUpdate = true,
                    isGroup = false,
                    isOnline = false,
                    lastSeenText = "today at 8:04 AM"
                ),
                ConversationEntity(
                    id = "cristiano",
                    name = "Cristiano Alvés",
                    initialColor = 0xFF546E7A,
                    lastMessage = "pls tell me you follow SingleCatClu...",
                    timestamp = "Yesterday",
                    unreadCount = 0,
                    isPinned = false,
                    hasStatusUpdate = false,
                    isGroup = false,
                    isOnline = false,
                    lastSeenText = "yesterday at 7:45 PM"
                ),
                ConversationEntity(
                    id = "hendricks",
                    name = "The Hendricks",
                    initialColor = 0xFF8D6E63,
                    lastMessage = "Mom: 🖼️ How was this 10 yrs a...",
                    timestamp = "Yesterday",
                    unreadCount = 0,
                    isPinned = false,
                    hasStatusUpdate = false,
                    isGroup = true,
                    isOnline = false,
                    lastSeenText = "yesterday at 9:12 PM"
                )
            )

            convDao.insertConversations(initialConversations)

            val baseTime = System.currentTimeMillis() - 15 * 60 * 1000
            val darlingMessages = listOf(
                MessageEntity(
                    id = "m1",
                    conversationId = "darling",
                    senderId = "darling",
                    senderName = "darling",
                    type = "TEXT",
                    text = "Hey sweetie, are we still having lunch together today?",
                    status = "READ",
                    timestamp = "10:32 AM",
                    timestampMillis = baseTime,
                    isOutgoing = false
                ),
                MessageEntity(
                    id = "m2",
                    conversationId = "darling",
                    senderId = "me",
                    senderName = "You",
                    type = "TEXT",
                    text = "Yes absolutely! Just wrapping up this task right now.",
                    status = "READ",
                    timestamp = "10:33 AM",
                    timestampMillis = baseTime + 60 * 1000,
                    isOutgoing = true
                ),
                MessageEntity(
                    id = "m3",
                    conversationId = "darling",
                    senderId = "darling",
                    senderName = "darling",
                    type = "TEXT",
                    text = "Yay! Where would you like to go?",
                    status = "READ",
                    timestamp = "10:33 AM",
                    timestampMillis = baseTime + 90 * 1000,
                    isOutgoing = false
                ),
                MessageEntity(
                    id = "m4",
                    conversationId = "darling",
                    senderId = "me",
                    senderName = "You",
                    type = "TEXT",
                    text = "That Italian spot around the corner has fresh pasta today.",
                    status = "READ",
                    timestamp = "10:35 AM",
                    timestampMillis = baseTime + 180 * 1000,
                    isOutgoing = true
                ),
                MessageEntity(
                    id = "m5",
                    conversationId = "darling",
                    senderId = "darling",
                    senderName = "darling",
                    type = "TEXT",
                    text = "That sounds perfect! What time should we meet?",
                    status = "READ",
                    timestamp = "10:36 AM",
                    timestampMillis = baseTime + 240 * 1000,
                    isOutgoing = false
                ),
                MessageEntity(
                    id = "m6",
                    conversationId = "darling",
                    senderId = "me",
                    senderName = "You",
                    type = "TEXT",
                    text = "Let's do 12:30, that gives us plenty of time.",
                    status = "READ",
                    timestamp = "10:36 AM",
                    timestampMillis = baseTime + 260 * 1000,
                    isOutgoing = true
                ),
                MessageEntity(
                    id = "m7",
                    conversationId = "darling",
                    senderId = "darling",
                    senderName = "darling",
                    type = "TEXT",
                    text = "Should I reserve an outdoor table for us?",
                    status = "READ",
                    timestamp = "10:38 AM",
                    timestampMillis = baseTime + 360 * 1000,
                    isOutgoing = false
                ),
                MessageEntity(
                    id = "m8",
                    conversationId = "darling",
                    senderId = "me",
                    senderName = "You",
                    type = "TEXT",
                    text = "Yes please, the weather outside is gorgeous!",
                    status = "READ",
                    timestamp = "10:39 AM",
                    timestampMillis = baseTime + 420 * 1000,
                    isOutgoing = true
                ),
                MessageEntity(
                    id = "m9",
                    conversationId = "darling",
                    senderId = "darling",
                    senderName = "darling",
                    type = "TEXT",
                    text = "Reserved! Can't wait to see you ❤️",
                    status = "READ",
                    timestamp = "10:41 AM",
                    timestampMillis = baseTime + 540 * 1000,
                    isOutgoing = false,
                    reactionsRaw = "❤️:1:false"
                ),
                MessageEntity(
                    id = "m10",
                    conversationId = "darling",
                    senderId = "me",
                    senderName = "You",
                    type = "TEXT",
                    text = "Heading out in a few minutes, see you soon! 🥰",
                    status = "READ",
                    timestamp = "10:42 AM",
                    timestampMillis = baseTime + 600 * 1000,
                    isOutgoing = true
                )
            )

            msgDao.insertMessages(darlingMessages)
        }
    }
}
