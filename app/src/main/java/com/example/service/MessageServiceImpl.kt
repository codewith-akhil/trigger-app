package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.repository.ChatRepositoryImpl
import com.example.di.AppServiceContainer
import com.example.model.DomainMessage
import com.example.model.MessageStatus
import com.example.model.MessageType
import com.example.model.PresenceStatus
import com.example.service.supabase.RealtimeEvent
import com.example.service.supabase.SupabaseClient
import com.example.service.supabase.SupabaseResult
import com.example.util.optStringOrNull
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * MessageServiceImpl — REAL Supabase-backed implementation.
 *
 * - sendMessage: inserts locally (Room) + calls send-message edge function
 * - observeMessages: Room flow + Realtime event collector (live updates)
 * - updateMessageStatus: updates Room + syncs to Supabase
 * - deleteForMe/deleteForEveryone: Room + Supabase sync
 * - toggleReaction: Room + Supabase sync
 * - toggleStarMessage: Room update + Supabase sync
 * - editMessage: edit-message edge function + Room update
 * - togglePinMessage: Room update + Supabase upsert
 * - retryAllFailedMessages: re-calls send-message for all FAILED messages
 * - searchMessagesEx: search-messages edge function (text/media/links/date/...)
 * - syncMessages: sync-messages edge function (pull)
 * - markConversationRead: mark-conversation-read edge function
 * - Realtime: subscribes to messages table inserts/updates/deletes
 */
