package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastActivityMillis DESC, id ASC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun getConversationById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationByIdOnce(id: String): ConversationEntity?

    /** Finds the conversation whose PEER is the given user UUID — used to map
     *  legacy peer-keyed message rows onto the real conversation. */
    @Query("SELECT * FROM conversations WHERE peerId = :peerId LIMIT 1")
    suspend fun getConversationByPeer(peerId: String): ConversationEntity?

    /** One-shot snapshot of every conversation row — used by the dashboard
     *  sync to purge legacy rows that the server pull no longer contains. */
    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationRowsOnce(): List<ConversationEntity>

    /** Batch delete by primary key — removes dedupe losers, declined/blocked
     *  mirrors and legacy non-UUID rows during the sync-conversations pull. */
    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversationsByIds(ids: List<String>)

    @Query("UPDATE conversations SET peerId = :peerId WHERE id = :id")
    suspend fun updatePeerId(id: String, peerId: String?)

    /** Persist a profile-resolved avatar URL onto the conversation row so the
     *  chat list renders the real photo instead of the letter fallback. */
    @Query("UPDATE conversations SET peerAvatarUrl = :url WHERE id = :id")
    suspend fun updateAvatarUrl(id: String, url: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Query("UPDATE conversations SET lastMessage = :lastMessage, timestamp = :timestamp, lastActivityMillis = :lastActivityMillis WHERE id = :id")
    suspend fun updateLastMessage(id: String, lastMessage: String, timestamp: String, lastActivityMillis: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE conversations SET isOnline = :isOnline, lastSeenText = :lastSeenText WHERE id = :id")
    suspend fun updatePresence(id: String, isOnline: Boolean, lastSeenText: String)

    @Query("UPDATE conversations SET disappearingDuration = :duration, disappearingUpdatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun updateDisappearing(id: String, duration: String, updatedAtMillis: Long)

    @Query("UPDATE conversations SET isMuted = :isMuted WHERE id = :id")
    suspend fun setMuted(id: String, isMuted: Boolean)

    @Query("UPDATE conversations SET isBlocked = :isBlocked WHERE id = :id")
    suspend fun updateBlocked(id: String, isBlocked: Boolean)

    @Query("UPDATE conversations SET isArchived = :isArchived WHERE id = :id")
    suspend fun updateArchived(id: String, isArchived: Boolean)
}
