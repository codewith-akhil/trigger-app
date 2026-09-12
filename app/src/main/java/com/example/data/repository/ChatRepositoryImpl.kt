package com.example.data.repository

import com.example.data.local.ChatDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.model.*
import com.example.service.MediaUrlResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepositoryImpl(
    private val database: ChatDatabase,
    private val coroutineScope: CoroutineScope
) {
    private val messageDao = database.messageDao()
    private val conversationDao = database.conversationDao()

    fun getAllConversations(): Flow<List<DomainConversation>> {
        return conversationDao.getAllConversations().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun insertConversation(entity: com.example.data.local.ConversationEntity) {
        conversationDao.insertConversation(entity)
    }

    fun getConversation(id: String): Flow<DomainConversation?> {
        return conversationDao.getConversationById(id).map { it?.toDomain() }
    }

    suspend fun getConversationByIdOnce(id: String): DomainConversation? {
        return conversationDao.getConversationByIdOnce(id)?.toDomain()
    }

    /** Finds the conversation whose peer is the given user UUID. */
    suspend fun getConversationByPeer(peerId: String): DomainConversation? {
        return conversationDao.getConversationByPeer(peerId)?.toDomain()
    }

    /** H4: moves every Room message cached under [fromConversationId] (a
     *  legacy peer-UUID key) onto the real conversation UUID. */
    suspend fun rekeyConversationMessages(fromConversationId: String, toConversationId: String) {
        if (fromConversationId == toConversationId) return
        messageDao.rekeyConversationMessages(fromConversationId, toConversationId)
    }

    /** Ensures a conversation row exists with the real UUID, carrying peer info. */
    suspend fun ensureConversationRow(id: String, peerId: String?, name: String?) {
        val existing = conversationDao.getConversationByIdOnce(id)
        if (existing == null) {
            conversationDao.insertConversation(
                ConversationEntity(id = id, name = name?.takeIf { it.isNotBlank() } ?: "Chat", peerId = peerId)
            )
        } else if (peerId != null && existing.peerId != peerId) {
            conversationDao.updatePeerId(id, peerId)
        }
    }

    /** Refreshes a conversation row's last-message preview + activity stamp so
     *  the dashboard chat list reorders/completes on live events without
     *  waiting for the next sync-conversations pull. No-op when the row does
     *  not exist (the caller ensures it first). */
    suspend fun updateConversationLastMessage(
        id: String,
        preview: String,
        timestamp: String,
        timestampMillis: Long
    ) {
        if (conversationDao.getConversationByIdOnce(id) == null) return
        conversationDao.updateLastMessage(id, preview, timestamp, timestampMillis)
    }

    /** Task 24: mint short-lived signed URLs for PRIVATE-bucket media before
     *  any render surface. Memoized in MediaUrlResolver (first call per object
     *  signs; later calls hit the in-memory cache; offline falls back to the
     *  last known signature + Coil's stable disk-cache key). */
    private suspend fun DomainMessage.withSignedMedia(): DomainMessage {
        if (!MediaUrlResolver.isPrivateBucket(mediaBucket)) return this
        val freshUrl = MediaUrlResolver.resolveWithRefresh(mediaUrl, mediaBucket, mediaPath)
        val thumbPath = MediaUrlResolver.objectPathOf(mediaThumbnail, mediaBucket, null)
        val freshThumb = if (thumbPath != null) {
            MediaUrlResolver.resolveWithRefresh(mediaThumbnail, mediaBucket, thumbPath)
        } else mediaThumbnail
        return copy(
            mediaUrl = freshUrl ?: mediaUrl,
            mediaThumbnail = freshThumb ?: mediaThumbnail
        )
    }

    fun getMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getMessagesForConversation(conversationId).map { list ->
            list.map { it.toDomainMessage().withSignedMedia() }
        }
    }

    /**
     * Loads one page of older messages ending before `beforeTimestamp`.
     * Used for cursor-based pagination when the user scrolls to the top.
     */
    suspend fun getMessagesPage(conversationId: String, beforeTimestamp: Long, limit: Int = 50): List<DomainMessage> {
        return messageDao.getMessagesPage(conversationId, beforeTimestamp, limit)
            .map { it.toDomainMessage().withSignedMedia() }
    }

    // ------------------------------------------------------------------
    // Task 25 — bounded message window (cursor pagination)
    // ------------------------------------------------------------------

    /** Latest [limit] messages, NEWEST first — the initial window anchor. */
    suspend fun getLatestMessages(conversationId: String, limit: Int): List<DomainMessage> {
        return messageDao.getLatestMessages(conversationId, limit)
            .map { it.toDomainMessage().withSignedMedia() }
    }

    /** Page STRICTLY older than [before], NEWEST first (local cache only). */
    suspend fun getMessagesBeforeCursor(conversationId: String, before: MessageCursor, limit: Int): List<DomainMessage> {
        return messageDao.getMessagesBeforeCursor(conversationId, before.timestampMillis, before.seq, before.messageId, limit)
            .map { it.toDomainMessage().withSignedMedia() }
    }

    /** Page STRICTLY newer than [after], OLDEST first (local cache only). */
    suspend fun getMessagesAfterCursor(conversationId: String, after: MessageCursor, limit: Int): List<DomainMessage> {
        return messageDao.getMessagesAfterCursor(conversationId, after.timestampMillis, after.seq, after.messageId, limit)
            .map { it.toDomainMessage().withSignedMedia() }
    }

    /** Live Room flow over the INCLUSIVE [top..bottom] window. Null bounds
     *  are unbounded. The open chat's ONLY list source — bounded memory,
     *  bounded re-mapping, every row change inside the window re-emits. */
    fun observeMessageWindow(conversationId: String, top: MessageCursor?, bottom: MessageCursor?): Flow<List<DomainMessage>> {
        return messageDao.observeMessageWindow(
            conversationId = conversationId,
            hasTop = if (top != null) 1 else 0,
            topTs = top?.timestampMillis ?: 0L,
            topSeq = top?.seq ?: 0L,
            topId = top?.messageId ?: "",
            hasBottom = if (bottom != null) 1 else 0,
            bottomTs = bottom?.timestampMillis ?: Long.MAX_VALUE,
            bottomSeq = bottom?.seq ?: Long.MAX_VALUE,
            bottomId = bottom?.messageId ?: ""
        ).map { list -> list.map { it.toDomainMessage().withSignedMedia() } }
    }

    /**
     * Returns all messages with status FAILED (across all conversations) so the
     * offline queue can retry them.
     */
    suspend fun getAllFailedMessages(): List<DomainMessage> {
        return messageDao.getAllFailedMessages().map { it.toDomainMessage() }
    }

    /** Outbox scan for the connectivity-triggered retry (Task 24). */
    suspend fun getStaleOutgoingMessages(olderThanMillis: Long): List<DomainMessage> {
        return messageDao.getStaleOutgoingMessages(olderThanMillis).map { it.toDomainMessage() }
    }

    /**
     * Returns starred messages in a conversation.
     */
    suspend fun getStarredMessages(conversationId: String): List<DomainMessage> {
        return messageDao.getStarredMessages(conversationId)
            .map { it.toDomainMessage().withSignedMedia() }
    }

    /**
     * Returns pinned messages in a conversation.
     */
    suspend fun getPinnedMessages(conversationId: String): List<DomainMessage> {
        return messageDao.getPinnedMessages(conversationId)
            .map { it.toDomainMessage().withSignedMedia() }
    }

    suspend fun sendMessage(message: DomainMessage, isOnline: Boolean) {
        val initialStatus = if (!isOnline) MessageStatus.FAILED else MessageStatus.SENDING
        val entity = MessageEntity.fromDomain(message.copy(status = initialStatus))
        messageDao.insertMessage(entity)

        // Update conversation's last message
        val lastMsgPreview = when (message.type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎤 Voice message"
            MessageType.DOCUMENT -> "📄 ${message.fileName ?: "Document"}"
            MessageType.LOCATION -> "📍 Location"
            MessageType.CONTACT -> "👤 ${message.contactName ?: "Contact"}"
            MessageType.CALL_LOG -> message.text
            else -> message.text
        }
        conversationDao.updateLastMessage(
            message.conversationId,
            lastMsgPreview,
            message.timestamp,
            message.timestampMillis
        )

        // Status transitions are driven by the send-message edge function (SENT)
        // and Supabase Realtime (DELIVERED/READ). No fake delay() ticks.
        if (isOnline) {
            // The MessageServiceImpl.sendMessage calls the edge function which
            // sets status=SENT on the server. Realtime UPDATE events from the
            // receiver's device will set DELIVERED and READ.
        }
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status.name)
    }

    /** Overwrites the stored reaction aggregate (server → raw form). */
    suspend fun updateMessageReactions(messageId: String, raw: String) {
        messageDao.updateMessageReactions(messageId, raw)
    }

    suspend fun toggleReaction(messageId: String, emoji: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        val currentReactions = msg.toDomainMessage().reactions.toMutableList()

        // ONE reaction per user per message (mirrors the server rule —
        // UNIQUE(message_id, user_id) + the toggle-reaction edge function):
        //   • same emoji tapped again → remove it
        //   • a DIFFERENT emoji while one is already mine → SWITCH (remove the
        //     old pill, add the new one). The old code added a second pill.
        val myExistingIndex = currentReactions.indexOfFirst { it.userReacted }

        if (myExistingIndex != -1) {
            val mine = currentReactions[myExistingIndex]
            if (mine.emoji == emoji) {
                // Same emoji → remove my reaction.
                if (mine.count <= 1) {
                    currentReactions.removeAt(myExistingIndex)
                } else {
                    currentReactions[myExistingIndex] = mine.copy(count = mine.count - 1, userReacted = false)
                }
            } else {
                // Different emoji → switch.
                if (mine.count <= 1) {
                    currentReactions.removeAt(myExistingIndex)
                } else {
                    currentReactions[myExistingIndex] = mine.copy(count = mine.count - 1, userReacted = false)
                }
                val targetIndex = currentReactions.indexOfFirst { it.emoji == emoji }
                if (targetIndex != -1) {
                    currentReactions[targetIndex] =
                        currentReactions[targetIndex].copy(count = currentReactions[targetIndex].count + 1, userReacted = true)
                } else {
                    currentReactions.add(MessageReaction(emoji = emoji, count = 1, userReacted = true))
                }
            }
        } else {
            val targetIndex = currentReactions.indexOfFirst { it.emoji == emoji }
            if (targetIndex != -1) {
                currentReactions[targetIndex] =
                    currentReactions[targetIndex].copy(count = currentReactions[targetIndex].count + 1, userReacted = true)
            } else {
                currentReactions.add(MessageReaction(emoji = emoji, count = 1, userReacted = true))
            }
        }

        val raw = currentReactions.joinToString(";") { "${it.emoji}:${it.count}:${it.userReacted}" }
        messageDao.updateMessageReactions(messageId, raw)
    }

    suspend fun markViewOnceAsViewed(messageId: String) {
        messageDao.markViewOnceAsViewed(messageId)
    }

    suspend fun deleteForEveryone(messageId: String) {
        messageDao.markDeletedForEveryone(messageId)
    }

    suspend fun deleteForMe(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    suspend fun clearChat(conversationId: String) {
        messageDao.clearConversationMessages(conversationId)
        conversationDao.updateLastMessage(conversationId, "", "", 0L)
    }

    fun searchMessages(conversationId: String, query: String): Flow<List<DomainMessage>> {
        return messageDao.searchMessages(conversationId, query).map { list ->
            list.map { it.toDomainMessage() }
        }
    }

    fun getMediaMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getMediaMessages(conversationId).map { list ->
            list.map { it.toDomainMessage().withSignedMedia() }
        }
    }

    fun getDocumentMessages(conversationId: String): Flow<List<DomainMessage>> {
        return messageDao.getDocumentMessages(conversationId).map { list ->
            list.map { it.toDomainMessage().withSignedMedia() }
        }
    }

    /** Block flow: drop the local pending conversation row so the chat leaves
     *  the Requests tab the instant the server confirms the block. */
    suspend fun deleteLocalConversationRow(conversationId: String) {
        conversationDao.deleteConversationsByIds(listOf(conversationId))
    }

    suspend fun setDisappearingDuration(
        conversationId: String,
        duration: DisappearingDuration,
        activatedAtMillis: Long = System.currentTimeMillis(),
    ) {
        conversationDao.updateDisappearing(conversationId, duration.name, activatedAtMillis)
        if (duration != DisappearingDuration.OFF) {
            // From-activation-time semantics: only messages created after the
            // setting was activated are eligible, never older history.
            val expireThreshold = System.currentTimeMillis() - duration.millis
            messageDao.deleteExpiredDisappearingMessages(conversationId, expireThreshold, activatedAtMillis)
        }
    }

    suspend fun markConversationRead(conversationId: String) {
        conversationDao.markAsRead(conversationId)
    }

    suspend fun setConversationMuted(conversationId: String, isMuted: Boolean) {
        conversationDao.setMuted(conversationId, isMuted)
    }

    suspend fun setBlocked(conversationId: String, isBlocked: Boolean) {
        conversationDao.updateBlocked(conversationId, isBlocked)
    }

    suspend fun updateRequestStatus(conversationId: String, status: String) {
        conversationDao.updateRequestStatus(conversationId, status)
    }

    suspend fun retryFailedMessage(messageId: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        messageDao.updateMessageStatus(messageId, MessageStatus.SENDING.name)
        // The MessageServiceImpl will re-call the send-message edge function.
        // Status transitions come from Realtime, not from delay().
    }

    /**
     * Toggles the starred flag on a message.
     */
    suspend fun toggleStar(messageId: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        val newStarred = !msg.isStarred
        messageDao.updateMessageStarred(messageId, newStarred)
    }

    /**
     * Updates message text + edited_at timestamp (for edit feature).
     */
    suspend fun updateMessageText(messageId: String, newText: String) {
        messageDao.updateMessageText(messageId, newText, System.currentTimeMillis())
    }

    /**
     * Toggles the pinned flag on a message.
     */
    suspend fun togglePin(messageId: String) {
        val msg = messageDao.getMessageById(messageId) ?: return
        val newPinned = !msg.isPinned
        messageDao.updateMessagePinned(messageId, newPinned)
    }

    /**
     * Sets the archived flag on a conversation.
     */
    suspend fun setConversationArchived(conversationId: String, isArchived: Boolean) {
        conversationDao.updateArchived(conversationId, isArchived)
    }

    /**
     * Returns a single message by ID.
     */
    suspend fun getMessageById(messageId: String): DomainMessage? {
        return messageDao.getMessageById(messageId)?.toDomainMessage()
    }

    /**
     * Sets the pinned flag on a message.
     */
    suspend fun setMessagePinned(messageId: String, isPinned: Boolean) {
        messageDao.updateMessagePinned(messageId, isPinned)
    }

    /**
     * Sets the starred flag on a message.
     */
    suspend fun setMessageStarred(messageId: String, isStarred: Boolean) {
        messageDao.updateMessageStarred(messageId, isStarred)
    }

    /**
     * Updates the seq field on a message.
     */
    suspend fun updateMessageSeq(messageId: String, seq: Long) {
        val msg = messageDao.getMessageById(messageId) ?: return
        messageDao.insertMessage(msg.copy(seq = seq))
    }

    /**
     * Writes the final remote media URL back onto a message after its upload
     * completed, so the sender's own bubble (and any history reload) uses the
     * recipient-accessible URL instead of the local content:// preview.
     */
    suspend fun updateMessageMedia(messageId: String, mediaUrl: String) {
        messageDao.updateMessageMedia(messageId, mediaUrl)
    }

    /** Writes URL + bucket + path (re-sign groundwork). */
    suspend fun updateMessageMediaFull(messageId: String, mediaUrl: String, bucket: String?, path: String?) {
        messageDao.updateMessageMediaFull(messageId, mediaUrl, bucket, path)
    }

    /** Phase 3 media persistence: records the durable on-device archive copy
     *  path (Trigger folder tree) on the row after a successful download, or
     *  nulls it. UI render/play prefers this file when it exists. */
    suspend fun updateMessageLocalMediaPath(messageId: String, path: String?) {
        messageDao.updateMessageLocalMediaPath(messageId, path)
    }

    /** Marks a message viewed (view-once sync from Realtime UPDATE events). */
    suspend fun markMessageViewed(messageId: String) {
        messageDao.markMessageViewed(messageId)
    }

    /**
     * Inserts a message directly (for incoming Realtime messages).
     * Phase 3: REPLACE-on-conflict would otherwise wipe an already-archived
     * localMediaPath when the same row is re-delivered (realtime replay,
     * history refetch) — the archived path is preserved when the incoming
     * domain object doesn't carry one.
     */
    suspend fun insertMessage(message: DomainMessage) {
        val preservedPath = message.localMediaPath
            ?: messageDao.getMessageById(message.id)?.localMediaPath
        val toInsert = if (preservedPath != null) message.copy(localMediaPath = preservedPath) else message
        messageDao.insertMessage(MessageEntity.fromDomain(toInsert))
    }

    /**
     * Persists a profile-resolved avatar URL onto the conversation row so the
     * dashboard chat list renders the real photo (not the letter fallback)
     * the moment the chat header resolves it — no sync pull required.
     */
    suspend fun updateConversationAvatarUrl(conversationId: String, url: String) {
        conversationDao.updateAvatarUrl(conversationId, url)
    }
}
