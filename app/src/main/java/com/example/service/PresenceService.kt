package com.example.service

import com.example.model.PresenceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PresenceService {
    val connectionState: StateFlow<PresenceStatus>
    fun observeContactPresence(contactId: String): Flow<Pair<PresenceStatus, String>>
    suspend fun setUserTyping(conversationId: String, isTyping: Boolean)
    suspend fun setUserRecording(conversationId: String, isRecording: Boolean)
    fun setNetworkConnected(isConnected: Boolean)
}
