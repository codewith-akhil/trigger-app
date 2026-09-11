package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.di.AppServiceContainer
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 12,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        // Phase 2: renamed from the legacy plaintext chat database (only
        // TriggerDbMigrator knows the old file name) and encrypted at rest
        // with SQLCipher. Same schema, same version.
        private const val DB_NAME = "trigger_msgstore.db"

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    DB_NAME
                )
                    // Phase 2: every open of the store is gated behind the DI
                    // pipeline (passphrase unwrap + one-time rename/encrypt
                    // migration) — see GatedOpenHelper below. This covers ALL
                    // code paths, including direct-DAO users like
                    // DashboardViewModel.
                    .openHelperFactory(gatedFactory())
                    .addMigrations(
                        REMOVE_SEEDED_DATA,
                        ADD_PIN_EDIT_SEQ_IDEMPOTENCY_ARCHIVED,
                        ADD_LOCATION_LIVE_FIELDS,
                        UNIFY_CONVERSATION_IDS_AND_MEDIA_PATHS,
                        ADD_CALL_LOG_FIELDS,
                        ADD_CONVERSATION_LAST_ACTIVITY,
                        ADD_CONVERSATION_DISAPPEARING_TIMESTAMP,
                        ADD_CONVERSATION_REQUEST_STATUS_AND_AVATAR,
                        ADD_MESSAGES_PAGINATION_INDEX,
                        ADD_MESSAGES_LOCAL_MEDIA_PATH
                    )
                    // Phase 3: fallbackToDestructiveMigration is GONE — and
                    // deliberately so. The schema store is SQLCipher-encrypted
                    // and holds the user's ENTIRE message history; a missing
                    // migration must FAIL LOUDLY (Room throws
                    // IllegalStateException "A migration from X to Y was
                    // required but not found") instead of silently re-creating
                    // an empty encrypted store. Verified: the chain above is
                    // contiguous 2→12 (this DB has shipped starting at version
                    // 2 — no 1→2 edge exists — and every step is registered),
                    // so every upgradeable install finds its migration. Any
                    // future schema change MUST append its Migration here.
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Factory that defers the real SQLCipher [SupportOpenHelperFactory]
         * until the DI gate releases. Room invokes the factory while the
         * database object is being built (synchronously, on whatever thread
         * calls [getInstance]) — creating the helper must therefore never
         * block; the gate is awaited at OPEN time instead, when the file is
         * actually touched on a Room executor thread. That ordering also
         * avoids a deadlock: the gate is completed only after the migration,
         * which runs without any Room connection.
         */
        private fun gatedFactory() = SupportSQLiteOpenHelper.Factory { configuration ->
            GatedOpenHelper(configuration)
        }

        // Migration 11 → 12 (Phase 3 media persistence):
        // messages.localMediaPath — absolute path of the message's durable
        // on-device media copy inside the Trigger folder tree (Phase 1).
        // Nullable: rows without an archived copy (still downloading,
        // download failed, view-once — which is never archived) keep NULL and
        // render/play from the URL pipeline. ADD COLUMN is metadata-only.
        private val ADD_MESSAGES_LOCAL_MEDIA_PATH = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN localMediaPath TEXT")
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

/**
 * SupportSQLiteOpenHelper that refuses to touch the database file until the
 * AppServiceContainer DI gate ([AppServiceContainer.databaseReady]) has
 * completed — i.e. until the one-time rename + encrypt migration has run and
 * the SQLCipher passphrase is published. The real helper is only created at
 * that point, delegating to [SupportOpenHelperFactory] with the passphrase.
 *
 * Every Room code path funnels through getWritableDatabase()/
 * getReadableDatabase() — repositories, ViewModels holding direct DAOs
 * (DashboardViewModel), background sync, clearAllTables — so gating HERE
 * makes it structurally impossible for anything to open the store before the
 * migration finished. If the pipeline failed, every open throws the pipeline's
 * exception: fail fast instead of silently creating an empty store.
 *
 * Blocking note: the await blocks the calling thread, which is always a Room
 * executor / background thread — Room never opens a database synchronously on
 * main.
 */
private class GatedOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration
) : SupportSQLiteOpenHelper {

    private val delegateLock = Any()

    @Volatile
    private var delegate: SupportSQLiteOpenHelper? = null

    /** WAL flag Room may set before the first open — re-applied on creation. */
    @Volatile
    private var writeAheadLoggingEnabled = false

    private fun unwrapped(): SupportSQLiteOpenHelper {
        synchronized(delegateLock) {
            delegate?.let { return it }
            val gate = AppServiceContainer.databaseReady
            if (!gate.isCompleted) {
                runBlocking { gate.await() }
            }
            val passphrase = AppServiceContainer.dbPassphrase
                ?: error("Database gate released without a passphrase")
            TriggerDbMigrator.ensureSqlCipherLoaded()
            val created = SupportOpenHelperFactory(passphrase).create(configuration)
            if (writeAheadLoggingEnabled) {
                created.setWriteAheadLoggingEnabled(true)
            }
            delegate = created
            return created
        }
    }

    override val databaseName: String?
        get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        writeAheadLoggingEnabled = enabled
        synchronized(delegateLock) { delegate?.setWriteAheadLoggingEnabled(enabled) }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = unwrapped().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = unwrapped().readableDatabase

    override fun close() {
        synchronized(delegateLock) {
            delegate?.close()
            delegate = null
        }
    }
}
