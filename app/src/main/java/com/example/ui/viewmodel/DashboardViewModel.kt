package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppServiceContainer
import com.example.model.DomainConversation
import com.example.model.PresenceStatus
import com.example.service.MediaUrlResolver
import com.example.service.supabase.SupabaseResult
import com.example.ui.screens.ChatFilter
import com.example.util.ChatTimeFormatter
import com.example.util.ConnectivityObserver
import com.example.util.optStringOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * INTERNAL — one parsed `sync-conversations` pull row plus its dedupe keys.
 * File-private so [DashboardViewModel.applyConversationPull] stays readable.
 */
private data class DashboardPullRow(
    val id: String,
    val otherId: String,
    val isMirrored: Boolean,
    val pairKey: String,
    val createdAtMillis: Long,
    val lastActivityMillis: Long,
    val unreadCount: Int,
    val entity: com.example.data.local.ConversationEntity
)

class DashboardViewModel : ViewModel() {
    private val repository = AppServiceContainer.chatRepository
    private val presenceService = AppServiceContainer.presenceService
    private val supabaseClient = AppServiceContainer.supabaseClient
    // Direct DAO access for the sync pull's batch dedupe/purge — the repository
    // surface has no bulk-delete primitive and its domain mapping drops the
    // lastActivityMillis stamp the chat list needs for timestamp rendering.
    private val conversationDao = AppServiceContainer.database.conversationDao()

    val connectionState: StateFlow<PresenceStatus> = presenceService.connectionState

    // Surfaces any failure from the initial `sync-conversations` pull so the
    // UI can render a retry banner instead of silently eating the error.
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // True while the initial `sync-conversations` pull is in flight — lets the
    // dashboard render a non-zero state instead of an empty list flash.
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Unread count for the dashboard header bell badge. Fetched on init and
    // refreshed on every dashboard (re)composition; the notifications screen
    // clears it via [clearNotificationsBadge] after marking rows read.
    private val _notificationsUnread = MutableStateFlow(0)
    val notificationsUnread: StateFlow<Int> = _notificationsUnread.asStateFlow()

    private var notificationsRefreshInFlight = false