class MessageServiceImpl(
    private val repository: ChatRepositoryImpl,
    private val presenceService: PresenceService
) : MessageService {

    companion object {
        private const val TAG = "MessageServiceImpl"
        private const val PREFS_NAME = "trigger_chat_prefs"
        private const val KEY_LAST_SYNC_TS = "last_sync_ts"

        /** Watermarks are PER CONVERSATION: a single global watermark made
         *  "open chat A, then chat B" pull B only for messages newer than
         *  A's last pull — B's older history never reached Room. */
        private fun lastSyncKey(conversationId: String) = "last_sync_ts_$conversationId"
        private const val EDIT_WINDOW_MS = 15L * 60 * 1000  // 15 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active realtime subscriptions per conversation
    @Volatile private var currentRealtimeFilter: String? = null
    @Volatile private var realtimeCollectorStarted = false
    @Volatile private var activeConversationId: String? = null

    // Live-location (C7): ChatViewModel plugs a listener in init and clears it
    // in onCleared() — events for live_location_shares carry fresh peer coords.
    @Volatile
    var onLiveLocationEvent: ((RealtimeEvent) -> Unit)? = null

    private val prefs: SharedPreferences? by lazy {
        try {
            AppServiceContainer.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears in-memory per-account state (active Realtime subscriptions).
     * Called by AccountStateManager on logout / account switch so the next
     * account gets fresh subscriptions instead of silently reusing the old
     * ones.
     */
    fun reset() {
        currentRealtimeFilter = null
        realtimeCollectorStarted = false
        activeConversationId = null
        onLiveLocationEvent = null
    }

    /**
     * Canonical-row heal: a pair may hold TWO conversation rows (per-user
     * mirrors for unread/last-read metadata) while messages always live on
     * the OLDEST row ("canonical"). When a chat is opened with a mirror row
     * id (e.g. the receiver's dashboard entry after accepting a request),
     * this resolves the canonical id so every client reads/writes the SAME
     * thread. Offline-safe: returns the input id when the server is unknown.
     */
    suspend fun resolveCanonicalConversationId(conversationId: String): String? {
        if (!MediaUrlResolver.isUuid(conversationId)) return null
        val supabaseClient = AppServiceContainer.supabaseClient
        val me = supabaseClient.currentSession?.user?.id ?: return null

        val self = when (val res = supabaseClient.getTable(
            "conversations", "id=eq.$conversationId&select=id,owner_id,peer_id,peer_name&limit=1"
        )) {
            is SupabaseResult.Success -> res.data.optJSONObject(0) ?: return null
            is SupabaseResult.Error -> return null
        }
        val selfPeerId = self.optString("peer_id", "")
        val selfOwnerId = self.optString("owner_id", "")
        val peer = when {
            selfPeerId.isNotBlank() && selfPeerId != me -> selfPeerId
            selfOwnerId.isNotBlank() && selfOwnerId != me -> selfOwnerId
            else -> null // self-chat (owner = peer = me) — already canonical
        } ?: return conversationId

        val or = "or=(and(owner_id.eq.$me,peer_id.eq.$peer),and(owner_id.eq.$peer,peer_id.eq.$me))"
        return when (val res = supabaseClient.getTable("conversations", "$or&order=created_at.asc&limit=1")) {
            is SupabaseResult.Success -> {
                val row = res.data.optJSONObject(0) ?: return conversationId
                val canonical = row.optString("id", conversationId)
                if (MediaUrlResolver.isUuid(canonical)) {
                    val name = self.optString("peer_name", "").ifBlank { null }
                    repository.ensureConversationRow(canonical, peer, name)
                    if (canonical != conversationId) {
                        // Any local echo stored under the mirror id moves over.
                        repository.rekeyConversationMessages(conversationId, canonical)
                    }
                    canonical
                } else conversationId
            }
            is SupabaseResult.Error -> conversationId
        }
    }

    /** H4: given a peer USER uuid, resolve (or create) the real conversation. */
    override suspend fun resolveOrCreateConversation(peerId: String): String? {
        if (!MediaUrlResolver.isUuid(peerId)) return null

        val supabaseClient = AppServiceContainer.supabaseClient
        val me = supabaseClient.currentSession?.user?.id ?: return null

        // 1. Server lookup in BOTH directions, OLDEST row first. The pair may
        //    hold two mirror rows (per-user metadata) while messages live on
        //    the oldest ("canonical") row — resolving deterministically here
        //    guarantees both clients land on the SAME thread.
        val or = "or=(and(owner_id.eq.$me,peer_id.eq.$peerId),and(owner_id.eq.$peerId,peer_id.eq.$me))"
        when (val res = supabaseClient.getTable("conversations", "$or&select=id&order=created_at.asc&limit=1")) {
            is SupabaseResult.Success -> {
                if (res.data.length() > 0) {
                    val id = res.data.getJSONObject(0).optString("id", "")
                    if (MediaUrlResolver.isUuid(id)) {
                        repository.ensureConversationRow(id, peerId, null)
                        // Re-key anything cached under the legacy peer uuid or
                        // under a mirror-row id onto the canonical id.
                        val cached = repository.getConversationByPeer(peerId)?.id
                        if (cached != null && cached != id) {
                            repository.rekeyConversationMessages(cached, id)
                        } else {
                            repository.rekeyConversationMessages(peerId, id)
                        }
                        return id
                    }
                }
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "conversation lookup failed: ${res.message}")
                // Offline fallback: trust the local cache (v6 peerId column).
                repository.getConversationByPeer(peerId)?.let { return it.id }
            }
        }

        // 3. Create (self-chats allowed: owner = peer = me).
        // Task 24: this client-side insert previously wrote request_status
        // "accepted", bypassing the whole request/3-message gate whenever the
        // contacts table held an entry without a live conversation row. New
        // conversations now start PENDING like every server-created one (the
        // self-chat needs no request). The pair lookup above plus the
        // (owner_id,peer_id) unique index keep this from duplicating rows.
        val requestStatus = if (peerId == me) "accepted" else "pending"
        val insert = supabaseClient.insertRecord(
            "conversations",
            org.json.JSONObject()
                .put("owner_id", me)
                .put("peer_id", peerId)
                .put("request_status", requestStatus)
                .put("is_group", false)
        )
        return when (insert) {
            is SupabaseResult.Success -> {
                val id = insert.data.optString("id", "")
                if (MediaUrlResolver.isUuid(id)) {
                    repository.ensureConversationRow(id, peerId, null)
                    id
                } else null
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "conversation create failed: ${insert.message}")
                null
            }
        }
 }

    override fun observeMessages(conversationId: String): Flow<List<DomainMessage>> {
        // Subscribe to Realtime for this conversation if not already
        ensureRealtimeSubscription(conversationId)
        // Return the Room flow (Room is the local cache, Realtime updates it)
        return repository.getMessages(conversationId)
    }

    override fun ensureRealtimeSubscription(conversationId: String) {
        activeConversationId = conversationId
        val filter = "conversation_id=eq.$conversationId"

        // RE-FILTER when the open chat changes: the websocket pinpoints ONE
        // conversation_id, and the previous early-return left the socket
        // filtered to the LAST chat — returning to an earlier chat silently
        // lost all of its live messages (incl. after every reconnect).
        if (currentRealtimeFilter != filter) {
            currentRealtimeFilter = filter
            AppServiceContainer.supabaseClient.connectRealtime(
                tables = listOf(
                    "public.messages",
                    "public.conversations",
                    "public.user_presences",
                    "public.live_location_shares"
                ),
                filter = filter
            )
        }

        // ONE collector for the process — observeMessages() used to add two
        // immortal collectors PER OPEN, leaking coroutines on every chat.
        if (!realtimeCollectorStarted) {
            realtimeCollectorStarted = true

            // Live-location (C7): live_location_shares events are forwarded to
            // the ChatViewModel listener registered on [onLiveLocationEvent].
            scope.launch {
                AppServiceContainer.supabaseClient.realtimeEvents.collect { event ->
                    try {
                        when (event.table) {
                            "messages" -> handleRealtimeMessageEvent(event)
                            "user_presences" -> handleRealtimePresenceEvent(event)
                            "live_location_shares" -> onLiveLocationEvent?.invoke(event)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to handle realtime event: ${e.message}")
                    }
                }
            }

            // Reconnect signal — pull what the CURRENT chat missed during the
            // WebSocket gap, using ITS OWN watermark.
            scope.launch {
                AppServiceContainer.supabaseClient.reconnectSignals.collect {
                    val conv = activeConversationId ?: return@collect
                    Log.i(TAG, "Realtime reconnected — pulling missed messages for $conv")
                    try {
                        val lastTs = prefs?.getLong(lastSyncKey(conv), 0L) ?: 0L
                        syncMessages(conversationId = conv, sinceTs = lastTs)
                    } catch (e: Exception) {
                        Log.w(TAG, "Post-reconnect sync failed: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun handleRealtimeMessageEvent(event: RealtimeEvent) {
        val record = event.record ?: return
        // Room caches ALL conversations — apply events wherever they belong
        // instead of dropping everything not matching one captured id.

        when (event.eventType) {
            "INSERT" -> {
                // New message from the other user — insert into Room if it's incoming
                val senderId = record.optString("sender_id", "")
                val currentUserId = AppServiceContainer.supabaseClient.currentUser?.id ?: ""
                if (senderId != currentUserId) {
                    val domainMsg = mapSupabaseToDomain(record, isOutgoing = false)
                    repository.insertMessage(domainMsg)
                    // Chat-list completeness: a conversation the local DB has
                    // never seen must surface IMMEDIATELY (previously it stayed
                    // invisible until the next sync-conversations pull, which is
                    // why "some users are not in the list"). ensureConversationRow
                    // is a no-op when the row exists; the preview update then
                    // reorders the list either way.
                    try {
                        val senderName = record.optStringOrNull("sender_name")
                        repository.ensureConversationRow(
                            id = domainMsg.conversationId,
                            peerId = senderId,
                            name = senderName
                        )
                        val preview = when (domainMsg.type) {
                            MessageType.IMAGE -> "📷 Photo"
                            MessageType.VIDEO -> "🎥 Video"
                            MessageType.AUDIO -> "🎤 Voice message"
                            MessageType.DOCUMENT -> "📄 ${domainMsg.fileName ?: "Document"}"
                            MessageType.LOCATION -> "📍 Location"
                            MessageType.CONTACT -> "👤 ${domainMsg.contactName ?: "Contact"}"
                            MessageType.CALL_LOG -> domainMsg.text
                            else -> domainMsg.text
                        }
                        repository.updateConversationLastMessage(
                            id = domainMsg.conversationId,
                            preview = preview,
                            timestamp = domainMsg.timestamp,
                            timestampMillis = domainMsg.timestampMillis
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "realtime conversation ensure failed: ${e.message}")
                    }
                    // Auto-mark as DELIVERED (we received it) — locally first
                    // (instant double-tick on our own copy), then persist it on
                    // the server so the SENDER's device flips to ✓✓ too.
                    repository.updateMessageStatus(domainMsg.id, MessageStatus.DELIVERED)
                    scheduleDeliveredReceipt(domainMsg.conversationId)
                }
            }
            "UPDATE" -> {
                // Status change (e.g. READ) or edit
                val msgId = record.optString("id", "")
                val status = record.optString("status", "")
                val readAt = record.optString("read_at", "")
                if (readAt != "null" && readAt.isNotEmpty()) {
                    repository.updateMessageStatus(msgId, MessageStatus.READ)
                } else if (status == "DELIVERED") {
                    repository.updateMessageStatus(msgId, MessageStatus.DELIVERED)
                }
                // Only treat as an EDIT when the server row actually carries
                // edited_at. Read receipts / pin / star also UPDATE the row
                // (with unchanged text) and previously stamped every one of
                // them as "edited" on the receiving device.
                if (!record.isNull("edited_at")) {
                    val text = record.optString("text", "")
                    if (text.isNotEmpty()) {
                        repository.updateMessageText(msgId, text)
                    }
                }
                // Update pinned + edited_at + starred flags
                if (!record.isNull("is_pinned")) {
                    val pinned = record.optBoolean("is_pinned", false)
                    repository.setMessagePinned(msgId, pinned)
                }
                if (!record.isNull("is_starred")) {
                    val starred = record.optBoolean("is_starred", false)
                    repository.setMessageStarred(msgId, starred)
                }
                // Reactions — the DB trigger denormalizes message_reactions
                // into messages.reactions; without parsing this the recipient
                // NEVER saw any reaction (not live, not on sync).
                if (record.has("reactions") && !record.isNull("reactions")) {
                    val reactionsObj = when (val raw = record.get("reactions")) {
                        is JSONObject -> raw
                        is String -> try { JSONObject(raw) } catch (e: Exception) { null }
                        else -> null
                    }
                    repository.updateMessageReactions(msgId, reactionsJsonToRaw(reactionsObj))
                }
                // Check if deleted for everyone
                if (record.optBoolean("is_deleted_for_everyone", false)) {
                    repository.deleteForEveryone(msgId)
                }
                // View-once opened by the RECEIVER — propagate to sender's UI
                if (!record.isNull("is_viewed") && record.optBoolean("is_viewed", false)) {
                    repository.markMessageViewed(msgId)
                }
            }
            "DELETE" -> {
                val msgId = record.optString("id", "")
                repository.deleteForMe(msgId)
            }
        }
    }

    /**
     * Local expiry for typing/recording states: if the peer's process dies
     * before typing_until=null, NO further event arrives and the receiver
     * showed "typing…" forever. One job per (user,state) — a newer event
     * replaces the previous schedule.
     */
    private val presenceExpiryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Delivered-receipt batching: one mark-messages-delivered call per
     *  conversation per 600 ms window instead of one per message. */
    private val deliveredReceiptJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Persist DELIVERED server-side for the caller's incoming messages in a
     *  conversation (debounced). The sender's realtime UPDATE then flips the
     *  tick to ✓✓ — previously the receipt was local-only so the sender NEVER
     *  saw a double tick. */
    private fun scheduleDeliveredReceipt(conversationId: String) {
        if (!com.example.service.MediaUrlResolver.isUuid(conversationId)) return
        deliveredReceiptJobs[conversationId]?.cancel()
        deliveredReceiptJobs[conversationId] = scope.launch {
            kotlinx.coroutines.delay(600)
            deliveredReceiptJobs.remove(conversationId)
            try {
                AppServiceContainer.supabaseClient.invokeFunction(
                    "mark-messages-delivered",
                    org.json.JSONObject().put("conversationId", conversationId)
                )
            } catch (e: Exception) {
                Log.w(TAG, "mark-messages-delivered failed: ${e.message}")
            }
        }
    }

    /** Called by ChatViewModel on chat open / history pull — catches up the
     *  DELIVERED receipt for everything already sitting in Room. */
    suspend fun markConversationDelivered(conversationId: String) {
        scheduleDeliveredReceipt(conversationId)
    }

    private fun schedulePresenceExpiry(userId: String, untilMs: Long, state: PresenceStatus) {
        val key = "$userId:${state.name}"
        presenceExpiryJobs.remove(key)?.cancel()
        presenceExpiryJobs[key] = scope.launch {
            val delayMs = (untilMs - System.currentTimeMillis()).coerceIn(0L, 30_000L)
            kotlinx.coroutines.delay(delayMs)
            (presenceService as? PresenceServiceImpl)?.setContactPresence(
                userId, PresenceStatus.ONLINE, "online"
            )
            presenceExpiryJobs.remove(key)
        }
    }

    private fun handleRealtimePresenceEvent(event: RealtimeEvent) {
        val record = event.record ?: return
        val isOnline = record.optBoolean("is_online", false)
        val lastSeen = record.optString("last_seen_at", "")
        val userId = record.optString("user_id", "")
        if (presenceService is PresenceServiceImpl) {
            // Typing indicator — short-lived typing_until expiry set by the
            // update-presence edge function. previously DEAD: the server never
            // persisted typing state and the client ignored it.
            val typingUntil = record.optString("typing_until", "")
            if (typingUntil.isNotBlank() && typingUntil != "null") {
                val untilMs = parseIsoToMillis(typingUntil)
                if (untilMs > System.currentTimeMillis()) {
                    presenceService.setContactPresence(userId, PresenceStatus.TYPING, "typing…")
                    schedulePresenceExpiry(userId, untilMs, PresenceStatus.TYPING)
                    return
                }
            }
            val recordingUntil = record.optString("recording_until", "")
            if (recordingUntil.isNotBlank() && recordingUntil != "null") {
                val untilMs = parseIsoToMillis(recordingUntil)
                if (untilMs > System.currentTimeMillis()) {
                    presenceService.setContactPresence(userId, PresenceStatus.RECORDING_AUDIO, "recording audio…")
                    schedulePresenceExpiry(userId, untilMs, PresenceStatus.RECORDING_AUDIO)
                    return
                }
            }
            val status = if (isOnline) PresenceStatus.ONLINE else PresenceStatus.OFFLINE
            // Privacy: when the peer hides last seen (or no accepted chat), the
            // subtitle stays blank — realtime events must not leak the state.
            if ((presenceService as? PresenceServiceImpl)?.isHidden(userId) == true) {
                presenceService.setContactPresence(userId, PresenceStatus.OFFLINE, "")
                return
            }
            // WhatsApp-style formatting: "Last seen 03:02" (24 h, device zone).
            val text = if (isOnline) "online" else com.example.util.LastSeenFormatter.format(lastSeen)
            presenceService.setContactPresence(userId, status, text)
        }
    }

    private fun mapSupabaseToDomain(record: JSONObject, isOutgoing: Boolean): DomainMessage {
        val tsMillis = if (!record.isNull("timestamp_millis")) {
            record.optLong("timestamp_millis", System.currentTimeMillis())
        } else {
            // Try to parse created_at ISO string
            val createdAt = record.optString("created_at", "")
            parseIsoToMillis(createdAt)
        }
        // Task 24: private buckets — the server row carries the BARE OBJECT
        // PATH in media_url/media_thumbnail plus the bucket in media_bucket.
        // Map ALL THREE so Room rows can re-sign on read. Legacy rows (public
        // URL form) get their path derived so they keep resolving too.
        val rawMediaUrl = record.optString("media_url", null)
        val rawThumb = record.optString("media_thumbnail", null)
        val rawBucket = record.optString("media_bucket", null)?.takeIf { it.isNotBlank() }
        val derivedMediaPath = MediaUrlResolver.objectPathOf(rawMediaUrl, rawBucket, null)
        return DomainMessage(
            id = record.optString("id", ""),
            conversationId = record.optString("conversation_id", ""),
            senderId = record.optString("sender_id", ""),
            senderName = record.optString("sender_name", ""),
            type = try {
                MessageType.valueOf(record.optString("type", "TEXT"))
            } catch (e: Exception) { MessageType.TEXT },
            text = record.optString("text", ""),
            mediaUrl = rawMediaUrl,
            mediaThumbnail = rawThumb,
            mediaBucket = rawBucket,
            mediaPath = derivedMediaPath,
            fileName = record.optString("file_name", null),
            fileSize = record.optLong("file_size", 0L),
            mediaDurationSec = record.optInt("media_duration_sec", 0),
            isViewOnce = record.optBoolean("is_view_once", false),
            isViewed = record.optBoolean("is_viewed", false),
            replyToId = record.optString("reply_to_id", null),
            status = try {
                MessageStatus.valueOf(record.optString("status", "SENT"))
            } catch (e: Exception) { MessageStatus.SENT },
            timestamp = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(tsMillis)),
            timestampMillis = tsMillis,
            isOutgoing = isOutgoing,
            isStarred = record.optBoolean("is_starred", false),
            isPinned = record.optBoolean("is_pinned", false),
            isDeletedForEveryone = record.optBoolean("is_deleted_for_everyone", false),
            editedAt = if (!record.isNull("edited_at")) parseIsoToMillis(record.optString("edited_at")) else null,
            seq = record.optLong("seq", 0L),
            idempotencyKey = record.optString("idempotency_key", null),
            locationLatitude = record.optString("location_lat", null)?.toDoubleOrNull(),
            locationLongitude = record.optString("location_lng", null)?.toDoubleOrNull(),
            locationAddress = record.optString("location_address", null),
            locationLiveMinutes = if (!record.isNull("location_live_minutes"))
                record.optInt("location_live_minutes").takeIf { it > 0 } else null,
            locationComment = record.optString("location_comment", null),
            reactions = parseReactionsFromServer(record),
            contactName = record.optString("contact_name", null),
            contactPhone = record.optString("contact_phone", null),
            callType = record.optString("call_type", null)?.takeIf { it.isNotBlank() && it != "null" },
            callDurationSec = record.optInt("call_duration_sec", 0)
        )
    }

    /**
     * messages.reactions jsonb ("emoji": {"count": n, "users": [uuid…]}) →
     * the Room raw form "emoji:count:me(true|false);…" — userReacted is
     * computed against the CURRENT user (a per-viewer fact the server cannot
     * store).
     */
    private fun parseReactionsFromServer(record: JSONObject): List<com.example.model.MessageReaction> {
        val obj = record.optJSONObject("reactions") ?: return emptyList()
        val myId = AppServiceContainer.supabaseClient.currentUser?.id
        val out = mutableListOf<com.example.model.MessageReaction>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val emoji = keys.next()
            val entry = obj.optJSONObject(emoji) ?: continue
            val count = entry.optInt("count", 0)
            val users = entry.optJSONArray("users")
            var reacted = false
            if (users != null && myId != null) {
                for (i in 0 until users.length()) {
                    if (users.optString(i) == myId) {
                        reacted = true
                        break
                    }
                }
            }
            if (count > 0) {
                out.add(com.example.model.MessageReaction(emoji, count, reacted))
            }
        }
        return out
    }

    /** Domain/Room bridge: List<MessageReaction> → "emoji:count:me;…" raw. */
    private fun reactionsJsonToRaw(obj: JSONObject?): String {
        if (obj == null) return ""
        val myId = AppServiceContainer.supabaseClient.currentUser?.id
        val parts = mutableListOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val emoji = keys.next()
            val entry = obj.optJSONObject(emoji) ?: continue
            val count = entry.optInt("count", 0)
            if (count <= 0) continue
            val users = entry.optJSONArray("users")
            var reacted = false
            if (users != null && myId != null) {
                for (i in 0 until users.length()) {
                    if (users.optString(i) == myId) {
                        reacted = true
                        break
                    }
                }
            }
            parts.add("$emoji:$count:$reacted")
        }
        return parts.joinToString(";")
    }

    private fun parseIsoToMillis(iso: String): Long {
        if (iso.isBlank() || iso == "null") return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                // Try without trailing Z
                java.time.LocalDateTime.parse(iso.replace(" ", "T"))
                    .toEpochSecond(java.time.ZoneOffset.UTC) * 1000
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    override suspend fun sendMessage(message: DomainMessage, peerId: String?, peerName: String?) {
        // 1. Insert locally (Room) immediately for instant UI
        repository.sendMessage(message, isOnline = true)

        // 2. Send to Supabase via send-message edge function with idempotency_key
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = buildMessagePayload(message, peerId, peerName)

        val result = supabaseClient.invokeFunction("send-message", payload)
        when (result) {
            is SupabaseResult.Success -> {
                // Message sent successfully — update status to SENT
                repository.updateMessageStatus(message.id, MessageStatus.SENT)
                // Update the seq from the server response if provided
                val data = result.data
                val msgObj = if (data.has("message")) data.getJSONObject("message") else data
                if (msgObj != null && msgObj.has("seq")) {
                    val seq = msgObj.optLong("seq", 0L)
                    if (seq > 0) repository.updateMessageSeq(message.id, seq)
                }
                // H4 self-heal: the server ALWAYS returns the real conversation
                // uuid. If we sent under a legacy peer-uuid key, adopt the real
                // one locally (re-key Room rows + ensure the conversation row).
                syncConversationIdentityFromResponse(message.conversationId, peerId, peerName, msgObj)
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "send-message failed: ${result.message}")
                // Mark as FAILED so the user can retry (offline queue)
                repository.updateMessageStatus(message.id, MessageStatus.FAILED)
            }
        }
    }

    /**
     * H4 self-heal: compares the server's conversation_id with the id we used
     * locally and re-keys the local cache when they diverge (legacy rows keyed
     * by the peer user uuid).
     */
    private suspend fun syncConversationIdentityFromResponse(
        localConversationId: String,
        peerId: String?,
        peerName: String?,
        msgObj: JSONObject?
    ) {
        try {
            val serverConvId = msgObj?.optString("conversation_id", "") ?: ""
            if (MediaUrlResolver.isUuid(serverConvId) && serverConvId != localConversationId) {
                repository.rekeyConversationMessages(localConversationId, serverConvId)
            }
            if (MediaUrlResolver.isUuid(serverConvId)) {
                val name = peerName?.takeIf { it.isNotBlank() }
                    ?: repository.getConversationByIdOnce(serverConvId)?.name
                repository.ensureConversationRow(serverConvId, peerId, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "conversation identity sync failed: ${e.message}")
        }
    }

    /**
     * Builds the send-message payload. Shared by sendMessage() and
     * completeMediaUpload() so both paths always agree on the wire format.
     */
    private fun buildMessagePayload(
        message: DomainMessage,
        peerId: String?,
        peerName: String?
    ): JSONObject {
        // Retry-stable idempotency: the message id IS the retry identity (the
        // same Room row is retried). A fresh random key per attempt made every
        // retry insert a DUPLICATE server row for callers that don't set an
        // explicit key (e.g. the CALL_LOG path).
        val idempotencyKey = message.idempotencyKey ?: message.id
        return JSONObject().apply {
            put("conversation_id", message.conversationId)
            put("type", message.type.name)
            put("text", message.text)
            put("timestamp_millis", message.timestampMillis)
            put("idempotency_key", idempotencyKey)
            // peer_id + peer_name: the edge function auto-creates a conversation
            // if conversation_id doesn't exist in Supabase. This is critical for
            // first-time chats where the app only has the peer's user UUID.
            if (!peerId.isNullOrEmpty()) put("peer_id", peerId)
            if (!peerName.isNullOrEmpty()) put("peer_name", peerName)
            if (message.mediaUrl != null) put("media_url", message.mediaUrl)
            if (message.mediaBucket != null) put("media_bucket", message.mediaBucket)
            if (message.mediaThumbnail != null) put("media_thumbnail", message.mediaThumbnail)
            if (message.fileName != null) put("file_name", message.fileName)
            if (message.fileSize > 0) put("file_size", message.fileSize)
            if (message.mediaDurationSec > 0) put("media_duration_sec", message.mediaDurationSec)
            if (message.isViewOnce) put("is_view_once", true)
            if (message.replyToId != null) put("reply_to_id", message.replyToId)
            if (message.locationLatitude != null) put("location_lat", message.locationLatitude)
            if (message.locationLongitude != null) put("location_lng", message.locationLongitude)
            if (message.locationAddress != null) put("location_address", message.locationAddress)
            if (message.locationLiveMinutes != null) put("location_live_minutes", message.locationLiveMinutes)
            if (message.locationComment != null) put("location_comment", message.locationComment)
            if (message.contactName != null) put("contact_name", message.contactName)
            if (message.contactPhone != null) put("contact_phone", message.contactPhone)
            // Call history persistence — server stores messages.call_type /
            // call_duration_sec so both participants keep the call record.
            if (message.type == MessageType.CALL_LOG) {
                message.callType?.let { put("call_type", it) }
                if (message.callDurationSec > 0) put("call_duration_sec", message.callDurationSec)
            }
        }
    }

    override suspend fun stageOutgoingMessage(message: DomainMessage) {
        // Local insert ONLY — the server call happens in completeMediaUpload
        // once the upload finishes and we hold a recipient-accessible URL.
        repository.sendMessage(message, isOnline = true)
    }

    override suspend fun completeMediaUpload(task: com.example.model.UploadTask) {
        val msg = repository.getMessageById(task.messageId) ?: return
        val finalUrl = task.mediaUrl
        if (finalUrl.isNullOrEmpty()) {
            Log.e(TAG, "completeMediaUpload: task ${task.id} has no final URL")
            repository.updateMessageStatus(task.messageId, MessageStatus.FAILED)
            return
        }

        // 1. Write the real remote URL + storage coordinates back onto the
        //    local row so the sender's own bubble (and any FUTURE re-sign)
        //    can always rebuild the URL.
        repository.updateMessageMediaFull(task.messageId, finalUrl, task.bucket, task.mediaPath)

        // 2. NOW create the server row — with the remote URL, never content://
        //    The thumbnail becomes the REMOTE poster-frame URL (the local
        //    cache path is useless to the receiver).
        val updated = msg.copy(
            mediaUrl = finalUrl,
            mediaBucket = task.bucket,
            mediaPath = task.mediaPath,
            mediaThumbnail = task.thumbnailUrl ?: msg.mediaThumbnail
        )
        val payload = buildMessagePayload(updated, task.peerId, task.peerName)
        val result = AppServiceContainer.supabaseClient.invokeFunction("send-message", payload)
        when (result) {
            is SupabaseResult.Success -> {
                repository.updateMessageStatus(task.messageId, MessageStatus.SENT)
                val data = result.data
                val msgObj = if (data.has("message")) data.getJSONObject("message") else data
                val seq = msgObj?.optLong("seq", 0L) ?: 0L
                if (seq > 0) repository.updateMessageSeq(task.messageId, seq)
                syncConversationIdentityFromResponse(msg.conversationId, task.peerId, task.peerName, msgObj)
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "post-upload send-message failed: ${result.message}")
                repository.updateMessageStatus(task.messageId, MessageStatus.FAILED)
            }
        }
    }

    override suspend fun markMediaMessageFailed(messageId: String, reason: String) {
        Log.e(TAG, "Media message $messageId failed: $reason")
        repository.updateMessageStatus(messageId, MessageStatus.FAILED)
    }

    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        repository.updateMessageStatus(messageId, status)
        // Sync to Supabase (update the messages table)
        // This is handled by Realtime — when the other user marks as read,
        // we receive the UPDATE event and update locally.
    }

    override suspend fun toggleReaction(messageId: String, emoji: String) {
        repository.toggleReaction(messageId, emoji)
        // Sync to Supabase message_reactions table via toggle-reaction edge function
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", messageId)
            put("emoji", emoji)
        }
        supabaseClient.invokeFunction("toggle-reaction", payload)
    }

    override suspend fun markViewOnceOpened(messageId: String) {
        repository.markViewOnceAsViewed(messageId)
        // Best-effort server sync so the SENDER sees "Opened" (via Realtime
        // UPDATE) and the view-once state survives reinstall/other devices.
        // Requires the mark-view-once-opened edge function to be deployed;
        // failure is non-fatal (local mark already applied).
        runCatching {
            AppServiceContainer.supabaseClient.invokeFunction(
                "mark-view-once-opened",
                org.json.JSONObject().put("message_id", messageId)
            )
        }.onFailure { Log.w(TAG, "view-once sync failed: ${it.message}") }
    }

    override suspend fun deleteForMe(messageId: String) {
        repository.deleteForMe(messageId)
        // Local-only delete (the message stays on the server for the other user)
    }

    override suspend fun deleteForEveryone(messageId: String) {
        repository.deleteForEveryone(messageId)
        // Sync to Supabase — call delete-message edge function
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", messageId)
            put("action", "delete_for_everyone")
        }
        supabaseClient.invokeFunction("delete-message", payload)
    }

    override suspend fun clearChat(conversationId: String) {
        repository.clearChat(conversationId)
        // Local-only clear (messages stay on the server for the other user)
    }

    override fun searchMessages(conversationId: String, query: String): Flow<List<DomainMessage>> {
        // Escape LIKE wildcards — a "%"/"_" query previously matched EVERYTHING.
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return repository.searchMessages(conversationId, escaped)
    }

    override fun getMediaMessages(conversationId: String): Flow<List<DomainMessage>> {
        return repository.getMediaMessages(conversationId)
    }

    override fun getDocumentMessages(conversationId: String): Flow<List<DomainMessage>> {
        return repository.getDocumentMessages(conversationId)
    }

    override suspend fun retryFailedMessage(messageId: String) {
        // Re-call send-message with the same message data
        val msg = repository.getMessageById(messageId) ?: return
        val isMedia = msg.type in listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.DOCUMENT)
        if (isMedia) {
            // Media rows whose upload never completed still hold a local
            // content:// preview (or null) as mediaUrl — re-SENDING would
            // store a dead URL on the server. Their retry path is a RE-UPLOAD
            // (the upload task streams the local file again, then the normal
            // completeMediaUpload creates the server row).
            // Task 24: mediaUrl is a BARE OBJECT PATH ("uid/uuid.ext") once
            // the upload completed — that is NOT a local file. Decide by
            // checking the file system, and when the storage coordinates are
            // already known, skip the re-upload and go straight to re-send
            // (the idempotency key prevents a duplicate server row).
            val rawMediaUrl = msg.mediaUrl?.takeIf { it.isNotBlank() && !it.startsWith("http") }
            val isLocalFile = rawMediaUrl != null && java.io.File(rawMediaUrl).exists()
            val alreadyUploaded = !isLocalFile &&
                !msg.mediaPath.isNullOrBlank() && !msg.mediaBucket.isNullOrBlank()
            if (!isLocalFile && !alreadyUploaded) {
                // Nothing local to re-upload and no upload ever completed:
                // either the source file is gone (re-send would store a dead
                // URL) or the row never had media.
                if (msg.mediaUrl.isNullOrBlank()) {
                    Log.w(TAG, "retryFailedMessage: media message $messageId has no local source — cannot re-upload")
                    repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                    return
                }
            } else if (isLocalFile) {
                repository.updateMessageStatus(messageId, MessageStatus.SENDING)
                val (realConvId, realPeerId) = resolveConversationForRetry(msg.conversationId)
                val conv = repository.getConversationByIdOnce(realConvId)
                val task = com.example.model.UploadTask(
                    id = "upload_$messageId",
                    messageId = messageId,
                    conversationId = realConvId,
                    fileName = msg.fileName ?: "media_${System.currentTimeMillis()}",
                    fileType = msg.type,
                    totalBytes = msg.fileSize,
                    filePath = rawMediaUrl!!, // verified above
                    mimeType = null, // resolved from the extension at upload time
                    thumbnailPath = msg.mediaThumbnail?.takeIf {
                        !it.startsWith("http") && java.io.File(it).exists()
                    },
                    peerId = realPeerId,
                    peerName = conv?.name
                )
                AppServiceContainer.uploadService.enqueueUpload(task)
                return
            }
            // alreadyUploaded (or plain remote-URL re-send) → fall through to
            // the normal send path below.
        }
        repository.updateMessageStatus(messageId, MessageStatus.SENDING)
        // H4: route by the REAL conversation uuid + the conversation's peer —
        // previously the conversationId was blindly passed as peer_id and only
        // worked because the server silently self-healed the mismatch.
        val (realConvId, realPeerId) = resolveConversationForRetry(msg.conversationId)
        sendMessage(msg.copy(conversationId = realConvId), peerId = realPeerId, peerName = null)
    }

    /**
     * Maps whatever id a legacy row carries (real conversation uuid OR the
     * peer user uuid) to (realConversationId, peerIdOrNull) and re-keys the
     * local rows when the legacy key was the peer uuid.
     */
    private suspend fun resolveConversationForRetry(idOrPeer: String): Pair<String, String?> {
        // Already the real conversation id?
        val conv = repository.getConversationByIdOnce(idOrPeer)
        if (conv != null) return Pair(idOrPeer, conv.peerId)
        // Legacy: row keyed by the peer user uuid — find the conversation row.
        val byPeer = repository.getConversationByPeer(idOrPeer)
        if (byPeer != null) {
            repository.rekeyConversationMessages(idOrPeer, byPeer.id)
            return Pair(byPeer.id, idOrPeer)
        }
        // Unknown — let the server find-or-create by treating it as a peer.
        return Pair(idOrPeer, idOrPeer)
    }

    /** Local-first outbox flush (Task 24). Stale = outgoing SENDING/FAILED
     *  older than 2 minutes (a fresh SENDING row is just an in-flight send —
     *  retrying it would race the live path). Each retry goes through
     *  [retryFailedMessage], which now distinguishes local files from bare
     *  object paths and relies on the stable idempotency key server-side. */
    override suspend fun retryPendingOutbox(): Int {
        val cutoff = System.currentTimeMillis() - 2 * 60 * 1000L
        val stale = try {
            repository.getStaleOutgoingMessages(cutoff)
        } catch (e: Exception) {
            Log.w(TAG, "outbox query failed: ${e.message}")
            return 0
        }
        if (stale.isEmpty()) return 0
        Log.i(TAG, "Outbox flush: retrying ${stale.size} stale outgoing message(s)")
        var retried = 0
        stale.forEach { msg ->
            try {
                retryFailedMessage(msg.id)
                retried++
            } catch (e: Exception) {
                Log.w(TAG, "outbox retry failed for ${msg.id}: ${e.message}")
            }
        }
        return retried
    }

    override suspend fun retryAllFailedMessages() {
        val failed = repository.getAllFailedMessages()
        if (failed.isEmpty()) return
        Log.i(TAG, "Retrying ${failed.size} failed messages")
        failed.forEach { msg ->
            try {
                val isMedia = msg.type in listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.DOCUMENT)
                if (isMedia && (msg.mediaUrl.isNullOrBlank() || !msg.mediaUrl.startsWith("http"))) {
                    return@forEach  // needs re-upload, not re-send
                }
                val (realConvId, realPeerId) = resolveConversationForRetry(msg.conversationId)
                sendMessage(msg.copy(conversationId = realConvId), peerId = realPeerId, peerName = null)
            } catch (e: Exception) {
                Log.w(TAG, "Retry failed for ${msg.id}: ${e.message}")
            }
        }
    }

    override suspend fun forwardMessage(message: DomainMessage, targetConversationIds: List<String>) {
        // Insert locally for each target
        val currentTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val localCopyIds = mutableListOf<String>()
        targetConversationIds.forEach { targetId ->
            val forwardedMsg = message.copy(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = targetId,
                timestamp = currentTime,
                timestampMillis = System.currentTimeMillis(),
                isOutgoing = true,
                status = MessageStatus.SENDING,
                reactions = emptyList(),
                idempotencyKey = java.util.UUID.randomUUID().toString()
            )
            localCopyIds.add(forwardedMsg.id)
            repository.sendMessage(forwardedMsg, true)
        }

        // Sync to Supabase via forward-message edge function
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", message.id)
            put("target_conversation_ids", org.json.JSONArray(targetConversationIds))
        }
        val result = supabaseClient.invokeFunction("forward-message", payload)
        when (result) {
            is SupabaseResult.Success -> {
                // The edge function created the server-side rows — flip the
                // local copies out of SENDING (previously stuck forever).
                localCopyIds.forEach { id ->
                    repository.updateMessageStatus(id, MessageStatus.SENT)
                }
            }
            is SupabaseResult.Error -> {
                localCopyIds.forEach { id ->
                    repository.updateMessageStatus(id, MessageStatus.FAILED)
                }
            }
        }
    }

    override suspend fun toggleStarMessage(messageId: String) {
        // Actually toggle star in Room
        repository.toggleStar(messageId)
        // Sync to Supabase
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", messageId)
        }
        supabaseClient.invokeFunction("toggle-star-message", payload)
    }

    override suspend fun editMessage(messageId: String, newText: String) {
        val msg = repository.getMessageById(messageId) ?: run {
            Log.w(TAG, "editMessage: message not found $messageId")
            return
        }
        val currentUserId = AppServiceContainer.supabaseClient.currentUser?.id ?: ""
        // Client-side guard: only sender can edit
        if (msg.isOutgoing.not() && msg.senderId != currentUserId && msg.senderId != "me") {
            Log.w(TAG, "editMessage: only the sender can edit")
            return
        }
        // Client-side guard: only TEXT messages
        if (msg.type != MessageType.TEXT) {
            Log.w(TAG, "editMessage: only TEXT messages can be edited")
            return
        }
        // Client-side guard: within 15 minutes
        val sinceEdit = System.currentTimeMillis() - msg.timestampMillis
        if (sinceEdit > EDIT_WINDOW_MS) {
            Log.w(TAG, "editMessage: outside 15-minute window")
            return
        }

        // Optimistically update Room
        repository.updateMessageText(messageId, newText)

        // Call edge function
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", messageId)
            put("new_text", newText)
        }
        val result = supabaseClient.invokeFunction("edit-message", payload)
        when (result) {
            is SupabaseResult.Success -> Log.i(TAG, "editMessage: success")
            is SupabaseResult.Error -> {
                Log.e(TAG, "editMessage failed: ${result.message}")
                // Revert text on failure (best-effort)
                repository.updateMessageText(messageId, msg.text)
            }
        }
    }

    override suspend fun togglePinMessage(messageId: String) {
        // Toggle locally
        repository.togglePin(messageId)
        // Sync to Supabase via pin-message edge function
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", messageId)
        }
        supabaseClient.invokeFunction("pin-message", payload)
    }

    override suspend fun searchMessagesEx(
        conversationId: String,
        query: String,
        searchType: String,
        dateFrom: String?,
        dateTo: String?
    ): List<DomainMessage> {
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("conversation_id", conversationId)
            put("query", query)
            put("search_type", searchType)
            if (dateFrom != null) put("date_from", dateFrom)
            if (dateTo != null) put("date_to", dateTo)
            put("limit", 50)
        }
        val result = supabaseClient.invokeFunction("search-messages", payload)
        return when (result) {
            is SupabaseResult.Success -> {
                val arr: JSONArray = if (result.data.has("results")) {
                    result.data.getJSONArray("results")
                } else {
                    JSONArray()
                }
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    DomainMessage(
                        id = obj.optString("id", ""),
                        conversationId = conversationId,
                        senderId = obj.optString("sender_id", ""),
                        senderName = "",
                        type = try {
                            MessageType.valueOf(obj.optString("type", "TEXT"))
                        } catch (e: Exception) { MessageType.TEXT },
                        text = obj.optString("text", ""),
                        mediaUrl = obj.optString("media_url", null),
                        fileName = obj.optString("file_name", null),
                        timestampMillis = parseIsoToMillis(obj.optString("created_at", "")),
                        timestamp = obj.optString("created_at", "")
                    )
                }
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "searchMessagesEx failed: ${result.message}")
                emptyList()
            }
        }
    }

    override suspend fun getSharedLinks(conversationId: String): List<SharedLink> {
        val supabaseClient = AppServiceContainer.supabaseClient
        val result = supabaseClient.getTable(
            "shared_links",
            "conversation_id=eq.$conversationId&limit=50&order=created_at.desc"
        )
        return when (result) {
            is SupabaseResult.Success -> {
                (0 until result.data.length()).map { i ->
                    val obj = result.data.getJSONObject(i)
                    SharedLink(
                        messageId = obj.optString("message_id", ""),
                        conversationId = obj.optString("conversation_id", ""),
                        senderId = obj.optString("sender_id", ""),
                        text = obj.optString("text", ""),
                        createdAt = obj.optString("created_at", "")
                    )
                }
            }
            is SupabaseResult.Error -> emptyList()
        }
    }

    override suspend fun reportUser(reportedUserId: String, reason: String) {
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("reported_user_id", reportedUserId)
            put("reason", reason)
        }
        val result = supabaseClient.invokeFunction("report-user", payload)
        when (result) {
            is SupabaseResult.Success -> Log.i(TAG, "reportUser: success")
            is SupabaseResult.Error -> Log.w(TAG, "reportUser failed: ${result.message}")
        }
    }

    /**
     * Task 25 — backward history page via sync-messages action="history".
     * Server-side equivalent of the Room cursor queries: strictly older than
     * the (timestampMillis, seq, id) cursor, newest first, participant RLS.
     * Rows are upserted into Room so the window flow picks them up and a
     * repeated request can never duplicate anything.
     */
    override suspend fun fetchHistoryPage(
        conversationId: String,
        beforeTimestampMillis: Long,
        beforeSeq: Long,
        beforeMessageId: String,
        limit: Int
    ): List<DomainMessage> {
        if (beforeTimestampMillis <= 0L || beforeMessageId.isBlank()) return emptyList()
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("action", "history")
            put("conversationId", conversationId)
            put("beforeTs", beforeTimestampMillis)
            put("beforeSeq", beforeSeq)
            put("beforeId", beforeMessageId)
            put("limit", limit)
        }
        return when (val result = supabaseClient.invokeFunction("sync-messages", payload)) {
            is SupabaseResult.Success -> {
                val arr = result.data.optJSONArray("messages") ?: JSONArray()
                val myId = supabaseClient.currentUser?.id ?: ""
                val out = mutableListOf<DomainMessage>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optString("id", "").isBlank()) continue
                    val domain = mapSupabaseToDomain(
                        obj,
                        isOutgoing = obj.optString("sender_id", "") == myId
                    )
                    repository.insertMessage(domain)
                    out += domain
                }
                out
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "fetchHistoryPage failed: ${result.message}")
                emptyList()
            }
        }
    }

    /** Task 25 — single-row deep fetch for reply navigation (participant RLS). */
    override suspend fun fetchMessageById(conversationId: String, messageId: String): DomainMessage? {
        if (messageId.isBlank()) return null
        val supabaseClient = AppServiceContainer.supabaseClient
        return when (val res = supabaseClient.getTable("messages", "id=eq.$messageId&limit=1")) {
            is SupabaseResult.Success -> {
                val obj = res.data.optJSONObject(0) ?: return null
                val myId = supabaseClient.currentUser?.id ?: ""
                val domain = mapSupabaseToDomain(obj, isOutgoing = obj.optString("sender_id", "") == myId)
                repository.insertMessage(domain)
                domain
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "fetchMessageById failed: ${res.message}")
                null
            }
        }
    }

    override suspend fun syncMessages(conversationId: String?, sinceTs: Long): Long {
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("action", "pull")
            if (conversationId != null) put("conversationId", conversationId)
            put("sinceTs", sinceTs)
        }
        val result = supabaseClient.invokeFunction("sync-messages", payload)
        return when (result) {
            is SupabaseResult.Success -> {
                val messages = result.data.optJSONArray("messages") ?: JSONArray()
                val untilTs = result.data.optLong("untilTs", System.currentTimeMillis())
                // Insert any messages we don't already have locally
                val currentUserId = supabaseClient.currentUser?.id ?: ""
                (0 until messages.length()).forEach { i ->
                    val obj = messages.getJSONObject(i)
                    val id = obj.optString("id", "")
                    if (id.isNotBlank()) {
                        val existing = repository.getMessageById(id)
                        if (existing == null) {
                            val isOutgoing = obj.optString("sender_id", "") == currentUserId
                            repository.insertMessage(mapSupabaseToDomain(obj, isOutgoing = isOutgoing))
                        } else {
                            // Update fields that may have changed on the server (edit, status, pin)
                            val text = obj.optString("text", "")
                            if (text.isNotEmpty() && text != existing.text) {
                                repository.updateMessageText(id, text)
                            }
                            if (!obj.isNull("is_pinned")) {
                                repository.setMessagePinned(id, obj.optBoolean("is_pinned", false))
                            }
                            if (!obj.isNull("is_starred")) {
                                repository.setMessageStarred(id, obj.optBoolean("is_starred", false))
                            }
                            // Status heal: the pull is the only path that fixes
                            // ticks missed while this device was offline.
                            if (!obj.isNull("read_at")) {
                                repository.updateMessageStatus(id, MessageStatus.READ)
                            } else if (obj.optString("status", "") == "DELIVERED" &&
                                existing.status != MessageStatus.READ) {
                                repository.updateMessageStatus(id, MessageStatus.DELIVERED)
                            }
                        }
                    }
                }
                // Persist the watermark — per conversation when scoped (the
                // global key is only maintained for unscoped pulls).
                prefs?.edit()?.apply {
                    if (conversationId != null) putLong(lastSyncKey(conversationId), untilTs)
                    putLong(KEY_LAST_SYNC_TS, untilTs)
                }?.apply()
                untilTs
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "syncMessages failed: ${result.message}")
                sinceTs
            }
        }
    }

    override suspend fun markConversationRead(conversationId: String) {
        // Mark locally (Room) for instant UI
        repository.markConversationRead(conversationId)
        // Call the mark-conversation-read edge function with the correct payload
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
        }
        supabaseClient.invokeFunction("mark-conversation-read", payload)
    }

    override suspend fun toggleArchiveConversation(conversationId: String, isArchived: Boolean) {
        // Update Room
        repository.setConversationArchived(conversationId, isArchived)
        // Sync to Supabase via direct upsert
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("id", conversationId)
            put("is_archived", isArchived)
        }
        supabaseClient.upsertRecord("conversations", payload, onConflict = "id")
    }
}
