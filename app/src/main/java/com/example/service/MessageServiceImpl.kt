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
        private const val EDIT_WINDOW_MS = 15L * 60 * 1000  // 15 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active realtime subscriptions per conversation
    private val activeSubscriptions = mutableSetOf<String>()

    private val prefs: SharedPreferences? by lazy {
        try {
            AppServiceContainer.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    override fun observeMessages(conversationId: String): Flow<List<DomainMessage>> {
        // Subscribe to Realtime for this conversation if not already
        ensureRealtimeSubscription(conversationId)
        // Return the Room flow (Room is the local cache, Realtime updates it)
        return repository.getMessages(conversationId)
    }

    private fun ensureRealtimeSubscription(conversationId: String) {
        if (activeSubscriptions.contains(conversationId)) return
        activeSubscriptions.add(conversationId)

        val supabaseClient = AppServiceContainer.supabaseClient
        // Connect to Realtime for messages + conversations + user_presences.
        // Filter messages by conversation_id so we don't get every message
        // in the system delivered over the wire.
        supabaseClient.connectRealtime(
            tables = listOf("public.messages", "public.conversations", "public.user_presences"),
            filter = "conversation_id=eq.$conversationId"
        )

        // Collect realtime events and update the local Room DB
        scope.launch {
            supabaseClient.realtimeEvents.collect { event ->
                try {
                    when (event.table) {
                        "messages" -> handleRealtimeMessageEvent(event, conversationId)
                        "user_presences" -> handleRealtimePresenceEvent(event)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to handle realtime event: ${e.message}")
                }
            }
        }

        // Also collect the reconnect signal so we can pull any messages that
        // were missed during the WebSocket disconnect.
        scope.launch {
            supabaseClient.reconnectSignals.collect {
                Log.i(TAG, "Realtime reconnected — pulling missed messages")
                try {
                    val lastTs = prefs?.getLong(KEY_LAST_SYNC_TS, 0L) ?: 0L
                    syncMessages(conversationId = conversationId, sinceTs = lastTs)
                } catch (e: Exception) {
                    Log.w(TAG, "Post-reconnect sync failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleRealtimeMessageEvent(event: RealtimeEvent, conversationId: String) {
        val record = event.record ?: return
        val msgConversationId = record.optString("conversation_id", "")
        if (msgConversationId != conversationId && msgConversationId.isNotEmpty()) return  // not our conversation

        when (event.eventType) {
            "INSERT" -> {
                // New message from the other user — insert into Room if it's incoming
                val senderId = record.optString("sender_id", "")
                val currentUserId = AppServiceContainer.supabaseClient.currentUser?.id ?: ""
                if (senderId != currentUserId) {
                    val domainMsg = mapSupabaseToDomain(record, isOutgoing = false)
                    repository.insertMessage(domainMsg)
                    // Auto-mark as DELIVERED (we received it)
                    repository.updateMessageStatus(domainMsg.id, MessageStatus.DELIVERED)
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
                // Check if text was edited
                val text = record.optString("text", "")
                if (text.isNotEmpty() && !record.isNull("text")) {
                    repository.updateMessageText(msgId, text)
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
                // Check if deleted for everyone
                if (record.optBoolean("is_deleted_for_everyone", false)) {
                    repository.deleteForEveryone(msgId)
                }
            }
            "DELETE" -> {
                val msgId = record.optString("id", "")
                repository.deleteForMe(msgId)
            }
        }
    }

    private fun handleRealtimePresenceEvent(event: RealtimeEvent) {
        val record = event.record ?: return
        val isOnline = record.optBoolean("is_online", false)
        val lastSeen = record.optString("last_seen_at", "")
        val userId = record.optString("user_id", "")
        // Update presence via the PresenceService
        if (presenceService is PresenceServiceImpl) {
            val status = if (isOnline) PresenceStatus.ONLINE else PresenceStatus.OFFLINE
            val text = if (isOnline) "online" else "last seen $lastSeen"
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
        return DomainMessage(
            id = record.optString("id", ""),
            conversationId = record.optString("conversation_id", ""),
            senderId = record.optString("sender_id", ""),
            senderName = record.optString("sender_name", ""),
            type = try {
                MessageType.valueOf(record.optString("type", "TEXT"))
            } catch (e: Exception) { MessageType.TEXT },
            text = record.optString("text", ""),
            mediaUrl = record.optString("media_url", null),
            mediaThumbnail = record.optString("media_thumbnail", null),
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
            contactName = record.optString("contact_name", null),
            contactPhone = record.optString("contact_phone", null)
        )
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

    override suspend fun sendMessage(message: DomainMessage) {
        // 1. Insert locally (Room) immediately for instant UI
        repository.sendMessage(message, isOnline = true)

        // 2. Send to Supabase via send-message edge function with idempotency_key
        val supabaseClient = AppServiceContainer.supabaseClient
        val idempotencyKey = message.idempotencyKey ?: java.util.UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("conversation_id", message.conversationId)
            put("type", message.type.name)
            put("text", message.text)
            put("timestamp_millis", message.timestampMillis)
            put("idempotency_key", idempotencyKey)
            if (message.mediaUrl != null) put("media_url", message.mediaUrl)
            if (message.fileName != null) put("file_name", message.fileName)
            if (message.fileSize > 0) put("file_size", message.fileSize)
            if (message.mediaDurationSec > 0) put("media_duration_sec", message.mediaDurationSec)
            if (message.isViewOnce) put("is_view_once", true)
            if (message.replyToId != null) put("reply_to_id", message.replyToId)
            if (message.locationLatitude != null) put("location_lat", message.locationLatitude)
            if (message.locationLongitude != null) put("location_lng", message.locationLongitude)
            if (message.locationAddress != null) put("location_address", message.locationAddress)
            if (message.contactName != null) put("contact_name", message.contactName)
            if (message.contactPhone != null) put("contact_phone", message.contactPhone)
        }

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
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "send-message failed: ${result.message}")
                // Mark as FAILED so the user can retry (offline queue)
                repository.updateMessageStatus(message.id, MessageStatus.FAILED)
            }
        }
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
        return repository.searchMessages(conversationId, query)
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
        repository.updateMessageStatus(messageId, MessageStatus.SENDING)
        sendMessage(msg)
    }

    override suspend fun retryAllFailedMessages() {
        val failed = repository.getAllFailedMessages()
        if (failed.isEmpty()) return
        Log.i(TAG, "Retrying ${failed.size} failed messages")
        failed.forEach { msg ->
            try {
                sendMessage(msg)
            } catch (e: Exception) {
                Log.w(TAG, "Retry failed for ${msg.id}: ${e.message}")
            }
        }
    }

    override suspend fun forwardMessage(message: DomainMessage, targetConversationIds: List<String>) {
        // Insert locally for each target
        val currentTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
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
            repository.sendMessage(forwardedMsg, true)
        }

        // Sync to Supabase via forward-message edge function
        val supabaseClient = AppServiceContainer.supabaseClient
        val payload = JSONObject().apply {
            put("message_id", message.id)
            put("target_conversation_ids", org.json.JSONArray(targetConversationIds))
        }
        val result = supabaseClient.invokeFunction("forward-message", payload)
        if (result is SupabaseResult.Success) {
            // Update local messages to SENT
            targetConversationIds.forEach { _ ->
                // The edge function created the real server-side messages
                // Realtime will deliver them to the receiver
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
                        }
                    }
                }
                // Persist the untilTs for the next sync
                prefs?.edit()?.putLong(KEY_LAST_SYNC_TS, untilTs)?.apply()
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
