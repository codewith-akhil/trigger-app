package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 4,
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
                    .addMigrations(REMOVE_SEEDED_DATA, ADD_PIN_EDIT_SEQ_IDEMPOTENCY_ARCHIVED)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
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
