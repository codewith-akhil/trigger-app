package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMillis ASC, seq ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    // ------------------------------------------------------------------
    // Task 25 — cursor pagination (canonical order: timestampMillis, seq, id)
    // ------------------------------------------------------------------

    /** The latest [limit] messages, NEWEST first (initial chat window). */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMillis DESC, seq DESC, id DESC LIMIT :limit")
    suspend fun getLatestMessages(conversationId: String, limit: Int): List<MessageEntity>

    /** Page STRICTLY older than the (ts, seq, id) cursor, NEWEST first.
     *  The three-branch comparison makes same-millisecond / same-seq groups
     *  impossible to skip or duplicate across page boundaries. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND " +
        "(timestampMillis < :ts OR (timestampMillis = :ts AND seq < :seq) OR (timestampMillis = :ts AND seq = :seq AND id < :id)) " +
        "ORDER BY timestampMillis DESC, seq DESC, id DESC LIMIT :limit"
    )
    suspend fun getMessagesBeforeCursor(conversationId: String, ts: Long, seq: Long, id: String, limit: Int): List<MessageEntity>

    /** Page STRICTLY newer than the (ts, seq, id) cursor, OLDEST first. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND " +
        "(timestampMillis > :ts OR (timestampMillis = :ts AND seq > :seq) OR (timestampMillis = :ts AND seq = :seq AND id > :id)) " +
        "ORDER BY timestampMillis ASC, seq ASC, id ASC LIMIT :limit"
    )
    suspend fun getMessagesAfterCursor(conversationId: String, ts: Long, seq: Long, id: String, limit: Int): List<MessageEntity>

    /** Live flow over the INCLUSIVE [top..bottom] window, ascending. Null
     *  bounds are disabled with the hasTop/hasBottom flags (0 = unbounded on
     *  that side). This is the ONLY list query the open chat subscribes to —
     *  it re-emits on every row change inside the window and never loads
     *  more than the window itself. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
        "AND (:hasTop = 0 OR (timestampMillis > :topTs OR (timestampMillis = :topTs AND seq > :topSeq) OR (timestampMillis = :topTs AND seq = :topSeq AND id >= :topId))) " +
        "AND (:hasBottom = 0 OR (timestampMillis < :bottomTs OR (timestampMillis = :bottomTs AND seq < :bottomSeq) OR (timestampMillis = :bottomTs AND seq = :bottomSeq AND id <= :bottomId))) " +
        "ORDER BY timestampMillis ASC, seq ASC, id ASC"
    )
    fun observeMessageWindow(
        conversationId: String,
        hasTop: Int, topTs: Long, topSeq: Long, topId: String,
        hasBottom: Int, bottomTs: Long, bottomSeq: Long, bottomId: String
    ): Flow<List<MessageEntity>>

    /** Legacy page query kept for compatibility. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND timestampMillis < :beforeTimestamp ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getMessagesPage(conversationId: String, beforeTimestamp: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND status = 'FAILED'")
    suspend fun getFailedMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 'FAILED'")
    suspend fun getAllFailedMessages(): List<MessageEntity>

    /** Offline outbox (Task 24): every outgoing message still stuck in
     *  SENDING (app killed mid-send) or FAILED older than [olderThanMillis]
     *  — retried automatically when connectivity returns. */
    @Query("SELECT * FROM messages WHERE isOutgoing = 1 AND status IN ('SENDING', 'FAILED') AND timestampMillis < :olderThanMillis")
    suspend fun getStaleOutgoingMessages(olderThanMillis: Long): List<MessageEntity>

    // View-once media is excluded from the starred grid too: star is hidden
    // for it, and a starred view-once row must never render a thumbnail.
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isStarred = 1 AND isViewOnce = 0 ORDER BY timestampMillis DESC")
    suspend fun getStarredMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isPinned = 1 ORDER BY timestampMillis DESC")
    suspend fun getPinnedMessages(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET reactionsRaw = :reactionsRaw WHERE id = :messageId")
    suspend fun updateMessageReactions(messageId: String, reactionsRaw: String)

    @Query("UPDATE messages SET isViewed = 1 WHERE id = :messageId")
    suspend fun markViewOnceAsViewed(messageId: String)

    // Tombstone also NULLs the local media columns — the server strips them
    // on delete-for-everyone, so the deleter's own Room row should not keep a
    // still-openable copy of the "deleted" media either (localMediaPath
    // included — the FILE itself is left in place for user-managed cleanup).
    @Query("UPDATE messages SET isDeletedForEveryone = 1, text = 'This message was deleted', mediaUrl = NULL, mediaPath = NULL, mediaThumbnail = NULL, mediaBucket = NULL, localMediaPath = NULL WHERE id = :messageId")
    suspend fun markDeletedForEveryone(messageId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversationMessages(conversationId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND text LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY timestampMillis DESC")
    fun searchMessages(conversationId: String, query: String): Flow<List<MessageEntity>>

    // View-once media is excluded: it must only be viewable in the chat bubble
    // (once), never from the shared Media gallery tab.
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND (type = 'IMAGE' OR type = 'VIDEO') AND isViewOnce = 0 AND isDeletedForEveryone = 0 ORDER BY timestampMillis DESC")
    fun getMediaMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND type = 'DOCUMENT' ORDER BY timestampMillis DESC")
    fun getDocumentMessages(conversationId: String): Flow<List<MessageEntity>>

    // Auto delete sweep: only messages created AFTER the setting was activated
    // are eligible (user requirement: enabling never deletes older history).
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestampMillis < :expireBeforeMillis AND timestampMillis >= :activatedAfterMillis")
    suspend fun deleteExpiredDisappearingMessages(conversationId: String, expireBeforeMillis: Long, activatedAfterMillis: Long)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun updateMessageStarred(messageId: String, isStarred: Boolean)

    @Query("UPDATE messages SET isPinned = :isPinned WHERE id = :messageId")
    suspend fun updateMessagePinned(messageId: String, isPinned: Boolean)

    @Query("UPDATE messages SET text = :newText, editedAt = :editedAt WHERE id = :messageId")
    suspend fun updateMessageText(messageId: String, newText: String, editedAt: Long)

    /** Writes the final remote media URL back onto the message after upload. */
    @Query("UPDATE messages SET mediaUrl = :mediaUrl WHERE id = :messageId")
    suspend fun updateMessageMedia(messageId: String, mediaUrl: String)

    /** Writes the final URL AND the storage coordinates (bucket + object path)
     *  so the URL can be rebuilt forever, even after reinstall. */
    @Query("UPDATE messages SET mediaUrl = :mediaUrl, mediaBucket = :bucket, mediaPath = :path WHERE id = :messageId")
    suspend fun updateMessageMediaFull(messageId: String, mediaUrl: String, bucket: String?, path: String?)

    /** Phase 3 media persistence: records (or clears) the absolute path of
     *  the message's durable on-device copy inside the Trigger folder tree.
     *  Null = no archived copy — render/play falls back to the URL pipeline. */
    @Query("UPDATE messages SET localMediaPath = :path WHERE id = :messageId")
    suspend fun updateMessageLocalMediaPath(messageId: String, path: String?)

    /** H4 unification: moves every message cached under a legacy peer-UUID key
     *  to the real server conversation UUID. Idempotent. */
    @Query("UPDATE messages SET conversationId = :toConversationId WHERE conversationId = :fromConversationId")
    suspend fun rekeyConversationMessages(fromConversationId: String, toConversationId: String)

    /** Realtime-driven view-once sync: mark a message opened. */
    @Query("UPDATE messages SET isViewed = 1 WHERE id = :messageId")
    suspend fun markMessageViewed(messageId: String)
}
