package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.config.ChatConfig
import com.example.di.AppServiceContainer
import com.example.model.*
import com.example.service.CallSession
import com.example.service.LiveLocationService
import com.example.service.LiveLocationShareState
import com.example.service.MessageServiceImpl
import com.example.service.supabase.RealtimeEvent
import com.example.service.supabase.SupabaseResult
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

    // Live messages from Room (the local cache, kept in sync with Supabase
    // Realtime by MessageServiceImpl).
    private val _liveMessages: StateFlow<List<DomainMessage>> = messageService
        .observeMessages(conversationId)
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
        .observeContactPresence(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PresenceStatus.ONLINE to "online")

    val connectionState: StateFlow<PresenceStatus> = presenceService.connectionState
    val activeCall: StateFlow<CallSession?> = callService.currentCall
    val activeUploads: StateFlow<List<UploadTask>> = uploadService.activeUploads

    val conversationInfo = repository.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Media & Docs in conversation for Contact Info Sheet
    val mediaMessages = messageService.getMediaMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documentMessages = messageService.getDocumentMessages(conversationId)
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

    // Multi-device sync state
    private val prefs = AppServiceContainer.context.getSharedPreferences("trigger_chat_prefs", android.content.Context.MODE_PRIVATE)
    private val KEY_LAST_SYNC_TS = "last_sync_ts"

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
        // Mark conversation as read on open (calls edge function)
        viewModelScope.launch {
            try {
                messageService.markConversationRead(conversationId)
            } catch (e: Exception) {
                // Fallback to Room-only mark-as-read
                repository.markConversationRead(conversationId)
            }
        }
        // Multi-device sync: pull any messages we missed since the last sync.
        viewModelScope.launch {
            try {
                val sinceTs = prefs.getLong(KEY_LAST_SYNC_TS, 0L)
                messageService.syncMessages(conversationId = conversationId, sinceTs = sinceTs)
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

    fun sendTextMessage() {
        val text = inputText.value.trim()
        if (text.isEmpty()) return

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

        viewModelScope.launch {
            messageService.sendMessage(message, peerId = peerId, peerName = contactName)
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
        recordingDurationSec.value = 0f
        recordingAmplitudes.value = emptyList()

        viewModelScope.launch {
            presenceService.setUserRecording(peerId, true)
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
            // The cache dir file can now be cleaned up on our side once the
            // upload job has its own path reference.
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
        val pending = pendingAttachment.value ?: return
        pendingAttachment.value = null

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = java.util.UUID.randomUUID().toString()

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
            // signed URL (recipients can never load a content:// URI).
            mediaUrl = pending.previewUrl,
            mediaDurationSec = pending.durationSec,
            isViewOnce = pending.isViewOnce,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true,
            idempotencyKey = java.util.UUID.randomUUID().toString()
        )

        viewModelScope.launch {
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
                peerId = peerId,
                peerName = contactName
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
            repository.setBlocked(conversationId, isBlocked)
        }
    }

    fun setMuted(isMuted: Boolean) {
        viewModelScope.launch {
            repository.setConversationMuted(conversationId, isMuted)
        }
    }

    fun shareContact(name: String, phone: String) {
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
        if (message.isViewed) return
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
            messageService.clearChat(conversationId)
        }
    }

    fun setDisappearingMessages(duration: DisappearingDuration) {
        viewModelScope.launch {
            repository.setDisappearingDuration(conversationId, duration)
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val systemMsg = DomainMessage(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = conversationId,
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
            messageService.sendMessage(systemMsg, peerId = peerId, peerName = contactName)
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
                val page = repository.getMessagesPage(conversationId, oldestTs, limit = 50)
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
    // Previously this "played" by animating a progress bar with delay() —
    // no MediaPlayer existed anywhere in the app. Now streams the message's
    // mediaUrl (signed URL) and reports real position-based progress.
    fun togglePlayVoice(message: DomainMessage) {
        if (currentlyPlayingAudioId.value == message.id) {
            stopVoicePlayback(resetProgress = true)
            return
        }

        val url = message.mediaUrl
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            audioPlaybackError.value = "Audio unavailable"
            return
        }

        stopVoicePlayback(resetProgress = true)
        currentlyPlayingAudioId.value = message.id
        audioPlaybackProgress.value = 0f
        audioPlaybackError.value = null

        audioPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            var player: android.media.MediaPlayer? = null
            try {
                player = android.media.MediaPlayer()
                mediaPlayer = player
                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                player.setDataSource(url)
                player.setOnCompletionListener {
                    audioPlaybackProgress.value = 1f
                    stopVoicePlayback(resetProgress = false)
                    currentlyPlayingAudioId.value = null
                    audioPlaybackProgress.value = 0f
                }
                player.prepare()  // blocking — we're on Dispatchers.IO
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
                    stopVoicePlayback(resetProgress = true)
                }
            }
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
