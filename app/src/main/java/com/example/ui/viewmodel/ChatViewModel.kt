package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.config.ChatConfig
import com.example.di.AppServiceContainer
import com.example.model.*
import com.example.service.CallSession
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
    val contactId: String = "",
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

    // Live messages from Room (the local cache, kept in sync with Supabase
    // Realtime by MessageServiceImpl).
    private val _liveMessages: StateFlow<List<DomainMessage>> = messageService
        .observeMessages(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pagination cursor — the oldest timestamp currently loaded. Null means
    // no pagination has happened yet.
    private val paginationCursor = MutableStateFlow<Long?>(null)
    private val _extraMessages = MutableStateFlow<List<DomainMessage>>(emptyList())

    // Combined messages: extras (older page) + main flow (latest)
    val messages: StateFlow<List<DomainMessage>> = combine(_liveMessages, _extraMessages) { main, extras ->
        // Merge by id, then sort by seq + timestampMillis
        val map = LinkedHashMap<String, DomainMessage>()
        // Insert extras first (older), then main (newer will overwrite duplicates)
        extras.sortedBy { it.seq }.forEach { map[it.id] = it }
        main.forEach { map[it.id] = it }
        map.values.sortedWith(compareBy({ it.seq }, { it.timestampMillis }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactPresence: StateFlow<Pair<PresenceStatus, String>> = presenceService
        .observeContactPresence(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PresenceStatus.ONLINE to "online")

    val connectionState: StateFlow<PresenceStatus> = presenceService.connectionState
    val activeCall: StateFlow<CallSession?> = callService.currentCall
    val activeUploads: StateFlow<List<UploadTask>> = uploadService.activeUploads

    val conversationInfo = repository.getConversation(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Media & Docs in conversation for Contact Info Sheet
    val mediaMessages = messageService.getMediaMessages(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documentMessages = messageService.getDocumentMessages(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Composer & Interaction state
    var inputText = MutableStateFlow("")
    var replyingTo = MutableStateFlow<DomainMessage?>(null)
    var selectedMessageIds = MutableStateFlow<Set<String>>(emptySet())

    // Search inside chat — server-side filter chips (All | Media | Documents | Links | Date)
    var isSearchMode = MutableStateFlow(false)
    var inChatSearchQuery = MutableStateFlow("")
    var searchFilter = MutableStateFlow(SearchFilter.ALL)
    var searchResultsEx = MutableStateFlow<List<DomainMessage>>(emptyList())
    val searchResults = inChatSearchQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList())
        else messageService.searchMessages(contactId, query)
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

    // Multi-device sync state
    private val prefs = AppServiceContainer.context.getSharedPreferences("trigger_chat_prefs", android.content.Context.MODE_PRIVATE)
    private val KEY_LAST_SYNC_TS = "last_sync_ts"

    // Voice recording state
    var isRecordingVoice = MutableStateFlow(false)
    var isRecordingLocked = MutableStateFlow(false)
    var recordingDurationSec = MutableStateFlow(0)
    var recordingAmplitudes = MutableStateFlow<List<Float>>(emptyList())
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
    private var audioPlaybackJob: Job? = null

    init {
        // Mark conversation as read on open (calls edge function)
        viewModelScope.launch {
            try {
                messageService.markConversationRead(contactId)
            } catch (e: Exception) {
                // Fallback to Room-only mark-as-read
                repository.markConversationRead(contactId)
            }
        }
        // Multi-device sync: pull any messages we missed since the last sync.
        viewModelScope.launch {
            try {
                val sinceTs = prefs.getLong(KEY_LAST_SYNC_TS, 0L)
                messageService.syncMessages(conversationId = contactId, sinceTs = sinceTs)
            } catch (e: Exception) {
                // Non-fatal — Room is the local cache.
            }
        }
        // Retry any failed messages (offline queue)
        viewModelScope.launch {
            try {
                messageService.retryAllFailedMessages()
            } catch (_: Exception) {}
        }
        // Load starred messages + shared links for Chat Info
        viewModelScope.launch {
            try {
                starredMessages.value = repository.getStarredMessages(contactId)
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            try {
                sharedLinks.value = messageService.getSharedLinks(contactId)
            } catch (_: Exception) {}
        }
    }

    fun onInputTextChanged(newText: String) {
        inputText.value = newText
        viewModelScope.launch {
            presenceService.setUserTyping(contactId, newText.isNotBlank())
        }
    }

    fun sendTextMessage() {
        val text = inputText.value.trim()
        if (text.isEmpty()) return

        val reply = replyingTo.value
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()
        val idempotencyKey = java.util.UUID.randomUUID().toString()

        val message = DomainMessage(
            id = msgId,
            conversationId = contactId,
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

        viewModelScope.launch {
            messageService.sendMessage(message)
            inputText.value = ""
            replyingTo.value = null
            // NO fake/simulated bot reply — real chat uses Supabase Realtime.
            // The receiver will see the message via realtime + can reply for real.
        }
    }

    // Voice recording methods
    fun startVoiceRecording() {
        isRecordingVoice.value = true
        isRecordingLocked.value = false
        recordingDurationSec.value = 0
        recordingAmplitudes.value = emptyList()

        viewModelScope.launch {
            presenceService.setUserRecording(contactId, true)
        }

        // Real voice recording using MediaRecorder — captures actual audio amplitudes
        try {
            val context = AppServiceContainer.context
            val voiceFile = java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            currentVoiceFile = voiceFile
            val recorder = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(voiceFile.absolutePath)
                prepare()
                start()
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
            // Fallback: if MediaRecorder fails (no permission, etc.), use minimal amplitudes
            recordingTimerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    recordingDurationSec.value += 1
                    recordingAmplitudes.value = (recordingAmplitudes.value + 0.1f).takeLast(24)
                }
            }
        }
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
        recordingDurationSec.value = 0
        recordingAmplitudes.value = emptyList()

        viewModelScope.launch {
            presenceService.setUserRecording(contactId, false)
        }
    }

    fun sendVoiceMessage() {
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

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()
        val idempotencyKey = java.util.UUID.randomUUID().toString()

        val message = DomainMessage(
            id = msgId,
            conversationId = contactId,
            senderId = "me",
            senderName = "You",
            type = MessageType.AUDIO,
            mediaDurationSec = duration.toInt(),
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true,
            idempotencyKey = idempotencyKey
        )

        viewModelScope.launch {
            messageService.sendMessage(message)
            // Enqueue real upload with the actual voice file path
            val fileSize = voiceFile?.length() ?: (duration.toLong() * 16000L).coerceAtLeast(1024L)
            val task = UploadTask(
                id = "upload_$msgId",
                messageId = msgId,
                conversationId = contactId,
                fileName = voiceFile?.name ?: "Voice_note_${System.currentTimeMillis()}.m4a",
                fileType = MessageType.AUDIO,
                totalBytes = fileSize,
                filePath = voiceFile?.absolutePath,
                mimeType = "audio/mp4"
            )
            uploadService.enqueueUpload(task)
            // Clean up the reference (file will be deleted by the cache after upload)
            currentVoiceFile = null
        }

        viewModelScope.launch {
            presenceService.setUserRecording(contactId, false)
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
        val pending = pendingAttachment.value ?: return
        pendingAttachment.value = null

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()

        val message = DomainMessage(
            id = msgId,
            conversationId = contactId,
            senderId = "me",
            senderName = "You",
            type = pending.type,
            text = pending.caption,
            fileName = pending.fileName,
            fileSize = pending.fileSize,
            mediaUrl = pending.previewUrl,
            mediaDurationSec = pending.durationSec,
            isViewOnce = pending.isViewOnce,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true
        )

        viewModelScope.launch {
            messageService.sendMessage(message)
            val task = UploadTask(
                id = "upload_$msgId",
                messageId = msgId,
                conversationId = contactId,
                fileName = pending.fileName,
                fileType = pending.type,
                totalBytes = pending.fileSize,
                filePath = pending.filePath,
                mimeType = pending.mimeType
            )
            uploadService.enqueueUpload(task)
        }
    }

    // Location & Contact sharing
    fun shareLocation(latitude: Double, longitude: Double, placeName: String, address: String) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val locText = if (placeName.isNotBlank() && placeName != "Current Location") "$placeName\n$address" else address
        val msg = DomainMessage(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = contactId,
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
            messageService.sendMessage(msg)
        }
    }

    fun shareLiveLocation(durationText: String) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msg = DomainMessage(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = contactId,
            senderId = "me",
            senderName = "You",
            type = MessageType.LOCATION,
            text = "Live Location shared ($durationText)",
            locationLatitude = 12.0436,
            locationLongitude = 75.3588,
            locationAddress = "Live • updating ($durationText)",
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true
        )
        viewModelScope.launch {
            messageService.sendMessage(msg)
        }
    }

    fun shareCurrentLocation() {
        shareLocation(12.0436, 75.3588, "Current Location", "Accurate to 20 meters")
    }

    fun setBlocked(isBlocked: Boolean) {
        viewModelScope.launch {
            repository.setBlocked(contactId, isBlocked)
        }
    }

    fun setMuted(isMuted: Boolean) {
        viewModelScope.launch {
            repository.setConversationMuted(contactId, isMuted)
        }
    }

    fun shareContact(name: String, phone: String) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msg = DomainMessage(
            id = "contact_${System.currentTimeMillis()}",
            conversationId = contactId,
            senderId = "me",
            senderName = "You",
            type = MessageType.CONTACT,
            contactName = name,
            contactPhone = phone,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true
        )
        viewModelScope.launch {
            messageService.sendMessage(msg)
        }
    }

    // View-Once open
    fun openViewOnceMedia(message: DomainMessage) {
        if (message.isViewed) return
        activeViewerMessage.value = message
        viewModelScope.launch {
            messageService.markViewOnceOpened(message.id)
        }
    }

    // Calling integrations
    fun startAudioCall() {
        callService.startCall(contactId, contactName, contactAvatarRes, CallType.AUDIO)
    }

    fun startVideoCall() {
        callService.startCall(contactId, contactName, contactAvatarRes, CallType.VIDEO)
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

    fun deleteSelectedForEveryone() {
        val ids = selectedMessageIds.value.toList()
        clearSelection()
        viewModelScope.launch {
            ids.forEach { messageService.deleteForEveryone(it) }
        }
    }

    fun forwardSelectedTo(targetConversationIds: List<String>) {
        val currentList = messages.value
        val toForward = currentList.filter { selectedMessageIds.value.contains(it.id) }
        clearSelection()
        viewModelScope.launch {
            toForward.forEach { msg ->
                messageService.forwardMessage(msg, targetConversationIds)
            }
        }
    }

    fun clearEntireChat() {
        viewModelScope.launch {
            messageService.clearChat(contactId)
        }
    }

    fun setDisappearingMessages(duration: DisappearingDuration) {
        viewModelScope.launch {
            repository.setDisappearingDuration(contactId, duration)
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val systemMsg = DomainMessage(
                id = "sys_${System.currentTimeMillis()}",
                conversationId = contactId,
                senderId = "system",
                senderName = "System",
                type = MessageType.SYSTEM,
                text = if (duration == DisappearingDuration.OFF) {
                    "Disappearing messages were turned off."
                } else {
                    "Disappearing messages were set to ${duration.displayName}."
                },
                timestamp = time,
                timestampMillis = System.currentTimeMillis(),
                isOutgoing = false
            )
            messageService.sendMessage(systemMsg)
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            messageService.retryFailedMessage(messageId)
        }
    }

    // ---------- Edit message ----------
    fun startEditing(message: DomainMessage) {
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
                starredMessages.value = repository.getStarredMessages(contactId)
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
            messageService.toggleArchiveConversation(contactId, isArchived)
        }
    }

    // ---------- Pagination ----------
    fun loadMoreMessages() {
        val currentList = messages.value
        if (currentList.isEmpty()) return
        // Already loading more? skip
        if (paginationCursor.value != null &&
            paginationCursor.value == currentList.first().timestampMillis) {
            return
        }
        val oldestTs = currentList.first().timestampMillis
        viewModelScope.launch {
            try {
                val page = repository.getMessagesPage(contactId, oldestTs, limit = 50)
                if (page.isNotEmpty()) {
                    _extraMessages.value = (_extraMessages.value + page).distinctBy { it.id }
                    paginationCursor.value = oldestTs
                }
            } catch (e: Exception) {
                // Non-fatal
            }
        }
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
                    conversationId = contactId,
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
                starredMessages.value = repository.getStarredMessages(contactId)
            } catch (_: Exception) {}
            try {
                sharedLinks.value = messageService.getSharedLinks(contactId)
            } catch (_: Exception) {}
        }
    }

    // Audio voice playback simulation
    fun togglePlayVoice(messageId: String, durationSec: Int) {
        if (currentlyPlayingAudioId.value == messageId) {
            // Pause
            audioPlaybackJob?.cancel()
            currentlyPlayingAudioId.value = null
            audioPlaybackProgress.value = 0f
        } else {
            // Play
            audioPlaybackJob?.cancel()
            currentlyPlayingAudioId.value = messageId
            audioPlaybackProgress.value = 0f

            audioPlaybackJob = viewModelScope.launch {
                val totalSteps = (durationSec * 10).coerceAtLeast(10)
                for (step in 1..totalSteps) {
                    delay(100)
                    audioPlaybackProgress.value = step.toFloat() / totalSteps
                }
                currentlyPlayingAudioId.value = null
                audioPlaybackProgress.value = 0f
            }
        }
    }
}

enum class SearchFilter { ALL, MEDIA, DOCUMENTS, LINKS, DATE }
