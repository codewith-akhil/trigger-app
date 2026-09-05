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

    init {
        // Sync conversations from Supabase on app launch (multi-device sync)
        viewModelScope.launch {
            try {
                val payload = org.json.JSONObject().apply {
                    put("action", "pull")
                }
                val result = supabaseClient.invokeFunction("sync-conversations", payload)
                if (result is com.example.service.supabase.SupabaseResult.Success) {
                    val arr = result.data.optJSONArray("conversations") ?: return@launch
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        // Insert/update each conversation in Room
                        val conv = com.example.data.local.ConversationEntity(
                            id = obj.getString("id"),
                            name = obj.optString("peer_name", "Unknown"),
                            avatarRes = null,
                            initialColor = obj.optLong("peer_avatar_color", 0xFF00A884),
                            lastMessage = obj.optString("last_message", ""),
                            timestamp = obj.optString("last_message_at", ""),
                            unreadCount = obj.optInt("unread_count", 0),
                            isPinned = obj.optBoolean("is_pinned", false),
                            hasStatusUpdate = false,
                            isGroup = obj.optBoolean("is_group", false),
                            isOnline = false,
                            lastSeenText = "offline",
                            disappearingDuration = obj.optString("disappearing_duration", "OFF"),
                            isMuted = obj.optBoolean("is_muted", false),
                            isBlocked = obj.optBoolean("is_blocked", false)
                        )
                        repository.insertConversation(conv)
                    }
                }
            } catch (_: Exception) { }
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
