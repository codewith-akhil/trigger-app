package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppServiceContainer
import com.example.model.DomainConversation
import com.example.model.PresenceStatus
import com.example.ui.screens.ChatFilter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {
    private val repository = AppServiceContainer.chatRepository
    private val presenceService = AppServiceContainer.presenceService
    private val supabaseClient = AppServiceContainer.supabaseClient

    val connectionState: StateFlow<PresenceStatus> = presenceService.connectionState

    // Surfaces any failure from the initial `sync-conversations` pull so the
    // UI can render a retry banner instead of silently eating the error.
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // True while the initial `sync-conversations` pull is in flight — lets the
    // dashboard render a non-zero state instead of an empty list flash.
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        // Sync conversations from Supabase on app launch (multi-device sync)
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val payload = org.json.JSONObject().apply {
                    put("action", "pull")
                }
                val result = supabaseClient.invokeFunction("sync-conversations", payload)
                if (result is com.example.service.supabase.SupabaseResult.Success) {
                    result.data.optJSONArray("conversations")?.let { applyConversationPull(it) }
                } else if (result is com.example.service.supabase.SupabaseResult.Error) {
                    _errorMessage.value = result.message.ifBlank { "Failed to sync conversations." }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to sync conversations."
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Applies a `sync-conversations` pull payload to Room. Both the initial
     * sync and retrySync() route through here — retrySync() previously rebuilt
     * the entity WITHOUT [peerId], silently severing presence/typing/calls for
     * every conversation after the first retry.
     */
    private suspend fun applyConversationPull(arr: org.json.JSONArray) {
        val myId = supabaseClient.currentUser?.id ?: ""
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            // H4/H5: persist the OTHER user's uuid on the row so a chat opened
            // by conversation uuid can still key presence/typing/calls.
            val ownerId = obj.optString("owner_id", "")
            val peerRaw = obj.optString("peer_id", "")
            val otherId = when {
                ownerId == myId -> peerRaw
                peerRaw == myId -> ownerId
                else -> peerRaw
            }
            val id = obj.getString("id")
            // isArchived is a LOCAL-ONLY flag (not part of the server pull).
            // insertConversation is REPLACE — without preserving it, every
            // sync silently un-archived all archived chats.
            val existing = repository.getConversationByIdOnce(id)
            val conv = com.example.data.local.ConversationEntity(
                id = id,
                peerId = otherId.takeIf { it.isNotBlank() },
                name = obj.optString("peer_name", "Unknown"),
                avatarRes = null,
                initialColor = obj.optLong("peer_avatar_color", 0xFF00A884),
                lastMessage = obj.optString("last_message", ""),
                timestamp = obj.optString("last_message_at", ""),
                lastActivityMillis = parseIsoToMillis(obj.optString("last_message_at", "")),
                unreadCount = obj.optInt("unread_count", 0),
                isPinned = obj.optBoolean("is_pinned", false),
                hasStatusUpdate = false,
                isGroup = obj.optBoolean("is_group", false),
                isOnline = false,
                lastSeenText = "offline",
                disappearingDuration = obj.optString("disappearing_duration", "OFF"),
                disappearingUpdatedAtMillis =
                    parseIsoToMillis(obj.optString("disappearing_updated_at", "")).takeIf { it > 0L },
                isMuted = obj.optBoolean("is_muted", false),
                isBlocked = obj.optBoolean("is_blocked", false),
                isArchived = existing?.isArchived ?: false
            )
            repository.insertConversation(conv)
        }
    }

    /** Parses an ISO-8601 timestamp ("2026-09-07T16:21:39Z") to epoch millis;
     *  returns 0L when unparsable so rows sort deterministically until the
     *  next real message refreshes the value. */
    private fun parseIsoToMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                // Fallback: offset form ("2026-09-07T16:21:39+00:00")
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /** Clears [errorMessage] after the UI has shown it (e.g. user dismissed
     *  the retry banner or successfully retried). */
    fun clearError() {
        _errorMessage.value = null
    }

    /** Re-runs the initial `sync-conversations` pull. Wired to the dashboard's
     *  retry banner so users can recover from a transient network failure. */
    fun retrySync() {
        if (_isSyncing.value) return
        _errorMessage.value = null
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val payload = org.json.JSONObject().apply {
                    put("action", "pull")
                }
                val result = supabaseClient.invokeFunction("sync-conversations", payload)
                if (result is com.example.service.supabase.SupabaseResult.Success) {
                    result.data.optJSONArray("conversations")?.let { applyConversationPull(it) }
                } else if (result is com.example.service.supabase.SupabaseResult.Error) {
                    _errorMessage.value = result.message.ifBlank { "Failed to sync conversations." }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to sync conversations."
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(ChatFilter.ALL)
    val selectedFilter = _selectedFilter.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived = _showArchived.asStateFlow()

    val conversations: StateFlow<List<DomainConversation>> = combine(
        repository.getAllConversations(),
        _searchQuery,
        _selectedFilter,
        _showArchived
    ) { all, query, filter, showArchived ->
        all.filter { conv ->
            // Archived filter — when showArchived=false, hide archived chats;
            // when showArchived=true, show only archived chats.
            val matchesArchiveFilter = if (showArchived) conv.isArchived else !conv.isArchived

            val matchesQuery = query.isBlank() ||
                    conv.name.contains(query, ignoreCase = true) ||
                    conv.lastMessage.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                ChatFilter.ALL -> true
                ChatFilter.UNREAD -> conv.unreadCount > 0
                ChatFilter.GROUPS -> conv.isGroup
            }

            matchesArchiveFilter && matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChanged(q: String) {
        _searchQuery.value = q
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun onFilterSelected(filter: ChatFilter) {
        _selectedFilter.value = filter
    }

    fun setFilter(filter: ChatFilter) {
        _selectedFilter.value = filter
    }

    fun setShowArchived(show: Boolean) {
        _showArchived.value = show
    }

    fun markAsRead(id: String) {
        viewModelScope.launch {
            repository.markConversationRead(id)
        }
    }

    fun toggleNetworkConnection() {
        val current = connectionState.value
        presenceService.setNetworkConnected(current == PresenceStatus.OFFLINE)
    }
}
