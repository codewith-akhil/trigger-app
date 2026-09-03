package com.example.service

import com.example.data.repository.ChatRepositoryImpl
import com.example.model.DomainMessage
import com.example.model.MessageStatus
import com.example.model.PresenceStatus
import kotlinx.coroutines.flow.Flow

class MessageServiceImpl(
    private val repository: ChatRepositoryImpl,
    private val presenceService: PresenceService
) : MessageService {

    override fun observeMessages(conversationId: String): Flow<List<DomainMessage>> {
        return repository.getMessages(conversationId)
    }

    override suspend fun sendMessage(message: DomainMessage) {
        val isOnline = presenceService.connectionState.value != PresenceStatus.OFFLINE
        repository.sendMessage(message, isOnline)
    }

    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        repository.updateMessageStatus(messageId, status)
    }

    override suspend fun toggleReaction(messageId: String, emoji: String) {
        repository.toggleReaction(messageId, emoji)
    }

    override suspend fun markViewOnceOpened(messageId: String) {
        repository.markViewOnceAsViewed(messageId)
    }

    override suspend fun deleteForMe(messageId: String) {
        repository.deleteForMe(messageId)
    }

    override suspend fun deleteForEveryone(messageId: String) {
        repository.deleteForEveryone(messageId)
    }

    override suspend fun clearChat(conversationId: String) {
        repository.clearChat(conversationId)
    }

    override fun searchMessages(conversationId: String, query: String): Flow<List<DomainMessage>> {
        return repository.searchMessages(conversationId, query)
    }

    override fun getMediaMessages(conversationId: String): Flow<List<DomainMessage>> {
        return repository.getMediaMessages(conversationId)
    }

    override fun getDocumentMessages(conversationId: String): Flow<List<DomainMessage>> {
        return repository.getDocumentMessages(conversationId)
    }

    override suspend fun retryFailedMessage(messageId: String) {
        repository.retryFailedMessage(messageId)
    }

    override suspend fun forwardMessage(message: DomainMessage, targetConversationIds: List<String>) {
        val currentTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        targetConversationIds.forEach { targetId ->
            val forwardedMsg = message.copy(
                id = "fwd_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}",
                conversationId = targetId,
                timestamp = currentTime,
                timestampMillis = System.currentTimeMillis(),
                isOutgoing = true,
                status = MessageStatus.SENDING,
                reactions = emptyList()
            )
            repository.sendMessage(forwardedMsg, presenceService.connectionState.value != PresenceStatus.OFFLINE)
        }
    }

    override suspend fun toggleStarMessage(messageId: String) {
        // Toggle starred
    }
}
