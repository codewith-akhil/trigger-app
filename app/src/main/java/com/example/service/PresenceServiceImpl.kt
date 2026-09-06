package com.example.service

import android.util.Log
import com.example.di.AppServiceContainer
import com.example.model.PresenceStatus
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.isActive

/**
 * PresenceServiceImpl — REAL presence via Supabase.
 *
 * - setUserTyping/setUserRecording: calls update-presence edge function
 * - setNetworkConnected: calls update-presence (online/offline)
 * - observeContactPresence: fed by Realtime events on user_presences table
 * - Heartbeat: sends update-presence every 30s while app is foregrounded
 */
class PresenceServiceImpl(
    private val scope: CoroutineScope
) : PresenceService {

    companion object {
        private const val TAG = "PresenceServiceImpl"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L  // 30 seconds
    }

    private val _connectionState = MutableStateFlow(PresenceStatus.ONLINE)
    override val connectionState = _connectionState.asStateFlow()

    private val contactPresenceMap = ConcurrentHashMap<String, MutableStateFlow<Pair<PresenceStatus, String>>>()

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    /**
     * Clears all per-account presence state. Called by AccountStateManager on
     * logout / account switch so cached contact presence from the previous
     * account is never shown to the next one.
     */
    fun reset() {
        stopHeartbeat()
        _connectionState.value = PresenceStatus.OFFLINE
        contactPresenceMap.clear()
    }

    override fun observeContactPresence(contactId: String): Flow<Pair<PresenceStatus, String>> {
        val flow = contactPresenceMap.getOrPut(contactId) {
            MutableStateFlow(PresenceStatus.OFFLINE to "offline")
        }
        return flow.asStateFlow()
    }

    override suspend fun setUserTyping(conversationId: String, isTyping: Boolean) {
        // Real typing indicator — broadcast via Supabase Realtime broadcast channel
        // For now we use a simple approach: update a presence record
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("action", "typing")
            put("conversation_id", conversationId)
            put("is_typing", isTyping)
        }
        val result = supabaseClient.invokeFunction("update-presence", payload)
        if (result is SupabaseResult.Error) {
            Log.w(TAG, "Failed to set typing: ${result.message}")
        }
    }

    override suspend fun setUserRecording(conversationId: String, isRecording: Boolean) {
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("action", "recording")
            put("conversation_id", conversationId)
            put("is_recording", isRecording)
        }
        val result = supabaseClient.invokeFunction("update-presence", payload)
        if (result is SupabaseResult.Error) {
            Log.w(TAG, "Failed to set recording: ${result.message}")
        }
    }

    override fun setNetworkConnected(isConnected: Boolean) {
        if (!isConnected) {
            _connectionState.value = PresenceStatus.OFFLINE
            sendPresenceUpdate(false)
            stopHeartbeat()
        } else {
            scope.launch {
                _connectionState.value = PresenceStatus.RECONNECTING
                delay(800)
                _connectionState.value = PresenceStatus.ONLINE
                sendPresenceUpdate(true)
                startHeartbeat()
                // Network is back — retry any messages that failed while offline
                try {
                    AppServiceContainer.messageService.retryAllFailedMessages()
                } catch (e: Exception) {
                    Log.w(TAG, "retryAllFailedMessages after reconnect failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Called when the app comes to the foreground — set online + start heartbeat.
     */
    fun onAppForeground() {
        _connectionState.value = PresenceStatus.ONLINE
        sendPresenceUpdate(true)
        startHeartbeat()
    }

    /**
     * Called when the app goes to the background — set offline.
     */
    fun onAppBackground() {
        sendPresenceUpdate(false)
        stopHeartbeat()
    }

    private fun sendPresenceUpdate(isOnline: Boolean) {
        scope.launch {
            val supabaseClient = AppServiceContainer.supabaseClient
            val payload = JSONObject().apply {
                put("isOnline", isOnline)
            }
            val result = supabaseClient.invokeFunction("update-presence", payload)
            if (result is SupabaseResult.Error) {
                Log.w(TAG, "Presence update failed: ${result.message}")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendPresenceUpdate(true)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Called by MessageServiceImpl when a Realtime presence event is received.
     */
    fun setContactPresence(contactId: String, status: PresenceStatus, text: String) {
        val flow = contactPresenceMap.getOrPut(contactId) {
            MutableStateFlow(status to text)
        }
        flow.value = status to text
    }
}
