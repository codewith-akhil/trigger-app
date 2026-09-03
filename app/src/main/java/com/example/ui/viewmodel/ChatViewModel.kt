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
    val durationSec: Int = 0
)

class ChatViewModel(
    val contactId: String = "darling",
    val contactName: String = "darling",
    val contactAvatarRes: Int? = null
) : ViewModel() {

    private val messageService = AppServiceContainer.messageService
    private val uploadService = AppServiceContainer.uploadService
    private val presenceService = AppServiceContainer.presenceService
    private val callService = AppServiceContainer.callService
    private val storageService = AppServiceContainer.storageService
    private val repository = AppServiceContainer.chatRepository

    val messages: StateFlow<List<DomainMessage>> = messageService
        .observeMessages(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // Search inside chat
    var isSearchMode = MutableStateFlow(false)
    var inChatSearchQuery = MutableStateFlow("")
    val searchResults = inChatSearchQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList())
        else messageService.searchMessages(contactId, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var currentMatchIndex = MutableStateFlow(0)

    // Voice recording state
    var isRecordingVoice = MutableStateFlow(false)
    var isRecordingLocked = MutableStateFlow(false)
    var recordingDurationSec = MutableStateFlow(0)
    var recordingAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    private var recordingTimerJob: Job? = null

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
        // Mark conversation as read on open
        viewModelScope.launch {
            repository.markConversationRead(contactId)
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
        val msgId = "msg_${System.currentTimeMillis()}"

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
            isOutgoing = true
        )

        viewModelScope.launch {
            messageService.sendMessage(message)
            inputText.value = ""
            replyingTo.value = null

            // Simulated realistic response from contact if connection is online
            if (connectionState.value != PresenceStatus.OFFLINE) {
                simulateContactResponse(text)
            }
        }
    }

    private fun simulateContactResponse(incomingPrompt: String) {
        viewModelScope.launch {
            delay(1000)
            presenceService.setContactPresence(contactId, PresenceStatus.TYPING, "typing...")

            delay(1800)
            presenceService.setContactPresence(contactId, PresenceStatus.ONLINE, "online")

            val replyText = when {
                incomingPrompt.contains("?", ignoreCase = true) -> "Definitely! I'll be there right on time! 😊"
                incomingPrompt.contains("love", ignoreCase = true) || incomingPrompt.contains("❤️") -> "Love you more! ❤️✨"
                incomingPrompt.contains("where", ignoreCase = true) -> "Right by the main entrance. Waiting for you!"
                incomingPrompt.contains("late", ignoreCase = true) -> "No worries at all, take your time! 💕"
                incomingPrompt.contains("food", ignoreCase = true) || incomingPrompt.contains("eat", ignoreCase = true) -> "Fresh pasta sounds heavenly today 🍝"
                else -> "Got it! Sounds like a great plan 😊"
            }

            val replyTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val replyMsg = DomainMessage(
                id = "msg_reply_${System.currentTimeMillis()}",
                conversationId = contactId,
                senderId = contactId,
                senderName = contactName,
                type = MessageType.TEXT,
                text = replyText,
                status = MessageStatus.READ,
                timestamp = replyTime,
                timestampMillis = System.currentTimeMillis(),
                isOutgoing = false
            )
            messageService.sendMessage(replyMsg)
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

        recordingTimerJob = viewModelScope.launch {
            val random = Random()
            while (isActive) {
                delay(1000)
                recordingDurationSec.value += 1
                val amp = 0.2f + random.nextFloat() * 0.8f
                recordingAmplitudes.value = (recordingAmplitudes.value + amp).takeLast(24)
            }
        }
    }

    fun lockVoiceRecording() {
        isRecordingLocked.value = true
    }

    fun cancelVoiceRecording() {
        recordingTimerJob?.cancel()
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
        cancelVoiceRecording()
        if (duration < 1) return

        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgId = "voice_${System.currentTimeMillis()}"

        val message = DomainMessage(
            id = msgId,
            conversationId = contactId,
            senderId = "me",
            senderName = "You",
            type = MessageType.AUDIO,
            mediaDurationSec = duration,
            status = MessageStatus.SENDING,
            timestamp = time,
            timestampMillis = System.currentTimeMillis(),
            isOutgoing = true
        )

        viewModelScope.launch {
            messageService.sendMessage(message)
            // Enqueue upload
            val task = UploadTask(
                id = "upload_$msgId",
                messageId = msgId,
                conversationId = contactId,
                fileName = "Voice_note_${System.currentTimeMillis()}.m4a",
                fileType = MessageType.AUDIO,
                totalBytes = (duration * 32 * 1024L).coerceAtLeast(64 * 1024L)
            )
            uploadService.enqueueUpload(task)
        }
    }

    // Attachment flow with file size verification
    fun selectMediaForPreview(type: MessageType, fileName: String, fileSize: Long, previewUrl: String? = null, previewRes: Int? = null) {
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
            previewRes = previewRes
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
        val msgId = "media_${System.currentTimeMillis()}"

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
                totalBytes = pending.fileSize
            )
            uploadService.enqueueUpload(task)
        }
    }

    // Location & Contact sharing
    fun shareLocation(latitude: Double, longitude: Double, placeName: String, address: String) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val locText = if (placeName.isNotBlank() && placeName != "Current Location") "$placeName\n$address" else address
        val msg = DomainMessage(
            id = "loc_${System.currentTimeMillis()}",
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
            id = "loc_live_${System.currentTimeMillis()}",
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
