package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.config.ChatConfig
import com.example.chat.pagination.MessageWindowController
import com.example.di.AppServiceContainer
import com.example.model.*
import com.example.service.CallSession
import com.example.service.LiveLocationService
import com.example.service.LiveLocationShareState
import com.example.service.MediaUrlResolver
import com.example.service.MessageServiceImpl
import com.example.service.supabase.RealtimeEvent
import com.example.service.supabase.SupabaseResult
import com.example.storage.ChatMediaFolders
import com.example.storage.TriggerFolder
import com.example.util.optStringOrNull
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

data class PendingAttachment(
    val type: MessageType,
    val fileName: String,
    val fileSize: Long,
    val previewUrl: String? = null,
    val previewRes: Int? = null,
    val caption: String = "",
    val isViewOnce: Boolean = false,
    val durationSec: Int = 0,
    val filePath: String? = null,
    val mimeType: String? = null
)

class ChatViewModel(
    /** REAL server conversation uuid (H4). All data flows key on this. */
    val conversationId: String = "",
    /** The OTHER user's auth uuid — presence, typing indicators, calls, reports. */
    val peerId: String = "",
    val contactName: String = "",
    val contactAvatarRes: Int? = null
) : ViewModel() {

    private val messageService = AppServiceContainer.messageService
    private val uploadService = AppServiceContainer.uploadService
    private val presenceService = AppServiceContainer.presenceService
    private val callService = AppServiceContainer.callService
    private val storageService = AppServiceContainer.storageService
    private val repository = AppServiceContainer.chatRepository
    private val supabaseClient = AppServiceContainer.supabaseClient

    // ---------- Message window (Task 25 pagination) ----------
    // WhatsApp-style bounded window backed by Room: the chat renders the
    // latest 50 messages instantly (local-first, no Supabase wait), older
    // history loads page-by-page on scroll-to-top (Room cache first, the
    // sync-messages history endpoint only when the cache is exhausted), and
    // a reply-navigation jump pins a ~2-page window around the target so a
    // 1000-message thread never enters memory at once.
    private val messageWindow = MessageWindowController(
        conversationId = conversationId,
        scope = viewModelScope,
        dataSource = object : MessageWindowController.DataSource {
            override suspend fun latest(conversationId: String, limit: Int) =
                repository.getLatestMessages(conversationId, limit)

            override suspend fun olderFromLocal(conversationId: String, before: MessageCursor, limit: Int) =
                repository.getMessagesBeforeCursor(conversationId, before, limit)

            override suspend fun newerFromLocal(conversationId: String, after: MessageCursor, limit: Int) =
                repository.getMessagesAfterCursor(conversationId, after, limit)

            override suspend fun olderFromServer(conversationId: String, before: MessageCursor, limit: Int) =
                messageService.fetchHistoryPage(
                    conversationId = conversationId,
                    beforeTimestampMillis = before.timestampMillis,
                    beforeSeq = before.seq,
                    beforeMessageId = before.messageId,
                    limit = limit
                )

            override suspend fun messageFromServerById(conversationId: String, messageId: String) =
                messageService.fetchMessageById(conversationId, messageId)

            override suspend fun messageById(conversationId: String, messageId: String) =
                repository.getMessageById(messageId)

            /**
             * Population guarantee (final chat fix): one-shot newest-page
             * backfill when Room has nothing for this conversation — the
             * WhatsApp "cache is full before the UI reads it" contract.
             * syncMessages(sinceTs=0) hits the server's INITIAL branch
             * (newest page, rows upserted into Room inside it); the read-back
             * keeps Room the single source of truth. Empty return = offline,
             * which the controller treats as retryable.
             */
            override suspend fun initialPageFromServer(conversationId: String, limit: Int): List<DomainMessage> {
                messageService.syncMessages(conversationId = conversationId, sinceTs = 0L)
                return repository.getLatestMessages(conversationId, limit)
            }

            override fun observeWindow(conversationId: String, top: MessageCursor?, bottom: MessageCursor?) =
                repository.observeMessageWindow(conversationId, top, bottom)
        }
    )

    /** The bounded, chronologically-sorted window the chat renders. */
    val messages: StateFlow<List<DomainMessage>> = messageWindow.messages
    val isLoadingOlder: StateFlow<Boolean> = messageWindow.isLoadingOlder
    val hasMoreOlder: StateFlow<Boolean> = messageWindow.hasMoreOlder
    val isLoadingNewer: StateFlow<Boolean> = messageWindow.isLoadingNewer
    /** True while the empty-cache initial pull is in flight (fresh install /
     *  first open of this chat on this device). The chat MUST show a loading
     *  state for it — a bare empty list read as "broken chat". */
    val isInitialSyncing: StateFlow<Boolean> = messageWindow.isInitialSyncing

    /** True when the window bottom is pinned away from the live edge (post-jump). */
    val isWindowed: StateFlow<Boolean> = messageWindow.isWindowed

    /** Reply deep-jump: id of the message the UI should scroll to once the
     *  window re-emits with it inside. Null = nothing pending. */
    val pendingJumpTo = MutableStateFlow<String?>(null)
    fun clearPendingJump() { pendingJumpTo.value = null }

    val contactPresence: StateFlow<Pair<PresenceStatus, String>> = presenceService
        .observeContactPresence(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PresenceStatus.OFFLINE to "")

    val connectionState: StateFlow<PresenceStatus> = presenceService.connectionState
    val activeCall: StateFlow<CallSession?> = callService.currentCall
    val activeUploads: StateFlow<List<UploadTask>> = uploadService.activeUploads

    val conversationInfo = repository.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---------- Message-request state (Instagram model) ----------
    // The canonical conversation row carries request_status: while it is
    // "pending", the REQUESTER may send at most 3 messages and the RECEIVER
    // must accept before replying; presence is hidden (server-side RLS gate).
    data class ConversationMeta(
        val requestStatus: String,      // pending | accepted | blocked | declined
        val isRequester: Boolean,       // I sent the request (I own the canonical row)
        val myRequestMessageCount: Int, // messages I already sent while pending (0..3)
        val pendingRequestId: String?   // message_requests.id when the receiver needs to act
    )

    private val _conversationMeta = MutableStateFlow<ConversationMeta?>(null)
    val conversationMeta: StateFlow<ConversationMeta?> = _conversationMeta.asStateFlow()

    /** One-shot notice for the UI when a send is blocked by request rules. */
    val requestNotice = MutableStateFlow<String?>(null)

    /** Which request action is running: "accept" | "decline" | "block" | null.
     *  Drives per-button spinner in the receiver banner and disables all buttons. */
    private val _requestActionInProgress = MutableStateFlow<String?>(null)
    val requestActionInProgress: StateFlow<String?> = _requestActionInProgress.asStateFlow()

    fun clearRequestNotice() { requestNotice.value = null }

    /** True while the pair is still in the pending-request phase. */
    val isMessageRequestPending: Boolean
        get() = _conversationMeta.value?.requestStatus == "pending"

    private var requestPollingJob: Job? = null

    fun refreshConversationMeta() {
        viewModelScope.launch {
            _conversationMeta.value = fetchConversationMeta()
            startRequestPolling()
        }
    }

    private suspend fun fetchConversationMeta(): ConversationMeta? {
        if (conversationId.isBlank()) return null
        return try {
            val res = supabaseClient.getTable(
                "conversations",
                "id=eq.$conversationId&select=id,owner_id,peer_id,request_status&limit=1"
            )
            val row = (res as? SupabaseResult.Success)?.data?.optJSONObject(0) ?: return null
            val status = row.optString("request_status", "accepted").ifBlank { "accepted" }
            val ownerId = row.optString("owner_id", "")
            val myId = supabaseClient.currentSession?.user?.id ?: ""
            val isRequester = ownerId.isNotBlank() && ownerId == myId
            var myCount = 0
            var pendingRequestId: String? = null
            if (status == "pending") {
                if (isRequester) {
                    val mine = supabaseClient.getTable(
                        "messages",
                        "conversation_id=eq.$conversationId&sender_id=eq.$myId&select=id"
                    )
                    myCount = (mine as? SupabaseResult.Success)?.data?.length() ?: 0
                } else {
                    val req = supabaseClient.getTable(
                        "message_requests",
                        "conversation_id=eq.$conversationId&status=eq.pending&select=id&limit=1"
                    )
                    pendingRequestId = (req as? SupabaseResult.Success)?.data?.optJSONObject(0)?.optString("id")
                }
            }
            ConversationMeta(status, isRequester, myCount, pendingRequestId)
        } catch (e: Exception) {
            Log.w("ChatViewModel", "fetchConversationMeta failed: ${e.message}")
            null
        }
    }

    /** While a request is pending, poll lightly so the requester's banner
     *  clears within seconds of the receiver accepting. */
    private fun startRequestPolling() {
        requestPollingJob?.cancel()
        if (_conversationMeta.value?.requestStatus != "pending") return
        requestPollingJob = viewModelScope.launch {
            while (isActive && _conversationMeta.value?.requestStatus == "pending") {
                delay(12_000)
                _conversationMeta.value = fetchConversationMeta()
            }
        }
    }

    /** RECEIVER: accept the message request → normal chat (presence unlocks). */
    fun acceptMessageRequest() {
        val requestId = _conversationMeta.value?.pendingRequestId ?: return
        viewModelScope.launch {
            _requestActionInProgress.value = "accept"
            try {
                val payload = org.json.JSONObject().put("requestId", requestId).put("action", "accept")
                when (val res = supabaseClient.invokeFunction("respond-message-request", payload)) {
                    is SupabaseResult.Success -> {
                        _conversationMeta.value = fetchConversationMeta()
                        startRequestPolling()
                        // Presence is now visible (RLS unlocked) — pull it immediately.
                        (presenceService as? com.example.service.PresenceServiceImpl)?.refreshPeerPresence(peerId)
                    }
                    is SupabaseResult.Error -> requestNotice.value = "Failed to accept request"
                }
            } finally {
                _requestActionInProgress.value = null
            }
        }
    }

    /** RECEIVER: decline the request. The sender is blocked from sending more;
     *  the receiver can still re-open the chat later by sending a message. */
    fun declineMessageRequest() {
        val requestId = _conversationMeta.value?.pendingRequestId ?: return
        viewModelScope.launch {
            _requestActionInProgress.value = "decline"
            try {
                val payload = org.json.JSONObject().put("requestId", requestId).put("action", "decline")
                when (val res = supabaseClient.invokeFunction("respond-message-request", payload)) {
                    is SupabaseResult.Success -> {
                        _conversationMeta.value = fetchConversationMeta()
                        startRequestPolling()
                    }
                    is SupabaseResult.Error -> requestNotice.value = "Failed to decline request"
                }
            } finally {
                _requestActionInProgress.value = null
            }
        }
    }

    /** RECEIVER: block the requester. Server marks request + canonical
     *  conversation blocked and writes blocked_contacts; locally we drop the
     *  pending row so the chat leaves the Requests tab immediately. */
    fun blockMessageRequest() {
        val requestId = _conversationMeta.value?.pendingRequestId ?: return
        viewModelScope.launch {
            _requestActionInProgress.value = "block"
            try {
                val payload = org.json.JSONObject().put("requestId", requestId).put("action", "block")
                when (val res = supabaseClient.invokeFunction("respond-message-request", payload)) {
                    is SupabaseResult.Success -> {
                        try { repository.deleteLocalConversationRow(conversationId) } catch (_: Exception) {}
                        requestPollingJob?.cancel()
                        _conversationMeta.value = null
                        requestNotice.value = "Blocked"
                    }
                    is SupabaseResult.Error -> requestNotice.value = "Failed to block"
                }
            } finally {
                _requestActionInProgress.value = null
            }
        }
    }

    /** Central client-side gate mirroring the server rules (defense in depth). */
    private fun requestBlockReason(): String? {
        val meta = _conversationMeta.value ?: return null
        return when {
            meta.requestStatus == "pending" && !meta.isRequester ->
                "Accept the message request to reply"
            meta.requestStatus == "pending" && meta.myRequestMessageCount >= 3 ->
                "You can send up to 3 messages while your request is pending"
            meta.requestStatus == "declined" && meta.isRequester ->
                "Your message request was declined"
            else -> null
        }
    }

    // Media & Docs in conversation for Contact Info Sheet
    val mediaMessages = messageService.getMediaMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documentMessages = messageService.getDocumentMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Composer & Interaction state
    var inputText = MutableStateFlow("")
    var replyingTo = MutableStateFlow<DomainMessage?>(null)
    var selectedMessageIds = MutableStateFlow<Set<String>>(emptySet())

    /** Single-flight guard: true from the moment a send starts until the
     *  network settles. UI disables send entry points; sendTextMessage also
     *  re-checks it so a double-tap can never duplicate a message. */
    private val _isSending = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSending: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSending.asStateFlow()

    /** Peer's avatar URL for the chat top bar (fetched from profiles). */
    val peerAvatarUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // Search inside chat — server-side filter chips (All | Media | Documents | Links | Date)
    var isSearchMode = MutableStateFlow(false)
    var inChatSearchQuery = MutableStateFlow("")
    var searchFilter = MutableStateFlow(SearchFilter.ALL)
    var searchResultsEx = MutableStateFlow<List<DomainMessage>>(emptyList())
    val searchResults = inChatSearchQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList())
        else messageService.searchMessages(conversationId, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var currentMatchIndex = MutableStateFlow(0)

    // Conversations available for forwarding
    val allConversations = repository.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Starred messages (for Chat Info)
    var starredMessages = MutableStateFlow<List<DomainMessage>>(emptyList())
    var sharedLinks = MutableStateFlow<List<com.example.service.SharedLink>>(emptyList())

    // Editing state — non-null when an inline edit field is shown for a message
    var editingMessage = MutableStateFlow<DomainMessage?>(null)
    var editingText = MutableStateFlow("")

    // Initial chat positioning (WhatsApp local-first): the chat must open at
    // the NEWEST message exactly ONCE per ViewModel lifetime — the flag lives
    // here so it (a) survives navigation to other screens (returning
    // mid-history must NOT yank the user back to the bottom), (b) resets on
    // process recreation (a fresh open always lands on the newest message,
    // offline included), and (c) gates the load-older trigger so a prepend
    // can never outrun/override the initial scroll (the "opens at the top"
    // bug was exactly this race).
    var initialPositionDone = false
        private set
    fun markInitialPositionDone() { initialPositionDone = true }

    // Voice recording state
    var isRecordingVoice = MutableStateFlow(false)
    var isRecordingLocked = MutableStateFlow(false)
    var recordingDurationSec = MutableStateFlow(0f)
    var recordingAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    var voiceErrorMessage = MutableStateFlow<String?>(null)
    private var recordingTimerJob: Job? = null
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var currentVoiceFile: java.io.File? = null

    // Attachment pre-send modal
    var pendingAttachment = MutableStateFlow<PendingAttachment?>(null)
    var fileSizeErrorMessage = MutableStateFlow<String?>(null)

    // Full screen media viewer
    var activeViewerMessage = MutableStateFlow<DomainMessage?>(null)

    // Voice playback state
    var currentlyPlayingAudioId = MutableStateFlow<String?>(null)
    var audioPlaybackProgress = MutableStateFlow(0f)
    var audioPlaybackError = MutableStateFlow<String?>(null)
    /** Message whose LAST playback attempt failed — the bubble renders the
     *  error inline (previously swallowed, failures were totally silent). */
    var audioPlaybackFailedId = MutableStateFlow<String?>(null)
    private var audioPlaybackJob: Job? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    // ---------- Live location (C7) ----------
    /** MY active share (mirrored from the foreground service, 1 s cadence). */
    val myLiveLocation = MutableStateFlow<LiveLocationShareState?>(null)
    /** The PEER's latest live coordinates (realtime + initial seed). */
    val peerLiveLocation = MutableStateFlow<LiveLocationShareState?>(null)
    private var liveLocationMirrorJob: Job? = null
    private var peerLiveExpiryJob: Job? = null

    init {
        // Task 25: the windowed chat no longer collects the full Room message
        // flow, so the per-conversation Realtime subscription (and its
        // reconnect re-pull) must be established explicitly. Idempotent.
        messageService.ensureRealtimeSubscription(conversationId)
        // Reply-hygiene watcher: whenever the chat's message window changes,
        // re-validate the active reply target. If the quoted message was
        // deleted (for me → row gone, or for everyone → tombstone), drop the
        // reply state — previously a stale reply preview kept quoting a
        // message that no longer existed and the send carried a dead replyToId.
        viewModelScope.launch {
            messages.collect {
                val reply = replyingTo.value ?: return@collect
                try {
                    val fresh = repository.getMessageById(reply.id)
                    if (fresh == null || fresh.isDeletedForEveryone) {
                        replyingTo.value = null
                    }
                } catch (_: Exception) {
                }
            }
        }
        // Message-request meta: pending/accepted/declined + my message budget
        refreshConversationMeta()
        // Header presence: privacy-aware fetch (get-peer-presence honors the
        // peer's last_seen setting + the accepted-conversation gate). Without
        // this the subtitle sat at its default until a realtime event.
        viewModelScope.launch {
            try {
                (presenceService as? com.example.service.PresenceServiceImpl)?.refreshPeerPresence(peerId)
            } catch (_: Exception) {}
        }
        // Catch up the DELIVERED receipt for everything already in Room
        // (history read while the sender was offline still flips to ✓✓).
        viewModelScope.launch {
            try {
                (messageService as? com.example.service.MessageServiceImpl)?.markConversationDelivered(conversationId)
            } catch (_: Exception) {}
        }
        // Top-bar avatar — live from the profiles row (search results may not
        // have carried it; chat entries never did).
        viewModelScope.launch {
            try {
                if (peerId.isNotBlank() && com.example.service.MediaUrlResolver.isUuid(peerId)) {
                    when (val res = AppServiceContainer.supabaseClient.getTable(
                        "profiles",
                        "id=eq.$peerId&select=avatar_url&limit=1"
                    )) {
                        is SupabaseResult.Success -> {
                            val row = res.data.optJSONObject(0)
                            val url = row?.optStringOrNull("avatar_url")
                            if (!url.isNullOrBlank()) {
                                peerAvatarUrl.value = url
                                // Write-back: the dashboard chat list reads the
                                // avatar from conversations.peerAvatarUrl —
                                // persisting the resolved URL here turns the
                                // list's letter fallback into the real photo
                                // without waiting for the next sync pull.
                                try {
                                    repository.updateConversationAvatarUrl(conversationId, url)
                                } catch (_: Exception) {}
                            }
                        }
                        is SupabaseResult.Error -> {}
                    }
                }
            } catch (_: Exception) {}
        }
        // Auto delete local sweep — delete messages that expired while the
        // chat was closed (the server cron covers the server copy; Room needs
        // its own pass on open). No-op when the setting was never activated.
        viewModelScope.launch {
            try {
                repository.getConversationByIdOnce(conversationId)?.let { conv ->
                    val duration = conv.disappearingDuration
                    val stamp = conv.disappearingUpdatedAtMillis
                    if (duration != DisappearingDuration.OFF && stamp != null && stamp > 0L) {
                        repository.setDisappearingDuration(conversationId, duration, stamp)
                    }
                }
            } catch (_: Exception) {}
        }
        // Mark conversation as read on open (calls edge function)
        viewModelScope.launch {
            try {
                messageService.markConversationRead(conversationId)
            } catch (e: Exception) {
                // Fallback to Room-only mark-as-read
                repository.markConversationRead(conversationId)
            }
        }
        // NOTE (WhatsApp local-first model): there is deliberately NO
        // syncMessages() call HERE. Opening a chat renders Room directly —
        // zero waiting on the hot path. The population guarantee lives in
        // MessageWindowController.init: when Room is empty for this
        // conversation it pulls the newest page once (initialPageFromServer),
        // so a fresh install fills its cache deterministically instead of
        // staring at a blank chat. Multi-device catch-up for ALREADY-cached
        // chats runs in the BACKGROUND (conversation pull completion +
        // connectivity regain via backgroundCatchUpSync, watermark-scoped so
        // cached rows are never re-downloaded).
        // Retry any failed messages (offline queue)
        viewModelScope.launch {
            try {
                messageService.retryAllFailedMessages()
            } catch (_: Exception) {}
        }
        // Load starred messages + shared links for Chat Info
        viewModelScope.launch {
            try {
                starredMessages.value = repository.getStarredMessages(conversationId)
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            try {
                sharedLinks.value = messageService.getSharedLinks(conversationId)
            } catch (_: Exception) {}
        }
        // C7: mirror the foreground service state + listen for the peer's
        // realtime live-location coordinates.
        startMyLiveLocationMirror()
        seedPeerLiveLocation()
        (messageService as? MessageServiceImpl)?.onLiveLocationEvent = { event ->
            handlePeerLiveLocationEvent(event)
        }
    }

    private var typingJob: Job? = null

    fun onInputTextChanged(newText: String) {
        inputText.value = newText
        // Debounced typing indicator — one event per idle gap instead of one
        // per keystroke (previously blew through the 30 req/min rate limit).
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(400)
            presenceService.setUserTyping(peerId, newText.isNotBlank())
        }
    }

    /**
     * Sends [text] as an outgoing text message from the media viewer's reply
     * bar. Routes through the same pipeline as the composer (request gating,
     * idempotency, optimistic insert) — the viewer previously discarded the
     * typed reply entirely (send button just closed the viewer).
     */
    fun sendReplyFromViewer(text: String) {
        notifyOutgoingStarted()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        inputText.value = trimmed
        sendTextMessage()
    }

    fun sendTextMessage() {
        notifyOutgoingStarted()
        // Double-send guard: a second tap while a send is in flight is dropped
        // here AND the UI disables the entry points (WhatsApp-style).
        if (_isSending.value) return
        val text = inputText.value.trim()
        if (text.isEmpty()) return

        // Message-request rules (server enforces the same limits)
        requestBlockReason()?.let {
            requestNotice.value = it
            return
        }

        val reply = replyingTo.value
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()
        val idempotencyKey = java.util.UUID.randomUUID().toString()

        val message = DomainMessage(
            id = msgId,
            conversationId = conversationId,
            senderId = "me",
            senderName = "You",
            type = MessageType.TEXT,
            text = text,
            replyToId = reply?.id,
            replyToText = reply?.text,
            replyToSender = reply?.senderName,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true,
            idempotencyKey = idempotencyKey
        )

        // WhatsApp-feel: the typed text and the reply mark leave the composer
        // IMMEDIATELY (optimistic Room insert makes the bubble appear at the
        // same instant). Previously both waited for the full edge-function
        // round-trip, so the text sat in the box and a second tap re-sent it.
        inputText.value = ""
        replyingTo.value = null
        _isSending.value = true

        viewModelScope.launch {
            try {
                messageService.sendMessage(message, peerId = peerId, peerName = contactName)
                // Keep the 3-message request budget accurate while pending.
                if (isMessageRequestPending) {
                    _conversationMeta.value = _conversationMeta.value?.copy(
                        myRequestMessageCount = _conversationMeta.value?.myRequestMessageCount?.plus(1) ?: 1
                    )
                }
                // NO fake/simulated bot reply — real chat uses Supabase Realtime.
                // The receiver will see the message via realtime + can reply for real.
            } finally {
                _isSending.value = false
            }
        }
    }

    // Voice recording methods
    fun startVoiceRecording() {
        // Hard backstop (spec: never touch MediaRecorder without the grant):
        // the UI permission gate calls this only after RECORD_AUDIO is held.
        // If a future call path skips the gate, refuse silently here — the
        // user's next tap on the mic button re-opens the permission dialog.
        val ctx = AppServiceContainer.context
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("ChatViewModel", "startVoiceRecording refused: RECORD_AUDIO not granted (UI gate missed)")
            return
        }
        isRecordingVoice.value = true
        isRecordingLocked.value = false
        recordingDurationSec.value = 0f
        recordingAmplitudes.value = emptyList()

        viewModelScope.launch {
            presenceService.setUserRecording(peerId, true)
        }

        // Real voice recording using MediaRecorder — captures actual audio
        // amplitudes. Phase 3: the recording lands DIRECTLY in the durable
        // Trigger tree (Trigger Voice Notes/Sent, TRG-*.m4a) — NEVER in
        // context.cacheDir — so the file doubles as the archived outgoing copy
        // (survives cache clears, renders file-first, feeds re-upload
        // retries). Recorder setup + file creation run on Dispatchers.IO.
        viewModelScope.launch {
            try {
                val voiceFile = withContext(Dispatchers.IO) {
                    AppServiceContainer.storageManager.newUserMediaFile(
                        TriggerFolder.VOICE_NOTES, isSent = true, "m4a"
                    )
                }
                currentVoiceFile = voiceFile
                val recorder = withContext(Dispatchers.IO) {
                    android.media.MediaRecorder().apply {
                        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(44100)
                        setAudioEncodingBitRate(128000)
                        setOutputFile(voiceFile.absolutePath)
                        prepare()
                        start()
                    }
                }
                mediaRecorder = recorder

                recordingTimerJob = viewModelScope.launch {
                    while (isActive) {
                        delay(100) // Sample every 100ms for smooth waveform
                        recordingDurationSec.value = (recordingDurationSec.value + 0.1f)
                        // Get real amplitude from MediaRecorder (0-32767)
                        val maxAmplitude = try { recorder.maxAmplitude } catch (e: Exception) { 0 }
                        // Normalize to 0.0-1.0 for the waveform UI
                        val amp = (maxAmplitude.toFloat() / 32767f).coerceIn(0f, 1f)
                        recordingAmplitudes.value = (recordingAmplitudes.value + amp).takeLast(100)
                    }
                }
            } catch (e: Exception) {
                // FAIL LOUDLY: no permission / mic busy. Previously this started a
                // fake timer and later uploaded a 0-byte file that could never send.
                Log.e("ChatViewModel", "Voice recording failed: ${e.message}")
                voiceErrorMessage.value = "Couldn't record audio — check mic permission"
                isRecordingVoice.value = false
                isRecordingLocked.value = false
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
                currentVoiceFile?.delete()
                currentVoiceFile = null
            }
        }
    }

    fun clearVoiceError() {
        voiceErrorMessage.value = null
    }

    fun lockVoiceRecording() {
        isRecordingLocked.value = true
    }

    fun cancelVoiceRecording() {
        recordingTimerJob?.cancel()
        // Stop + release MediaRecorder
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        currentVoiceFile?.delete()
        currentVoiceFile = null
        isRecordingVoice.value = false
        isRecordingLocked.value = false
        recordingDurationSec.value = 0f
        recordingAmplitudes.value = emptyList()

        viewModelScope.launch {
            presenceService.setUserRecording(peerId, false)
        }
    }

    fun sendVoiceMessage() {
        notifyOutgoingStarted()
        val duration = recordingDurationSec.value
        val voiceFile = currentVoiceFile
        // Stop recording but DON'T delete the file — we need it for upload
        recordingTimerJob?.cancel()
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        isRecordingVoice.value = false
        isRecordingLocked.value = false

        if (duration < 1) {
            voiceFile?.delete()
            currentVoiceFile = null
            return
        }

        // Recording failed earlier (MediaRecorder error) — do NOT send an
        // empty/invalid file. Fail loudly instead of fabricating a message.
        if (voiceFile == null || !voiceFile.exists() || voiceFile.length() < 1024L) {
            voiceErrorMessage.value = "Recording failed — please try again"
            voiceFile?.delete()
            currentVoiceFile = null
            return
        }

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()
        val idempotencyKey = java.util.UUID.randomUUID().toString()

        val message = DomainMessage(
            id = msgId,
            conversationId = conversationId,
            senderId = "me",
            senderName = "You",
            type = MessageType.AUDIO,
            // Instant self-playback: the local recording is playable while the
            // upload runs; completeMediaUpload swaps in the public server URL
            // when it finishes (previously mediaUrl stayed null and the sender
            // hit the "Audio unavailable" gate on their OWN voice note).
            mediaUrl = voiceFile?.absolutePath,
            // Phase 3: the recording IS the durable archived copy (recorded
            // straight into Trigger Voice Notes/Sent) — persist the path on
            // the row immediately so the sender's player hits the file.
            localMediaPath = voiceFile?.absolutePath,
            mediaDurationSec = duration.toInt(),
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true,
            idempotencyKey = idempotencyKey
        )

        viewModelScope.launch {
            // Stage locally only — send-message is deferred until upload
            // completes so the server row carries the accessible signed URL.
            messageService.stageOutgoingMessage(message)
            // Enqueue real upload with the actual voice file path
            val fileSize = voiceFile?.length() ?: (duration.toLong() * 16000L).coerceAtLeast(1024L)
            val task = UploadTask(
                id = "upload_$msgId",
                messageId = msgId,
                conversationId = conversationId,
                fileName = voiceFile?.name ?: "Voice_note_${System.currentTimeMillis()}.m4a",
                fileType = MessageType.AUDIO,
                totalBytes = fileSize,
                filePath = voiceFile?.absolutePath,
                mimeType = "audio/mp4",
                peerId = peerId,
                peerName = contactName
            )
            uploadService.enqueueUpload(task)
            // The recording already lives in the Trigger tree (Voice Notes /
            // Sent) — it is the durable archived copy, NOT a cache file. Drop
            // only the ViewModel reference; the file stays for file-first
            // playback and re-upload retries.
            currentVoiceFile = null
        }

        viewModelScope.launch {
            presenceService.setUserRecording(peerId, false)
        }
    }

    // Attachment flow with file size verification
    fun selectMediaForPreview(type: MessageType, fileName: String, fileSize: Long, previewUrl: String? = null, previewRes: Int? = null, filePath: String? = null, mimeType: String? = null) {
        val (isValid, errorMsg) = storageService.validateUpload(fileSize, type)
        if (!isValid) {
            fileSizeErrorMessage.value = errorMsg
            return
        }
        fileSizeErrorMessage.value = null
        pendingAttachment.value = PendingAttachment(
            type = type,
            fileName = fileName,
            fileSize = fileSize,
            previewUrl = previewUrl,
            previewRes = previewRes,
            filePath = filePath,
            mimeType = mimeType
        )
    }

    fun updatePendingCaption(caption: String) {
        val current = pendingAttachment.value ?: return
        pendingAttachment.value = current.copy(caption = caption)
    }

    fun togglePendingViewOnce() {
        val current = pendingAttachment.value ?: return
        pendingAttachment.value = current.copy(isViewOnce = !current.isViewOnce)
    }

    fun dismissPendingAttachment() {
        pendingAttachment.value = null
    }

    fun sendPendingAttachment() {
        notifyOutgoingStarted()
        val pending = pendingAttachment.value ?: return
        pendingAttachment.value = null

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()

        viewModelScope.launch {
            // VIDEO: generate the poster frame + real duration BEFORE staging
            // so the sender's bubble (and, once the frame is uploaded, the
            // receiver's) shows a real frame instead of Coil trying — and
            // failing — to decode the video URL as an image.
            var thumbnailPath: String? = null
            var durationSec = pending.durationSec
            val srcPath = pending.filePath
            if (pending.type == MessageType.VIDEO && !srcPath.isNullOrBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        thumbnailPath = com.example.util.MediaCompressor
                            .extractVideoThumbnailBlocking(AppServiceContainer.context, srcPath)
                            ?.absolutePath
                        if (durationSec <= 0) {
                            durationSec = com.example.util.MediaCompressor
                                .extractMediaDurationSecBlocking(AppServiceContainer.context, srcPath)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "video thumbnail/duration failed: ${e.message}")
                }
            }

            // Phase 3: archive the picked content into the Trigger tree
            // (*/Sent/) BEFORE the upload starts — content:// grants are
            // transient and the archived copy (a) renders the sender's bubble
            // from disk instantly, (b) survives cache clears as the
            // re-upload source for retries, (c) outlives the upload itself.
            // HARD GUARD: view-once payloads are NEVER archived — no
            // browsable copy of a view-once media may exist on disk.
            var archivedLocalPath: String? = null
            if (!pending.isViewOnce &&
                pending.type in listOf(
                    MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.DOCUMENT
                ) &&
                !pending.filePath.isNullOrBlank()
            ) {
                try {
                    archivedLocalPath = ChatMediaFolders.archiveOutgoingCopy(
                        sourcePath = pending.filePath,
                        type = pending.type,
                        fileName = pending.fileName,
                        mimeType = pending.mimeType
                    )?.absolutePath
                    if (archivedLocalPath == null) {
                        Log.w("ChatViewModel", "outgoing archive failed for ${pending.fileName} — continuing with URL pipeline")
                    }
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "outgoing archive error: ${e.message}")
                }
            }

            val message = DomainMessage(
                id = msgId,
                conversationId = conversationId,
                senderId = "me",
                senderName = "You",
                type = pending.type,
                text = pending.caption,
                fileName = pending.fileName,
                fileSize = pending.fileSize,
                // Local preview only for the sender's own bubble — the SERVER row
                // is created after upload via completeMediaUpload() with the real
                // URL (recipients can never load a content:// URI).
                mediaUrl = pending.previewUrl,
                // Phase 3: durable archived copy inside the Trigger tree — the
                // sender's row renders/plays from disk immediately. Null when
                // the archive failed (URL pipeline fallback) or view-once.
                localMediaPath = archivedLocalPath,
                mediaThumbnail = thumbnailPath,
                mediaDurationSec = durationSec.coerceAtLeast(0),
                isViewOnce = pending.isViewOnce,
                status = MessageStatus.SENDING,
                timestamp = time,
                timestampMillis = System.currentTimeMillis(),
                isOutgoing = true,
                idempotencyKey = java.util.UUID.randomUUID().toString()
            )

            // Stage locally only — send-message is deferred until upload
            // completes so the server row carries the accessible URL.
            messageService.stageOutgoingMessage(message)
            val task = UploadTask(
                id = "upload_$msgId",
                messageId = msgId,
                conversationId = conversationId,
                fileName = pending.fileName,
                fileType = pending.type,
                totalBytes = pending.fileSize,
                filePath = pending.filePath,
                mimeType = pending.mimeType,
                thumbnailPath = thumbnailPath,
                peerId = peerId,
                peerName = contactName
            )
            uploadService.enqueueUpload(task)
        }
    }

    // Location & Contact sharing
    fun shareLocation(latitude: Double, longitude: Double, placeName: String, address: String) {
        notifyOutgoingStarted()
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val locText = if (placeName.isNotBlank() && placeName != "Current Location") "$placeName\n$address" else address
        val msg = DomainMessage(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = "me",
            senderName = "You",
            type = MessageType.LOCATION,
            text = locText,
            locationLatitude = latitude,
            locationLongitude = longitude,
            locationAddress = address,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true
        )
        viewModelScope.launch {
            messageService.sendMessage(
                msg.copy(idempotencyKey = msg.idempotencyKey ?: java.util.UUID.randomUUID().toString()),
                peerId = peerId, peerName = contactName
            )
        }
    }

    /**
     * Share a live-location message with the REAL current device coordinates.
     *
     * @param latitude  real GPS/NETWORK fix latitude (from SendLocationScreen)
     * @param longitude real GPS/NETWORK fix longitude
     * @param durationText one of "15 minutes" / "1 hour" / "8 hours"
     * @param comment   optional user comment attached to the share
     */
    fun shareLiveLocation(latitude: Double, longitude: Double, durationText: String, comment: String = "") {
        notifyOutgoingStarted()
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val baseText = "Live Location shared ($durationText)"
        val msg = DomainMessage(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = "me",
            senderName = "You",
            type = MessageType.LOCATION,
            text = if (comment.isNotBlank()) "$baseText — $comment" else baseText,
            locationLatitude = latitude,
            locationLongitude = longitude,
            locationAddress = if (comment.isNotBlank())
                "Live • updating ($durationText) — $comment"
            else
                "Live • updating ($durationText)",
            locationLiveMinutes = durationTextToMinutes(durationText),
            locationComment = comment.ifBlank { null },
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true,
            idempotencyKey = java.util.UUID.randomUUID().toString()
        )
        viewModelScope.launch {
            messageService.sendMessage(msg, peerId = peerId, peerName = contactName)
            // C7: start the REAL foreground-service updater — previously only
            // the static anchor message was sent and nothing ever moved.
            try {
                LiveLocationService.start(
                    AppServiceContainer.context,
                    conversationId,
                    durationTextToMinutes(durationText)
                )
            } catch (e: Exception) {
                Log.w("ChatViewModel", "live location service start failed: ${e.message}")
            }
        }
    }

    /** Stops MY active live-location share (banner Stop button). */
    fun stopLiveLocationShare() {
        LiveLocationService.stop(AppServiceContainer.context)
        myLiveLocation.value = null
    }

    // ----- C7 live-location internals -----

    private fun startMyLiveLocationMirror() {
        liveLocationMirrorJob?.cancel()
        liveLocationMirrorJob = viewModelScope.launch {
            while (isActive) {
                val share = LiveLocationService.activeShare
                myLiveLocation.value = share?.takeIf {
                    it.conversationId == conversationId &&
                        it.expiresAtMillis > System.currentTimeMillis()
                }
                delay(1000)
            }
        }
    }

    /** Pulls any still-active peer share so a freshly-opened chat shows it. */
    private fun seedPeerLiveLocation() {
        viewModelScope.launch {
            try {
                val me = AppServiceContainer.supabaseClient.currentSession?.user?.id ?: return@launch
                val result = AppServiceContainer.supabaseClient.getTable(
                    "live_location_shares",
                    "conversation_id=eq.$conversationId&select=*&order=updated_at.desc&limit=5"
                )
                if (result is SupabaseResult.Success) {
                    for (i in 0 until result.data.length()) {
                        val obj = result.data.getJSONObject(i)
                        if (obj.optString("sharer_id") == me) continue
                        val expires = parseInstantMillis(obj.optString("expires_at", ""), 0L)
                        if (expires <= System.currentTimeMillis()) continue
                        peerLiveLocation.value = LiveLocationShareState(
                            conversationId = conversationId,
                            sharerId = obj.optString("sharer_id"),
                            latitude = obj.optDouble("latitude", 0.0),
                            longitude = obj.optDouble("longitude", 0.0),
                            accuracyMeters = obj.optDouble("accuracy", 0.0).takeIf { it > 0 }?.toFloat(),
                            expiresAtMillis = expires,
                            updatedAtMillis = parseInstantMillis(
                                obj.optString("updated_at", ""), System.currentTimeMillis()
                            )
                        )
                        schedulePeerLiveExpiry(expires)
                        break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handlePeerLiveLocationEvent(event: RealtimeEvent) {
        val record = event.record ?: return
        if (record.optString("conversation_id", "") != conversationId) return
        val me = AppServiceContainer.supabaseClient.currentSession?.user?.id ?: ""
        val sharer = record.optString("sharer_id", "")
        if (sharer.isBlank() || sharer == me) return
        val expires = parseInstantMillis(record.optString("expires_at", ""), 0L)
        if (expires <= System.currentTimeMillis()) {
            peerLiveLocation.value = null
            return
        }
        peerLiveLocation.value = LiveLocationShareState(
            conversationId = conversationId,
            sharerId = sharer,
            latitude = record.optDouble("latitude", 0.0),
            longitude = record.optDouble("longitude", 0.0),
            accuracyMeters = record.optDouble("accuracy", 0.0).takeIf { it > 0 }?.toFloat(),
            expiresAtMillis = expires,
            updatedAtMillis = System.currentTimeMillis()
        )
        schedulePeerLiveExpiry(expires)
    }

    private fun schedulePeerLiveExpiry(expiresAtMillis: Long) {
        peerLiveExpiryJob?.cancel()
        peerLiveExpiryJob = viewModelScope.launch {
            delay((expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0) + 500)
            peerLiveLocation.value = null
        }
    }

    private fun parseInstantMillis(iso: String, fallback: Long): Long {
        if (iso.isBlank()) return fallback
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                fallback
            }
        }
    }

    /** Maps the UI duration chips to the server's `location_live_minutes` column. */
    private fun durationTextToMinutes(durationText: String): Int = when (durationText.trim().lowercase()) {
        "15 minutes" -> 15
        "1 hour" -> 60
        "8 hours" -> 480
        else -> 60
    }

    fun setBlocked(isBlocked: Boolean) {
        viewModelScope.launch {
            // Local flag (Room) — hides the chat UI-side immediately.
            repository.setBlocked(conversationId, isBlocked)
            // Task 24: sync the block to the SERVER. Previously this was a
            // local-only flag — the peer could keep sending because neither
            // blocked_contacts nor conversations.request_status ever changed.
            // The server-side gate lives in send-message / send-message-request
            // (blocked_contacts check, both directions).
            if (peerId.isNotBlank() && MediaUrlResolver.isUuid(peerId)) {
                try {
                    AppServiceContainer.supabaseClient.invokeFunction(
                        "manage-blocked-contacts",
                        org.json.JSONObject()
                            .put("action", if (isBlocked) "block" else "unblock")
                            .put("blockedIdentifier", peerId)
                            .put("blockedUserId", peerId)
                    )
                    if (!isBlocked && contactName.isNotBlank() && contactName != peerId) {
                        // Also clear a block that was created from the profile
                        // screen (identifier = display name there).
                        try {
                            AppServiceContainer.supabaseClient.invokeFunction(
                                "manage-blocked-contacts",
                                org.json.JSONObject()
                                    .put("action", "unblock")
                                    .put("blockedIdentifier", contactName)
                            )
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "server block sync failed: ${e.message}")
                }
            }
        }
    }

    fun setMuted(isMuted: Boolean) {
        viewModelScope.launch {
            repository.setConversationMuted(conversationId, isMuted)
        }
    }

    fun shareContact(name: String, phone: String) {
        notifyOutgoingStarted()
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msg = DomainMessage(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = "me",
            senderName = "You",
            type = MessageType.CONTACT,
            contactName = name,
            contactPhone = phone,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true,
            idempotencyKey = java.util.UUID.randomUUID().toString()
        )
        viewModelScope.launch {
            messageService.sendMessage(msg, peerId = peerId, peerName = contactName)
        }
    }

    // View-Once open
    fun openViewOnceMedia(message: DomainMessage) {
        // Once-only, and never for the SENDER: exactly like WhatsApp, the
        // sender cannot re-open (or pre-open) their own view-once media —
        // the server rejects the receipt for senders too.
        if (message.isViewed || message.isOutgoing) return
        activeViewerMessage.value = message
        viewModelScope.launch {
            messageService.markViewOnceOpened(message.id)
        }
    }

    // Calling integrations
    fun startAudioCall() {
        callService.startCall(peerId, contactName, contactAvatarRes, CallType.AUDIO)
    }

    fun startVideoCall() {
        callService.startCall(peerId, contactName, contactAvatarRes, CallType.VIDEO)
    }

    // Message selection & actions
    fun toggleSelectMessage(id: String) {
        val current = selectedMessageIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        selectedMessageIds.value = current
    }

    fun clearSelection() {
        selectedMessageIds.value = emptySet()
    }

    fun replyToSelected(message: DomainMessage) {
        replyingTo.value = message
        clearSelection()
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            messageService.toggleReaction(messageId, emoji)
            clearSelection()
        }
    }

    fun deleteSelectedForMe() {
        val ids = selectedMessageIds.value.toList()
        clearSelection()
        viewModelScope.launch {
            ids.forEach { messageService.deleteForMe(it) }
        }
    }

    /** Delete a single message for me — used by the full-screen media viewer,
     *  which previously called the (empty) selection-based path and did nothing. */
    fun deleteMessageForMe(message: DomainMessage) {
        viewModelScope.launch {
            messageService.deleteForMe(message.id)
        }
    }

    fun deleteSelectedForEveryone() {
        val ids = selectedMessageIds.value.toList()
        clearSelection()
        viewModelScope.launch {
            ids.forEach { messageService.deleteForEveryone(it) }
        }
    }

    fun forwardSelectedTo(targetConversationIds: List<String>) {
        val currentList = messages.value
        // View-once media is NON-FORWARDABLE — forwarding would mint an
        // unbounded copy and defeat the entire feature. (The service layer
        // re-checks this: MessageServiceImpl.forwardMessage is the backstop.)
        val toForward = currentList.filter {
            selectedMessageIds.value.contains(it.id) && !it.isViewOnce
        }
        clearSelection()
        viewModelScope.launch {
            toForward.forEach { msg ->
                messageService.forwardMessage(msg, targetConversationIds)
            }
        }
    }

    fun clearEntireChat() {
        viewModelScope.launch {
            messageService.clearChat(conversationId)
        }
    }

    fun setDisappearingMessages(duration: DisappearingDuration) {
        viewModelScope.launch {
            // 1. Persist on the SHARED server row first (either participant may
            //    change it). disappearing_updated_at is stamped server-side —
            //    the cron only purges messages created after that instant, so
            //    enabling auto delete never deletes older history.
            var activatedAt = System.currentTimeMillis()
            when (val res = supabaseClient.invokeFunction(
                "set-auto-delete",
                org.json.JSONObject()
                    .put("conversationId", conversationId)
                    .put("duration", duration.name)
            )) {
                is SupabaseResult.Success -> {
                    val iso = res.data.optString("activatedAt", "")
                    if (iso.isNotBlank() && iso != "null") {
                        try {
                            activatedAt = java.time.Instant.parse(iso).toEpochMilli()
                        } catch (_: Exception) {}
                    }
                }
                is SupabaseResult.Error -> {
                    requestNotice.value = "Could not update auto delete. Try again."
                    return@launch
                }
            }
            // 2. Local Room: stamp + one-shot sweep (no chat "message" is
            //    inserted — the in-list system notice renders from state).
            repository.setDisappearingDuration(conversationId, duration, activatedAt)
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            messageService.retryFailedMessage(messageId)
        }
    }

    // ---------- Edit message ----------
    fun startEditing(message: DomainMessage) {
        // Doing anything else with a message (edit included) cancels the
        // reply-mark on the spot (user requirement).
        replyingTo.value = null
        selectedMessageIds.value = emptySet()
        editingMessage.value = message
        editingText.value = message.text
    }

    fun cancelEditing() {
        editingMessage.value = null
        editingText.value = ""
    }

    fun saveEdit() {
        val msg = editingMessage.value ?: return
        val newText = editingText.value.trim()
        if (newText.isEmpty()) {
            cancelEditing()
            return
        }
        viewModelScope.launch {
            messageService.editMessage(msg.id, newText)
            cancelEditing()
        }
    }

    // ---------- Pin message ----------
    fun togglePinMessage(message: DomainMessage) {
        viewModelScope.launch {
            messageService.togglePinMessage(message.id)
        }
    }

    // ---------- Star message (single, not via selection) ----------
    fun toggleStarMessage(message: DomainMessage) {
        viewModelScope.launch {
            messageService.toggleStarMessage(message.id)
            // Refresh starred list
            try {
                starredMessages.value = repository.getStarredMessages(conversationId)
            } catch (_: Exception) {}
        }
    }

    // ---------- Report user ----------
    fun reportUser(reportedUserId: String, reason: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                messageService.reportUser(reportedUserId, reason)
            } catch (_: Exception) {}
            onDone()
        }
    }

    // ---------- Archive chat ----------
    fun setArchived(isArchived: Boolean) {
        viewModelScope.launch {
            messageService.toggleArchiveConversation(conversationId, isArchived)
        }
    }

    // ---------- Pagination (Task 25 — bounded window) ----------

    /** Scroll-to-top trigger: grows the window by one page of older messages
     *  (Room cache first; the server only when the cache is exhausted). */
    fun loadOlderMessages() = messageWindow.loadOlderMessages()

    /** Post-jump downward growth toward the live edge. */
    fun loadNewerMessages() = messageWindow.loadNewerMessages()

    /** Reply navigation: guarantees the target is inside the window (deep-
     *  fetching the containing page from Room/server when necessary), then
     *  hands the id to the UI via [pendingJumpTo] for the scroll. */
    fun jumpToMessage(messageId: String) {
        if (messages.value.any { it.id == messageId }) {
            pendingJumpTo.value = messageId
            return
        }
        viewModelScope.launch {
            val ok = try {
                messageWindow.jumpToMessage(messageId)
            } catch (_: Exception) {
                false
            }
            if (ok) pendingJumpTo.value = messageId
            // else: the quote preview still shows the denormalized snapshot —
            // a silent no-op matches the old behavior.
        }
    }

    /** Every optimistic send re-attaches the live edge so the user's own
     *  message always lands in view, even after a history jump. */
    private fun notifyOutgoingStarted() {
        messageWindow.releaseBottom()
    }

    // ---------- Server-side search via search-messages ----------
    fun runSearchEx(filter: SearchFilter, query: String = "", dateFrom: String? = null, dateTo: String? = null) {
        searchFilter.value = filter
        inChatSearchQuery.value = query
        viewModelScope.launch {
            try {
                val type = when (filter) {
                    SearchFilter.ALL -> if (query.isBlank()) "text" else "text"
                    SearchFilter.MEDIA -> "media"
                    SearchFilter.DOCUMENTS -> "documents"
                    SearchFilter.LINKS -> "links"
                    SearchFilter.DATE -> "date"
                }
                val results = messageService.searchMessagesEx(
                    conversationId = conversationId,
                    query = query,
                    searchType = type,
                    dateFrom = dateFrom,
                    dateTo = dateTo
                )
                searchResultsEx.value = results
            } catch (e: Exception) {
                searchResultsEx.value = emptyList()
            }
        }
    }

    // ---------- Refresh starred + shared links (called when Chat Info opens) ----------
    fun refreshChatInfo() {
        viewModelScope.launch {
            try {
                starredMessages.value = repository.getStarredMessages(conversationId)
            } catch (_: Exception) {}
            try {
                sharedLinks.value = messageService.getSharedLinks(conversationId)
            } catch (_: Exception) {}
        }
    }

    // ---------- REAL audio playback ----------
    // Streams the message's mediaUrl and reports real position-based progress.
    // Accepted sources: https(s) (signed/public server URLs), content:// (the
    // sender's fresh recording) and plain absolute file paths (voice notes
    // before their upload completes). Previously anything not starting with
    // "http" was rejected — the sender could never play their OWN voice note.
    fun togglePlayVoice(message: DomainMessage) {
        if (currentlyPlayingAudioId.value == message.id) {
            stopVoicePlayback(resetProgress = true)
            return
        }

        val url = message.mediaUrl
        if (url.isNullOrBlank()) {
            audioPlaybackError.value = "Audio unavailable"
            audioPlaybackFailedId.value = message.id
            return
        }

        stopVoicePlayback(resetProgress = true)
        currentlyPlayingAudioId.value = message.id
        audioPlaybackProgress.value = 0f
        audioPlaybackError.value = null
        audioPlaybackFailedId.value = null

        audioPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            var player: android.media.MediaPlayer? = null
            try {
                // Task 24: resolve a PLAYABLE source — a previously cached
                // local copy wins (offline replay, zero re-download), otherwise
                // a short-lived signed URL is minted (and cached for next time).
                val url = resolvePlayableAudioUrl(message)
                    ?: throw IllegalStateException("no playable audio source")
                player = android.media.MediaPlayer()
                mediaPlayer = player
                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                if (url.startsWith("content://")) {
                    player.setDataSource(AppServiceContainer.context, android.net.Uri.parse(url))
                } else {
                    // MediaPlayer.setDataSource handles BOTH https(s) URLs and
                    // absolute local file paths.
                    player.setDataSource(url)
                }
                player.setOnCompletionListener {
                    audioPlaybackProgress.value = 1f
                    stopVoicePlayback(resetProgress = false)
                    currentlyPlayingAudioId.value = null
                    audioPlaybackProgress.value = 0f
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.w("ChatViewModel", "Audio player error: what=$what extra=$extra")
                    if (isActive) {
                        audioPlaybackError.value = "Playback failed"
                        audioPlaybackFailedId.value = message.id
                        stopVoicePlayback(resetProgress = false)
                    }
                    true
                }
                try {
                    player.prepare()  // blocking — we're on Dispatchers.IO
                } catch (firstAttempt: Exception) {
                    // Task 24: the bubble's signed URL (1 h TTL) may have
                    // expired between Room read and play. Force-refresh the
                    // signature once and retry before surfacing an error.
                    player.reset()
                    val freshUrl = resolvePlayableAudioUrl(message, forceRefresh = true)
                    if (freshUrl != null) {
                        if (freshUrl.startsWith("content://")) {
                            player.setDataSource(AppServiceContainer.context, android.net.Uri.parse(freshUrl))
                        } else {
                            player.setDataSource(freshUrl)
                        }
                        player.prepare()
                    } else {
                        throw firstAttempt
                    }
                }
                player.start()

                val durationMs = player.duration.coerceAtLeast(1)
                while (isActive && player.isPlaying) {
                    audioPlaybackProgress.value =
                        (player.currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
                    delay(100)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w("ChatViewModel", "Audio playback failed: ${e.message}")
                    audioPlaybackError.value = "Playback failed"
                    audioPlaybackFailedId.value = message.id
                    stopVoicePlayback(resetProgress = false)
                }
            }
        }
    }

    /** Resolves a PLAYABLE audio source for a voice message (Task 24):
     *  - local sources (content://, recorded file) pass through untouched;
     *  - Phase 3: the durable on-device archive copy (messages.localMediaPath,
     *    Trigger Voice Notes tree) wins whenever the file exists — instant
     *    start, offline replay, zero re-download;
     *  - otherwise a short-lived signed URL is minted and streamed directly
     *    (the old ephemeral voice-cache staging is REMOVED — received notes are
     *    archived by MessageServiceImpl into the Trigger tree, so the player
     *    streams the URL at most once per note and every later play hits the
     *    durable file);
     *  - [forceRefresh] bypasses any memoized signature (expired-URL retry). */
    private suspend fun resolvePlayableAudioUrl(
        message: DomainMessage,
        forceRefresh: Boolean = false
    ): String? {
        // Phase 3: the durable archive copy wins when it actually exists.
        message.localMediaPath?.takeIf { it.isNotBlank() }?.let { path ->
            val archived = java.io.File(path)
            if (archived.exists() && archived.length() > 0L) return path
        }
        val raw = message.mediaUrl ?: return null
        if (!raw.startsWith("http")) return raw // content:// or local file
        val bucket = message.mediaBucket
        val objectPath = message.mediaPath
            ?: MediaUrlResolver.extractObjectPath(raw, bucket ?: MediaUrlResolver.CHAT_MEDIA_BUCKET)
        if (bucket == null || !MediaUrlResolver.isPrivateBucket(bucket) || objectPath == null) {
            return MediaUrlResolver.resolveWithRefresh(raw, bucket, message.mediaPath, forceRefresh = forceRefresh) ?: raw
        }
        // No archive yet (download still in flight or failed): stream the
        // signed URL. resolveWithRefresh falls back to the last known
        // signature while offline — no cacheDir staging anymore.
        return MediaUrlResolver.resolveWithRefresh(raw, bucket, objectPath, forceRefresh = forceRefresh)
    }

    /** Seeks the CURRENTLY PLAYING message's audio to [fraction] (0..1) —
     *  wired to taps on the bubble's waveform. Ignored for other messages. */
    fun seekAudioTo(messageId: String, fraction: Float) {
        if (currentlyPlayingAudioId.value != messageId) return
        val player = mediaPlayer ?: return
        try {
            val duration = player.duration
            if (duration > 0) {
                player.seekTo((duration * fraction.coerceIn(0f, 1f)).toInt())
                audioPlaybackProgress.value = fraction.coerceIn(0f, 1f)
            }
        } catch (_: Exception) {
            // Player already released mid-tap — harmless.
        }
    }

    private fun stopVoicePlayback(resetProgress: Boolean) {
        audioPlaybackJob?.cancel()
        audioPlaybackJob = null
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        if (resetProgress) {
            currentlyPlayingAudioId.value = null
            audioPlaybackProgress.value = 0f
        }
    }

    override fun onCleared() {
        // The screen is gone — release hardware + cancel timers. Previously
        // the mic stayed held and the amplitude timer kept polling after the
        // user left the chat.
        recordingTimerJob?.cancel()
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        currentVoiceFile?.delete()
        currentVoiceFile = null
        stopVoicePlayback(resetProgress = false)
        typingJob?.cancel()
        super.onCleared()
    }
}

enum class SearchFilter { ALL, MEDIA, DOCUMENTS, LINKS, DATE }
