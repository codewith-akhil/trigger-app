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

    val connectionState: StateFlow<PresenceStatus> = presenceService.connectionState

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
