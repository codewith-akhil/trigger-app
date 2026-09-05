package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMillis ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

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

    @Query("UPDATE messages SET isDeletedForEveryone = 1, text = 'This message was deleted' WHERE id = :messageId")
    suspend fun markDeletedForEveryone(messageId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversationMessages(conversationId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND text LIKE '%' || :query || '%' ORDER BY timestampMillis DESC")
    fun searchMessages(conversationId: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND (type = 'IMAGE' OR type = 'VIDEO') ORDER BY timestampMillis DESC")
    fun getMediaMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND type = 'DOCUMENT' ORDER BY timestampMillis DESC")
    fun getDocumentMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestampMillis < :expireBeforeMillis")
    suspend fun deleteExpiredDisappearingMessages(conversationId: String, expireBeforeMillis: Long)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun updateMessageStarred(messageId: String, isStarred: Boolean)

    @Query("UPDATE messages SET text = :newText WHERE id = :messageId")
    suspend fun updateMessageText(messageId: String, newText: String)
}
