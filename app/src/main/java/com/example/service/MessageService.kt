package com.example.service

import com.example.model.DomainMessage
import com.example.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageService {
    fun observeMessages(conversationId: String): Flow<List<DomainMessage>>

    /** Task 25: ensures the per-conversation Realtime subscription is active.
     *  The windowed chat no longer collects the full Room message flow (the
     *  old observeMessages() side effect), so the open chat now calls this
     *  explicitly. Idempotent. */
    fun ensureRealtimeSubscription(conversationId: String)

    /**
     * Task 25 — server-side backward pagination (sync-messages action=history).
     * Fetches up to [limit] messages STRICTLY older than the (ts, seq, id)
     * cursor, NEWEST first, for a conversation the caller participates in.
     * Every returned row is upserted into Room (REPLACE by id, so re-fetches
     * are impossible to duplicate). Returns the mapped messages.
     */
    suspend fun fetchHistoryPage(
        conversationId: String,
        beforeTimestampMillis: Long,
        beforeSeq: Long,
        beforeMessageId: String,
        limit: Int
    ): List<DomainMessage>

    /**
     * Task 25 — reply-navigation deep fetch: single message by id straight
     * from the server (participant RLS applies), upserted into Room.
     * Returns null when the row does not exist or the fetch fails.
     */
    suspend fun fetchMessageById(conversationId: String, messageId: String): DomainMessage?
    suspend fun sendMessage(message: DomainMessage, peerId: String? = null, peerName: String? = null)

    /**
     * H4 unification: given a peer USER uuid, resolves the real server
     * conversation id (local cache → server lookup both directions → create).
     * Returns null when the peer id is not a valid UUID or resolution fails.
     */
    suspend fun resolveOrCreateConversation(peerId: String): String?

    /**
     * Inserts an outgoing media message into Room only (status SENDING) — the
     * server call is deferred until its upload completes so the server row is
     * created with the recipient-accessible URL, never a local content:// URI.
     */
    suspend fun stageOutgoingMessage(message: DomainMessage)

    /**
     * Called when a media upload completes. Writes the final remote URL back
     * onto the Room row and sends the message to the server with it.
     */
    suspend fun completeMediaUpload(task: com.example.model.UploadTask)

    /**
     * Called when a media upload fails permanently — flips the staged message
     * to FAILED so the user can retry.
     */
    suspend fun markMediaMessageFailed(messageId: String, reason: String)

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

    /** Local-first outbox flush (Task 24): retries every outgoing message
     *  stuck in SENDING (app killed mid-send) or FAILED that is older than
     *  2 minutes. Called automatically when connectivity returns and on app
     *  start. Idempotent — send-message dedupes by the stable idempotency
     *  key, so a message that actually reached the server is never sent
     *  twice. Returns the number of messages retried. */
    suspend fun retryPendingOutbox(): Int
    suspend fun forwardMessage(message: DomainMessage, targetConversationIds: List<String>)
    suspend fun toggleStarMessage(messageId: String)

    /** Edits a message's text via the edit-message edge function. Only the
     *  sender can edit TEXT messages within 15 minutes of sending. */
    suspend fun editMessage(messageId: String, newText: String)

    /** Toggles the is_pinned flag on a message via direct Supabase upsert. */
    suspend fun togglePinMessage(messageId: String)

    /** Re-calls send-message for every message with status FAILED in Room. */
    suspend fun retryAllFailedMessages()

    /**
     * Runs the search-messages edge function for a given filter. Returns a list
     * of (potentially partial) DomainMessage instances containing id, text,
     * type, senderId, timestamp, mediaUrl, fileName.
     */
    suspend fun searchMessagesEx(
        conversationId: String,
        query: String,
        searchType: String,
        dateFrom: String? = null,
        dateTo: String? = null
    ): List<DomainMessage>

    /**
     * Fetches the shared_links view for a conversation from Supabase.
     * Returns a list of (linkText, createdAt) pairs.
     */
    suspend fun getSharedLinks(conversationId: String): List<SharedLink>

    /**
     * Reports a user via the report-user edge function.
     */
    suspend fun reportUser(reportedUserId: String, reason: String)

    /**
     * Syncs messages from the server (action=pull). Inserts any messages that
     * aren't already in Room. Returns the untilTs timestamp.
     */
    suspend fun syncMessages(conversationId: String? = null, sinceTs: Long = 0L): Long

    /**
     * WhatsApp-model background catch-up (NOT chat-open): pulls, per recent
     * conversation, only the messages newer than that conversation's local
     * watermark into Room. Called on app start and on connectivity regain —
     * never while opening a chat (the chat renders Room directly, offline
     * included). Failures are non-fatal; watermarks are never reset.
     */
    suspend fun backgroundCatchUpSync()

    /**
     * Marks a conversation as read on the server via mark-conversation-read.
     */
    suspend fun markConversationRead(conversationId: String)

    /**
     * Toggles the is_archived flag on a conversation via direct Supabase upsert.
     */
    suspend fun toggleArchiveConversation(conversationId: String, isArchived: Boolean)
}

data class SharedLink(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val createdAt: String
)
