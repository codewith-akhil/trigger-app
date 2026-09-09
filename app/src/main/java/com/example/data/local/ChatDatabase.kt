package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 11,
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
                    .addMigrations(
                        REMOVE_SEEDED_DATA,
                        ADD_PIN_EDIT_SEQ_IDEMPOTENCY_ARCHIVED,
                        ADD_LOCATION_LIVE_FIELDS,
                        UNIFY_CONVERSATION_IDS_AND_MEDIA_PATHS,
                        ADD_CALL_LOG_FIELDS,
                        ADD_CONVERSATION_LAST_ACTIVITY,
                        ADD_CONVERSATION_DISAPPEARING_TIMESTAMP,
                        ADD_CONVERSATION_REQUEST_STATUS_AND_AVATAR,
                        ADD_MESSAGES_PAGINATION_INDEX
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Migration 10 → 11 (Task 25): composite pagination index on messages.
        // Serves the newest-first window/page cursor queries — without it the
        // whole conversation is fetched + sorted on every window emission.
        // The name MUST match the @Entity Index declaration (Room validates
        // the expected schema on every open).
        private val ADD_MESSAGES_PAGINATION_INDEX = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conv_ts_seq ON messages(conversationId, timestampMillis, seq)")
            }
        }

        // Migration 9 → 10: chat-list dedupe + completeness columns.
        //  - conversations.requestStatus — mirrors conversations.request_status
        //    ("pending"/"accepted"/"declined"/"blocked"); declined/blocked
        //    mirrors are dropped from the local list on every pull.
        //  - conversations.peerAvatarUrl — the OTHER user's public avatar URL
        //    (conversations.peer_avatar_url, profile-resolved on mirrored rows).
        // Column defaults match the ConversationEntity Kotlin defaults; existing
        // rows are the accepted, avatar-less legacy state.
        private val ADD_CONVERSATION_REQUEST_STATUS_AND_AVATAR = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN requestStatus TEXT NOT NULL DEFAULT 'accepted'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN peerAvatarUrl TEXT")
            }
        }

        // Migration 8 → 9: conversations.disappearingUpdatedAtMillis — auto
        // delete activation time. The in-chat notice and the local purge both
        // key off it; messages created before activation are never deleted.
        private val ADD_CONVERSATION_DISAPPEARING_TIMESTAMP = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN disappearingUpdatedAtMillis INTEGER")
            }
        }

        // Migration 7 → 8: conversations.lastActivityMillis — recency ordering
        // for the chat list (the list previously ordered by UUID, so new
        // messages never bubbled a conversation to the top).
        private val ADD_CONVERSATION_LAST_ACTIVITY = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastActivityMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 6 → 7: call history persistence — messages.callType /
        // callDurationSec mirror the server's messages.call_type /
        // call_duration_sec columns so audio/video call history with duration
        // is stored both locally and in Supabase.
        private val ADD_CALL_LOG_FIELDS = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN callType TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN callDurationSec INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 5 → 6 (H4/H5 + re-sign groundwork):
        //  - conversations.peerId — the OTHER user's auth UUID, so a chat opened
        //    by conversation UUID can still resolve presence/calls (peer-keyed).
        //  - messages.mediaBucket/mediaPath — storage coordinates so expired
        //    signed URLs can be rebuilt from scratch on any device.
        // No data rewrite happens here: legacy peer-keyed message rows are
        // re-keyed at RUNTIME (MessageServiceImpl re-key on send/sync responses)
        // because the peer→conversation mapping only exists on the server.
        private val UNIFY_CONVERSATION_IDS_AND_MEDIA_PATHS = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN peerId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaBucket TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaPath TEXT")
            }
        }

        // Migration 4 → 5: live-location metadata on messages
        // (locationLiveMinutes = share duration, locationComment = user comment)
        private val ADD_LOCATION_LIVE_FIELDS = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN locationLiveMinutes INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN locationComment TEXT")
            }
        }

        private val REMOVE_SEEDED_DATA = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM messages WHERE id IN ('m1','m2','m3','m4','m5','m6','m7','m8','m9','m10')")
                db.execSQL("DELETE FROM conversations WHERE id IN ('darling','besties','jonathan','maya','lillian','cristiano','hendricks')")
            }
        }

        // Migration 3 → 4: add new columns for the chat gap fix
        // (isPinned, editedAt, seq, idempotencyKey on messages; isArchived on conversations)
        private val ADD_PIN_EDIT_SEQ_IDEMPOTENCY_ARCHIVED = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN idempotencyKey TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_seq ON messages(seq)")
            }
        }
    }
}
