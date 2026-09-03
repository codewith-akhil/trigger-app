package com.example.service

import com.example.model.PresenceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PresenceServiceImpl(
    private val scope: CoroutineScope
) : PresenceService {

    private val _connectionState = MutableStateFlow(PresenceStatus.ONLINE)
    override val connectionState = _connectionState.asStateFlow()

    private val contactPresenceMap = ConcurrentHashMap<String, MutableStateFlow<Pair<PresenceStatus, String>>>()

    override fun observeContactPresence(contactId: String): Flow<Pair<PresenceStatus, String>> {
        val flow = contactPresenceMap.getOrPut(contactId) {
            MutableStateFlow(PresenceStatus.ONLINE to "online")
        }
        return flow.asStateFlow()
    }

    override suspend fun setUserTyping(conversationId: String, isTyping: Boolean) {
        // Broadcasts user typing state to real-time service
    }

    override suspend fun setUserRecording(conversationId: String, isRecording: Boolean) {
        // Broadcasts user recording state to real-time service
    }

    override fun setNetworkConnected(isConnected: Boolean) {
        if (!isConnected) {
            _connectionState.value = PresenceStatus.OFFLINE
        } else {
            scope.launch {
                _connectionState.value = PresenceStatus.RECONNECTING
                delay(1200)
                _connectionState.value = PresenceStatus.ONLINE
            }
        }
    }

    fun setContactPresence(contactId: String, status: PresenceStatus, text: String) {
        val flow = contactPresenceMap.getOrPut(contactId) {
            MutableStateFlow(status to text)
        }
        flow.value = status to text
    }
}