    init {
        // Local-first (Task 24): seed the process-wide connectivity signal and
        // flush the offline outbox whenever internet comes back (stuck SENDING
        // / FAILED messages retry automatically; idempotency keys prevent
        // duplicates). Also flush once on app start — anything that expired
        // while the app was closed gets a chance now.
        ConnectivityObserver.start(AppServiceContainer.context)
        // Background multi-device message catch-up (WhatsApp model): runs
        // AFTER the conversation pull below has landed (chained in its
        // finally) and on every GENUINE connectivity regain. It used to run
        // as a parallel launch at t=0 — on a fresh install that raced the
        // conversation pull, found Room's conversations table still EMPTY,
        // and pulled nothing, so every chat opened blank until some
        // unrelated trigger fired a pull. Opening a chat NEVER syncs: the
        // chat screen renders Room directly, offline included. Failures are
        // non-fatal (retried on the next regain).
        viewModelScope.launch {
            // .drop(1): the StateFlow replays the current state on subscribe,
            // and running catch-up on that synthetic initial emission is the
            // fresh-install race described above (plus a duplicate round that
            // could hit the 30-req/min sync rate limit). Real offline→online
            // transitions still fire it.
            ConnectivityObserver.isOnline.drop(1).collect { online ->
                if (online) {
                    try {
                        AppServiceContainer.messageService.retryPendingOutbox()
                    } catch (_: Exception) {
                    }
                    try {
                        AppServiceContainer.messageService.backgroundCatchUpSync()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // Sync conversations from Supabase on app launch (multi-device sync)
        viewModelScope.launch {
            _isSyncing.value = true
            // Flush the offline outbox first — independent of conversations,
            // idempotent (dedupe keys), so stuck SENDING/FAILED messages from
            // the last session retry immediately.
            try {
                AppServiceContainer.messageService.retryPendingOutbox()
            } catch (_: Exception) {
            }
            try {
                val payload = org.json.JSONObject().apply {
                    put("action", "pull")
                }
                val result = supabaseClient.invokeFunction("sync-conversations", payload)
                if (result is SupabaseResult.Success) {
                    result.data.optJSONArray("conversations")?.let { applyConversationPull(it) }
                } else if (result is SupabaseResult.Error) {
                    _errorMessage.value = result.message.ifBlank { "Failed to sync conversations." }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to sync conversations."
            } finally {
                _isSyncing.value = false
                // Message catch-up runs HERE — strictly after conversation
                // rows exist in Room. On a fresh install this ordering is the
                // whole game: the per-conversation watermark pulls only find
                // their targets once the conversations themselves have been
                // pulled, so by the time the user taps a chat its messages
                // are already cached and the chat renders instantly.
                try {
                    AppServiceContainer.messageService.backgroundCatchUpSync()
                } catch (_: Exception) {
                }
            }
        }
        // Bell badge seed. Defensive by design: the user_notifications table may
        // not exist yet on older deployments — any failure simply means 0.
        refreshNotificationsUnread()
    }

    /**
     * Applies a `sync-conversations` pull payload to Room with dedupe +
     * completeness so the chat list shows exactly one row per peer pair:
     *
     *  1. Rows whose request_status is "declined"/"blocked" are never upserted
     *     and any stale local copy is deleted.
     *  2. Rows are grouped by peer pair (the OTHER user's uuid, mirroring the
     *     MessageServiceImpl canonical convention: the pair's OLDEST row is the
     *     thread every client converges on). The group keeps one canonical row
     *     and merges max(lastActivityMillis) + max(unreadCount) into it; loser
     *     ids are deleted from Room.
     *  3. Legacy local rows whose id is not a valid UUID and that the pull does
     *     not contain are purged.
     *  4. Owner-perspective peer_name fix: on mirrored rows (peer_id == me, the
     *     other user's row) the server's peer_name is MY name — display
     *     name/avatar are resolved from the profiles batch instead.
     */
    private suspend fun applyConversationPull(arr: org.json.JSONArray) {
        val myId = supabaseClient.currentUser?.id ?: ""
        if (myId.isBlank()) return

        val pulledIds = HashSet<String>()
        val rows = ArrayList<DashboardPullRow>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isBlank()) continue
            pulledIds.add(id)

            // Declined/blocked request mirrors must vanish from the list —
            // never upsert them and drop any stale local copy.
            val requestStatus = obj.optStringOrNull("request_status")
            if (requestStatus != null &&
                (requestStatus.equals("declined", true) || requestStatus.equals("blocked", true))
            ) {
                conversationDao.deleteConversationsByIds(listOf(id))
                continue
            }

            val ownerId = obj.optStringOrNull("owner_id") ?: ""
            val peerRaw = obj.optStringOrNull("peer_id") ?: ""
            val isMirrored = peerRaw == myId && ownerId != myId
            val otherId = when {
                ownerId == myId -> peerRaw
                peerRaw == myId -> ownerId
                else -> peerRaw
            }
            val isGroup = obj.optBoolean("is_group", false)
            // Group rows have no single peer — dedupe them by their own id.
            val pairKey = if (!isGroup && MediaUrlResolver.isUuid(otherId)) "peer:$otherId" else "id:$id"

            val lastMessageAt = obj.optString("last_message_at", "")
            val lastActivityMillis = parseIsoToMillis(lastMessageAt)

            rows.add(
                DashboardPullRow(
                    id = id,
                    otherId = otherId,
                    isMirrored = isMirrored,
                    pairKey = pairKey,
                    createdAtMillis = parseIsoToMillis(obj.optString("created_at", "")),
                    lastActivityMillis = lastActivityMillis,
                    unreadCount = obj.optInt("unread_count", 0).coerceAtLeast(0),
                    entity = com.example.data.local.ConversationEntity(
                        id = id,
                        peerId = otherId.takeIf { it.isNotBlank() },
                        name = obj.optString("peer_name", "Unknown"),
                        avatarRes = null,
                        initialColor = obj.optLong("peer_avatar_color", 0xFF00A884),
                        lastMessage = obj.optString("last_message", ""),
                        timestamp = lastMessageAt,
                        lastActivityMillis = lastActivityMillis,
                        unreadCount = obj.optInt("unread_count", 0),
                        isPinned = obj.optBoolean("is_pinned", false),
                        hasStatusUpdate = false,
                        isGroup = isGroup,
                        isOnline = false,
                        lastSeenText = "offline",
                        disappearingDuration = obj.optString("disappearing_duration", "OFF"),
                        disappearingUpdatedAtMillis =
                            parseIsoToMillis(obj.optString("disappearing_updated_at", "")).takeIf { it > 0L },
                        isMuted = obj.optBoolean("is_muted", false),
                        isBlocked = obj.optBoolean("is_blocked", false),
                        isArchived = false,
                        requestStatus = requestStatus ?: "accepted",
                        peerAvatarUrl = obj.optStringOrNull("peer_avatar_url")
                    )
                )
            )
        }

        // Group by peer pair; canonical = OLDEST row (created_at asc) — the same
        // convention resolveCanonicalConversationId / resolveOrCreateConversation
        // use when healing mirror pairs on chat open.
        val grouped = rows.groupBy { it.pairKey }
        val losers = ArrayList<String>()
        val keptRows = ArrayList<DashboardPullRow>()
        for (group in grouped.values) {
            val sorted = group.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
            val canonical = sorted.first()
            if (group.size > 1) {
                losers.addAll(group.filter { it.id != canonical.id }.map { it.id })
            }
            // Merge the pair's freshest activity onto the row the list keeps.
            // Task 24: unread is taken from the CANONICAL row only — the server
            // increments/decrements unread on the canonical row exclusively,
            // so the old max(canonical, mirror) merge resurrected a phantom
            // badge from the accept-time mirror snapshot on every pull.
            val mergedActivity = group.maxOf { it.lastActivityMillis }
            val mergedUnread = canonical.unreadCount
            val mergedEntity = if (mergedActivity != canonical.lastActivityMillis ||
                mergedUnread != canonical.unreadCount
            ) {
                canonical.entity.copy(
                    lastActivityMillis = mergedActivity,
                    unreadCount = mergedUnread,
                    lastMessage = group.maxBy { it.lastActivityMillis }.entity.lastMessage
                )
            } else canonical.entity
            keptRows.add(canonical.copy(lastActivityMillis = mergedActivity, unreadCount = mergedUnread, entity = mergedEntity))
        }
        if (losers.isNotEmpty()) {
            conversationDao.deleteConversationsByIds(losers.distinct())
        }

        // Owner-perspective fix: on mirrored rows the server peer_name is MY
        // display name — resolve the REAL other-user name/avatar from profiles.
        val mirroredOtherIds = keptRows.filter { it.isMirrored && MediaUrlResolver.isUuid(it.otherId) }
            .map { it.otherId }.distinct()
        val profilesById = if (mirroredOtherIds.isNotEmpty()) fetchProfiles(mirroredOtherIds) else emptyMap()

        for (row in keptRows) {
            val existing = conversationDao.getConversationByIdOnce(row.id)
            var entity = row.entity.copy(
                // isArchived is a LOCAL-ONLY flag (not part of the server pull).
                // insertConversation is REPLACE — without preserving it, every
                // sync silently un-archived all archived chats.
                isArchived = existing?.isArchived ?: false
            )
            if (row.isMirrored) {
                val profile = profilesById[row.otherId]
                entity = entity.copy(
                    name = profile?.first?.takeIf { it.isNotBlank() }
                        ?: existing?.name?.takeIf { it.isNotBlank() && it != "Unknown" }
                        ?: "Unknown",
                    peerAvatarUrl = profile?.second ?: entity.peerAvatarUrl
                )
            }
            conversationDao.insertConversation(entity)
        }

        // Purge legacy rows: ids that were never real conversation UUIDs
        // (peer-uuid keys, seeded demos) and that this pull does not carry.
        val locals = conversationDao.getAllConversationRowsOnce()
        val legacyIds = locals
            .filter { !MediaUrlResolver.isUuid(it.id) && it.id !in pulledIds }
            .map { it.id }
        if (legacyIds.isNotEmpty()) {
            conversationDao.deleteConversationsByIds(legacyIds)
        }
    }

    /** Batch profiles lookup → (display name, avatar url) per user id. */
    private suspend fun fetchProfiles(
        ids: List<String>
    ): Map<String, Pair<String, String?>> {
        return try {
            when (val res = supabaseClient.getTable(
                "profiles",
                "id=in.(${ids.joinToString(",")})&select=id,full_name,username,avatar_url"
            )) {
                is SupabaseResult.Success -> {
                    val map = HashMap<String, Pair<String, String?>>()
                    for (i in 0 until res.data.length()) {
                        val p = res.data.getJSONObject(i)
                        val fullName = p.optStringOrNull("full_name")
                        val username = p.optStringOrNull("username")
                        val name = fullName ?: username ?: ""
                        if (p.optString("id").isNotBlank()) {
                            map[p.optString("id")] = Pair(name, p.optStringOrNull("avatar_url"))
                        }
                    }
                    map
                }
                is SupabaseResult.Error -> emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Unread user_notifications count for the bell badge. Never throws: an
     *  error (including a 404 while the table migration is still rolling out)
     *  renders as 0. */
    private suspend fun fetchUnreadNotificationsCount(): Int {
        val me = supabaseClient.currentSession?.user?.id ?: return 0
        return try {
            when (val res = supabaseClient.getTable(
                "user_notifications",
                "user_id=eq.$me&is_read=eq.false&select=id"
            )) {
                is SupabaseResult.Success -> res.data.length()
                is SupabaseResult.Error -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /** Re-fetches the unread notifications count for the bell badge. Safe to
     *  call on every dashboard (re)composition — in-flight calls collapse. */
    fun refreshNotificationsUnread() {
        if (notificationsRefreshInFlight) return
        notificationsRefreshInFlight = true
        viewModelScope.launch {
            try {
                _notificationsUnread.value = fetchUnreadNotificationsCount()
            } catch (e: Exception) {
                // Badge is best-effort; never crash the dashboard over it.
            } finally {
                notificationsRefreshInFlight = false
            }
        }
    }

    /** Clears the bell badge immediately after the notifications screen has
     *  marked my rows read server-side (server state stays authoritative —
     *  the next refresh simply re-derives the same value). */
    fun clearNotificationsBadge() {
        _notificationsUnread.value = 0
    }

    /** Parses an ISO-8601 timestamp ("2026-09-07T16:21:39Z") to epoch millis;
     *  returns 0L when unparsable so rows sort deterministically until the
     *  next real message refreshes the value. */
    private fun parseIsoToMillis(iso: String): Long = ChatTimeFormatter.parseIsoToMillis(iso)

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
                if (result is SupabaseResult.Success) {
                    result.data.optJSONArray("conversations")?.let { applyConversationPull(it) }
                } else if (result is SupabaseResult.Error) {
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

    // Entity-backed (not repository-backed) so the chat-list timestamp can be
    // rendered from lastActivityMillis — the entity's ISO `timestamp` string
    // may be raw server UTC or a legacy local "h:mm a" value.
    val conversations: StateFlow<List<DomainConversation>> = combine(
        conversationDao.getAllConversations(),
        _searchQuery,
        _selectedFilter,
        _showArchived
    ) { all, query, filter, showArchived ->
        all.map { entity ->
            entity.toDomain().copy(
                timestamp = ChatTimeFormatter.formatForList(entity.lastActivityMillis, entity.timestamp)
            )
        }.filter { conv ->
            // REQUESTS tab shows ONLY pending message requests (incoming AND
            // outgoing — the canonical sender-owned row carries request_status
            // "pending" on both sides' pulls; ChatScreen renders the
            // Accept/Decline UI for the receiver and the waiting state for the
            // requester). Every other tab keeps the Task-24 contract: pending
            // never mixes into the regular chat list.
            val matchesRequestPolicy = if (filter == ChatFilter.REQUESTS) {
                conv.requestStatus == "pending"
            } else {
                conv.requestStatus != "pending"
            }

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
                ChatFilter.REQUESTS -> true
            }

            matchesRequestPolicy && matchesArchiveFilter && matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tab-independent unread count for the bottom-bar Chats badge. The
    // [conversations] flow bakes the active filter in, so summing it on the
    // Requests tab would drop accepted-chat unread from the badge. This one
    // ignores search/filter/archived toggles and always reflects the real
    // inbox: accepted chats only (pending requests surface in the Requests
    // tab with their own badges, not in the bottom-bar count).
    val totalUnreadCount: StateFlow<Int> = conversationDao.getAllConversations()
        .map { rows ->
            rows.filter { it.requestStatus != "pending" && !it.isArchived }
                .sumOf { it.unreadCount }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    /** True while the Requests tab is active (the screen swaps its empty
     *  state and list source accordingly). */
    val isRequestsTab: Boolean
        get() = _selectedFilter.value == ChatFilter.REQUESTS

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
