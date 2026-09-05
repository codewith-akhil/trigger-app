package com.example.data.repository

import com.example.data.local.ChatDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepositoryImpl(
    private val database: ChatDatabase,
    private val coroutineScope: CoroutineScope
) {
    private val messageDao = database.messageDao()
    private val conversationDao = database.conversationDao()

    fun getAllConversations(): Flow<List<DomainConversation>> {
        return conversationDao.getAllConversations().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun insertConversation(entity: com.example.data.local.ConversationEntity) {
        conversationDao.insertConversation(entity)
    }

    fun getConversation(id: String): Flow<DomainConversation?> {
        return conversationDao.getConversationById(id).map { it?.toDomain() }
    }

    fun getMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getMessagesForConversation(conversationId).map { list ->
            list.map { it.toDomainMessage() }
        }
    }

    /**
     * Loads one page of older messages ending before `beforeTimestamp`.
     * Used for cursor-based pagination when the user scrolls to the top.
     */
    suspend fun getMessagesPage(conversationId: String, beforeTimestamp: Long, limit: Int = 50): List<DomainMessage> {
        return messageDao.getMessagesPage(conversationId, beforeTimestamp, limit).map { it.toDomainMessage() }
    }

    /**
     * Returns all messages with status FAILED (across all conversations) so the
     * offline queue can retry them.
     */
    suspend fun getAllFailedMessages(): List<DomainMessage> {
        return messageDao.getAllFailedMessages().map { it.toDomainMessage() }
    }

    /**
     * Returns starred messages in a conversation.
     */
    suspend fun getStarredMessages(conversationId: String): List<DomainMessage> {
        return messageDao.getStarredMessages(conversationId).map { it.toDomainMessage() }
    }

    /**
     * Returns pinned messages in a conversation.
     */
    suspend fun getPinnedMessages(conversationId: String): List<DomainMessage> {
        return messageDao.getPinnedMessages(conversationId).map { it.toDomainMessage() }
    }

    suspend fun sendMessage(message: DomainMessage, isOnline: Boolean) {
        val initialStatus = if (!isOnline) MessageStatus.FAILED else MessageStatus.SENDING
        val entity = MessageEntity.fromDomain(message.copy(status = initialStatus))
        messageDao.insertMessage(entity)

        // Update conversation's last message
        val lastMsgPreview = when (message.type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎤 Voice message"
            MessageType.DOCUMENT -> "📄 ${message.fileName ?: "Document"}"
            MessageType.LOCATION -> "📍 Location"
            MessageType.CONTACT -> "👤 ${message.contactName ?: "Contact"}"
            MessageType.CALL_LOG -> message.text
            else -> message.text
        }
        conversationDao.updateLastMessage(message.conversationId, lastMsgPreview, message.timestamp)

        // Status transitions are driven by the send-message edge function (SENT)
        // and Supabase Realtime (DELIVERED/READ). No fake delay() ticks.
        if (isOnline) {
            // The MessageServiceImpl.sendMessage calls the edge function which
            // sets status=SENT on the server. Realtime UPDATE events from the
            // receiver's device will set DELIVERED and READ.
        }
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status.name)
    }

    suspend fun toggleReaction(messageId: String, emoji: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        val currentReactions = msg.toDomainMessage().reactions.toMutableList()
        val existingIndex = currentReactions.indexOfFirst { it.emoji == emoji }

        if (existingIndex != -1) {
            val existing = currentReactions[existingIndex]
            if (existing.userReacted) {
                // remove reaction
                if (existing.count <= 1) {
                    currentReactions.removeAt(existingIndex)
                } else {
                    currentReactions[existingIndex] = existing.copy(count = existing.count - 1, userReacted = false)
                }
            } else {
                currentReactions[existingIndex] = existing.copy(count = existing.count + 1, userReacted = true)
            }
        } else {
            currentReactions.add(MessageReaction(emoji = emoji, count = 1, userReacted = true))
        }

        val raw = currentReactions.joinToString(";") { "${it.emoji}:${it.count}:${it.userReacted}" }
        messageDao.updateMessageReactions(messageId, raw)
    }

    suspend fun markViewOnceAsViewed(messageId: String) {
        messageDao.markViewOnceAsViewed(messageId)
    }

    suspend fun deleteForEveryone(messageId: String) {
        messageDao.markDeletedForEveryone(messageId)
    }

    suspend fun deleteForMe(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    suspend fun clearChat(conversationId: String) {
        messageDao.clearConversationMessages(conversationId)
        conversationDao.updateLastMessage(conversationId, "", "")
    }

    fun searchMessages(conversationId: String, query: String): Flow<List<DomainMessage>> {
        return messageDao.searchMessages(conversationId, query).map { list ->
            list.map { it.toDomainMessage() }
        }
    }

    fun getMediaMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getMediaMessages(conversationId).map { list ->
            list.map { it.toDomainMessage() }
        }
    }

    fun getDocumentMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getDocumentMessages(conversationId).map { list ->
            list.map { it.toDomainMessage() }
        }
    }

    suspend fun setDisappearingDuration(conversationId: String, duration: DisappearingDuration) {
        conversationDao.updateDisappearing(conversationId, duration.name)
        if (duration != DisappearingDuration.OFF) {
            val expireThreshold = System.currentTimeMillis() - duration.millis
            messageDao.deleteExpiredDisappearingMessages(conversationId, expireThreshold)
        }
    }

    suspend fun markConversationRead(conversationId: String) {
        conversationDao.markAsRead(conversationId)
    }

    suspend fun setConversationMuted(conversationId: String, isMuted: Boolean) {
        conversationDao.setMuted(conversationId, isMuted)
    }

    suspend fun setBlocked(conversationId: String, isBlocked: Boolean) {
        conversationDao.updateBlocked(conversationId, isBlocked)
    }

    suspend fun retryFailedMessage(messageId: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        messageDao.updateMessageStatus(messageId, MessageStatus.SENDING.name)
        // The MessageServiceImpl will re-call the send-message edge function.
        // Status transitions come from Realtime, not from delay().
    }

    /**
     * Toggles the starred flag on a message.
     */
    suspend fun toggleStar(messageId: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        val newStarred = !msg.isStarred
        messageDao.updateMessageStarred(messageId, newStarred)
    }

    /**
     * Updates message text + edited_at timestamp (for edit feature).
     */
    suspend fun updateMessageText(messageId: String, newText: String) {
        messageDao.updateMessageText(messageId, newText, System.currentTimeMillis())
    }

    /**
     * Toggles the pinned flag on a message.
     */
    suspend fun togglePin(messageId: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        val newPinned = !msg.isPinned
        messageDao.updateMessagePinned(messageId, newPinned)
    }

    /**
     * Sets the archived flag on a conversation.
     */
    suspend fun setConversationArchived(conversationId: String, isArchived: Boolean) {
        conversationDao.updateArchived(conversationId, isArchived)
    }

    /**
     * Returns a single message by ID.
     */
    suspend fun getMessageById(messageId: String): DomainMessage? {
        return messageDao.getMessageById(messageId)?.toDomainMessage()
    }

    /**
     * Sets the pinned flag on a message.
     */
    suspend fun setMessagePinned(messageId: String, isPinned: Boolean) {
        messageDao.updateMessagePinned(messageId, isPinned)
    }

    /**
     * Sets the starred flag on a message.
     */
    suspend fun setMessageStarred(messageId: String, isStarred: Boolean) {
        messageDao.updateMessageStarred(messageId, isStarred)
    }

    /**
     * Updates the seq field on a message.
     */
    suspend fun updateMessageSeq(messageId: String, seq: Long) {
        val msg = messageDao.getMessageById(messageId) ?: return
        messageDao.insertMessage(msg.copy(seq = seq))
    }

    /**
     * Inserts a message directly (for incoming Realtime messages).
     */
    suspend fun insertMessage(message: DomainMessage) {
        messageDao.insertMessage(MessageEntity.fromDomain(message))
    }
}
