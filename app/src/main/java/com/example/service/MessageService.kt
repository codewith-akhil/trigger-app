package com.example.service

import com.example.model.DomainMessage
import com.example.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageService {
    fun observeMessages(conversationId: String): Flow<List<DomainMessage>>
    suspend fun sendMessage(message: DomainMessage)
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
    suspend fun toggleReaction(messageId: String, emoji: String)
    suspend fun markViewOnceOpened(messageId: String)
    suspend fun deleteForMe(messageId: String)
    suspend fun deleteForEveryone(messageId: String)
    suspend fun clearChat(conversationId: String)
    fun searchMessages(conversationId: String, query: String): Flow<List<DomainMessage>>
    fun getMediaMessages(conversationId: String): Flow<List<DomainMessage>>
    fun getDocumentMessages(conversationId: String): Flow<List<DomainMessage>>
    suspend fun retryFailedMessage(messageId: String)
    suspend fun forwardMessage(message: DomainMessage, targetConversationIds: List<String>)
    suspend fun toggleStarMessage(messageId: String)
}
