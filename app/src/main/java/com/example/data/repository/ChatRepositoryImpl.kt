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

    fun getConversation(id: String): Flow<DomainConversation?> {
        return conversationDao.getConversationById(id).map { it?.toDomain() }
    }

    fun getMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getMessagesForConversation(conversationId).map { list ->
            list.map { it.toDomainMessage() }
        }
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

        if (isOnline) {
            // Realtime WhatsApp ticks simulation: SENDING -> SENT -> DELIVERED -> READ
            coroutineScope.launch {
                delay(350)
                messageDao.updateMessageStatus(message.id, MessageStatus.SENT.name)
                delay(600)
                messageDao.updateMessageStatus(message.id, MessageStatus.DELIVERED.name)
                delay(900)
                messageDao.updateMessageStatus(message.id, MessageStatus.READ.name)
            }
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
        coroutineScope.launch {
            delay(400)
            messageDao.updateMessageStatus(messageId, MessageStatus.SENT.name)
            delay(500)
            messageDao.updateMessageStatus(messageId, MessageStatus.DELIVERED.name)
            delay(800)
            messageDao.updateMessageStatus(messageId, MessageStatus.READ.name)
        }
    }
}
