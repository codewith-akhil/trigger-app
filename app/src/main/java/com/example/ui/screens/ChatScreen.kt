package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.di.AppServiceContainer
import com.example.model.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactId: String = "",
    contactName: String = "",
    contactAvatarRes: Int? = null,
    onBack: () -> Unit
) {
    val viewModel = remember(contactId) {
        ChatViewModel(contactId, contactName, contactAvatarRes)
    }

    val messages by viewModel.messages.collectAsState()
    val presence by viewModel.contactPresence.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val activeUploads by viewModel.activeUploads.collectAsState()
    val conversationInfo by viewModel.conversationInfo.collectAsState()

    val mediaMessages by viewModel.mediaMessages.collectAsState()
    val documentMessages by viewModel.documentMessages.collectAsState()

    val inputText by viewModel.inputText.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val selectedIds by viewModel.selectedMessageIds.collectAsState()

    val isSearchMode by viewModel.isSearchMode.collectAsState()
    val inChatSearchQuery by viewModel.inChatSearchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    val isRecordingVoice by viewModel.isRecordingVoice.collectAsState()
    val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
    val recordingAmplitudes by viewModel.recordingAmplitudes.collectAsState()

    val pendingAttachment by viewModel.pendingAttachment.collectAsState()
    val fileSizeError by viewModel.fileSizeErrorMessage.collectAsState()

    val activeViewerMessage by viewModel.activeViewerMessage.collectAsState()
    val currentlyPlayingAudioId by viewModel.currentlyPlayingAudioId.collectAsState()
    val audioProgress by viewModel.audioPlaybackProgress.collectAsState()

    val context = LocalContext.current
    val isBlocked = conversationInfo?.isBlocked ?: false

    var showContactInfoSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showSendLocationScreen by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showUnblockDialog by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showMuteDialog by remember { mutableStateOf(false) }
    var showReactionPickerForId by remember { mutableStateOf<String?>(null) }
    var isEmojiPickerOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Real Camera capture launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            try {
                val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                val uri = Uri.fromFile(file)
                viewModel.selectMediaForPreview(
                    type = MessageType.IMAGE,
                    fileName = file.name,
                    fileSize = file.length().coerceAtLeast(1024L),
                    previewUrl = uri.toString()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraLauncher.launch(null)
        }
    }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(null)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Real Gallery picker (Photo & Video)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri, "gallery_${System.currentTimeMillis()}.jpg")
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video") || name.endsWith(".mp4", ignoreCase = true)
            viewModel.selectMediaForPreview(
                type = if (isVideo) MessageType.VIDEO else MessageType.IMAGE,
                fileName = name,
                fileSize = size,
                previewUrl = uri.toString()
            )
        }
    }

    // Real Document file picker
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri, "document_${System.currentTimeMillis()}.pdf")
            viewModel.selectMediaForPreview(
                type = MessageType.DOCUMENT,
                fileName = name,
                fileSize = size,
                previewUrl = uri.toString()
            )
        }
    }

    // Real Audio file picker
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri, "audio_${System.currentTimeMillis()}.mp3")
            viewModel.selectMediaForPreview(
                type = MessageType.AUDIO,
                fileName = name,
                fileSize = size,
                previewUrl = uri.toString()
            )
        }
    }

    // Real Audio record permission
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startVoiceRecording()
        }
    }

    fun startVoiceRecordingWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startVoiceRecording()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Search message matching indices in chat
    val matchingIndices = remember(messages, inChatSearchQuery) {
        if (inChatSearchQuery.isBlank()) emptyList()
        else messages.mapIndexedNotNull { index, msg ->
            if (msg.text.contains(inChatSearchQuery, ignoreCase = true)) index else null
        }
    }
    var currentMatchIndex by remember(matchingIndices) {
        mutableStateOf(if (matchingIndices.isNotEmpty()) matchingIndices.size - 1 else -1)
    }

    LaunchedEffect(currentMatchIndex, matchingIndices) {
        if (currentMatchIndex in matchingIndices.indices) {
            coroutineScope.launch {
                listState.animateScrollToItem(matchingIndices[currentMatchIndex])
            }
        }
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_screen"),
        topBar = {
            when {
                selectedIds.isNotEmpty() -> {
                    // Selection Mode Action Bar
                    ChatSelectionTopBar(
                        selectedCount = selectedIds.size,
                        onClearSelection = { viewModel.clearSelection() },
                        onReply = {
                            val msg = messages.find { it.id == selectedIds.first() }
                            if (msg != null) viewModel.replyToSelected(msg)
                        },
                        onDelete = { showDeleteDialog = true },
                        onForward = { showForwardDialog = true },
                        onCopy = {
                            val selectedTexts = messages
                                .filter { selectedIds.contains(it.id) }
                                .joinToString("\n") { it.text }
                            clipboardManager.setText(AnnotatedString(selectedTexts))
                            viewModel.clearSelection()
                        }
                    )
                }

                isSearchMode -> {
                    // In-Chat Search Bar
                    ChatSearchTopBar(
                        query = inChatSearchQuery,
                        onQueryChanged = {
                            viewModel.inChatSearchQuery.value = it
                        },
                        matchCount = matchingIndices.size,
                        currentIndex = if (matchingIndices.isEmpty()) 0 else currentMatchIndex + 1,
                        onCloseSearch = {
                            viewModel.isSearchMode.value = false
                            viewModel.inChatSearchQuery.value = ""
                        },
                        onNextMatch = {
                            if (matchingIndices.isNotEmpty()) {
                                currentMatchIndex = (currentMatchIndex + 1) % matchingIndices.size
                            }
                        },
                        onPreviousMatch = {
                            if (matchingIndices.isNotEmpty()) {
                                currentMatchIndex = if (currentMatchIndex <= 0) matchingIndices.size - 1 else currentMatchIndex - 1
                            }
                        }
                    )
                }

                else -> {
                    // Normal Chat Top App Bar
                    ChatMainTopBar(
                        contactName = contactName,
                        presenceText = if (isBlocked) "Blocked" else presence.second,
                        avatarRes = contactAvatarRes,
                        onBack = onBack,
                        onHeaderClick = { showContactInfoSheet = true },
                        onVideoCall = {
                            if (isBlocked) showUnblockDialog = true
                            else viewModel.startVideoCall()
                        },
                        onVoiceCall = {
                            if (isBlocked) showUnblockDialog = true
                            else viewModel.startAudioCall()
                        },
                        onSearchClick = { viewModel.isSearchMode.value = true },
                        onMenuClick = { showOptionsMenu = true },
                        showMenu = showOptionsMenu,
                        onDismissMenu = { showOptionsMenu = false },
                        onViewContact = {
                            showOptionsMenu = false
                            showContactInfoSheet = true
                        },
                        onClearChat = {
                            showOptionsMenu = false
                            showClearChatDialog = true
                        },
                        onMuteClick = {
                            showMuteDialog = true
                        },
                        onDisappearingClick = {
                            showContactInfoSheet = true
                        },
                        onBlockToggleClick = {
                            if (isBlocked) showUnblockDialog = true
                            else showBlockDialog = true
                        },
                        isBlocked = isBlocked
                    )
                }
            }
        },
        bottomBar = {
            Column {
                if (isBlocked) {
                    // Blocked contact notice banner
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .clickable { showUnblockDialog = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        shadowElevation = 2.dp
                    ) {
                        Text(
                            text = "You blocked this contact. Tap to unblock.",
                            color = Color(0xFF667781),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    // Reply quote banner if replying
                    if (replyingTo != null) {
                        ReplyComposerBanner(
                            senderName = replyingTo?.senderName ?: "Sender",
                            text = replyingTo?.text ?: "",
                            onDismiss = { viewModel.replyingTo.value = null }
                        )
                    }

                    // Bottom Input or Voice Recording Bar
                    if (isRecordingVoice) {
                        ChatVoiceRecordingBar(
                            durationSeconds = recordingDurationSec,
                            amplitudes = recordingAmplitudes,
                            onCancel = { viewModel.cancelVoiceRecording() },
                            onSend = { viewModel.sendVoiceMessage() }
                        )
                    } else {
                        ChatComposerBar(
                            text = inputText,
                            onTextChanged = {
                                isEmojiPickerOpen = false
                                viewModel.onInputTextChanged(it)
                            },
                            onSend = {
                                isEmojiPickerOpen = false
                                viewModel.sendTextMessage()
                            },
                            onAttachClick = {
                                isEmojiPickerOpen = false
                                showAttachmentSheet = true
                            },
                            onCameraClick = {
                                isEmojiPickerOpen = false
                                launchCamera()
                            },
                            onStartVoiceRecording = {
                                isEmojiPickerOpen = false
                                startVoiceRecordingWithPermission()
                            },
                            isEmojiPickerOpen = isEmojiPickerOpen,
                            onEmojiClick = {
                                isEmojiPickerOpen = !isEmojiPickerOpen
                            }
                        )

                        // Real Emoji Picker keyboard section
                        if (isEmojiPickerOpen) {
                            ChatEmojiPicker(
                                onEmojiSelected = { emoji ->
                                    viewModel.onInputTextChanged(inputText + emoji)
                                },
                                onBackspace = {
                                    if (inputText.isNotEmpty()) {
                                        viewModel.onInputTextChanged(inputText.dropLast(1))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Authentic WhatsApp doodle wallpaper background
            WhatsAppWallpaperBackground(modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.fillMaxSize()) {
                // Network connection banner (if offline or reconnecting)
                if (connectionState == PresenceStatus.OFFLINE) {
                    Surface(color = Color(0xFFE53935), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Waiting for network...", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                } else if (connectionState == PresenceStatus.RECONNECTING) {
                    Surface(color = Color(0xFFF57C00), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reconnecting...", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }

                // Messages list
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    item {
                        DateSeparator(dateText = "Today")
                    }

                    if (conversationInfo?.disappearingDuration != DisappearingDuration.OFF) {
                        item {
                            SystemMessageBubble(text = "Messages in this chat are set to disappear after ${conversationInfo?.disappearingDuration?.displayName ?: "24 hours"}.")
                        }
                    }

                    items(messages, key = { it.id }) { message ->
                        val isSelected = selectedIds.contains(message.id)
                        val uploadTask = activeUploads.find { it.messageId == message.id }
                        val isAudioPlaying = currentlyPlayingAudioId == message.id

                        Box(modifier = Modifier.fillMaxWidth()) {
                            DomainChatBubble(
                                message = message,
                                isSelected = isSelected,
                                uploadTask = uploadTask,
                                isPlayingAudio = isAudioPlaying,
                                audioProgress = if (isAudioPlaying) audioProgress else 0f,
                                onLongPress = {
                                    showReactionPickerForId = message.id
                                    viewModel.toggleSelectMessage(message.id)
                                },
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        viewModel.toggleSelectMessage(message.id)
                                    }
                                },
                                onTogglePlayAudio = {
                                    viewModel.togglePlayVoice(message.id, message.mediaDurationSec)
                                },
                                onOpenMediaViewer = {
                                    viewModel.activeViewerMessage.value = message
                                },
                                onOpenViewOnce = {
                                    viewModel.openViewOnceMedia(message)
                                },
                                onCancelUpload = {
                                    if (uploadTask != null) AppServiceContainer.uploadService.cancelUpload(uploadTask.id)
                                },
                                onRetryUpload = {
                                    viewModel.retryMessage(message.id)
                                },
                                onReactionClick = { emoji ->
                                    viewModel.addReaction(message.id, emoji)
                                }
                            )

                            // Floating Reaction bar if selected for reaction
                            if (showReactionPickerForId == message.id) {
                                MessageReactionBar(
                                    onReactionSelected = { emoji ->
                                        viewModel.addReaction(message.id, emoji)
                                        showReactionPickerForId = null
                                    },
                                    modifier = Modifier
                                        .align(if (message.isOutgoing) Alignment.TopEnd else Alignment.TopStart)
                                        .offset(y = (-36).dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Attachment bottom sheet
    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            containerColor = Color.White
        ) {
            WhatsAppAttachmentSheetContent(
                onDocumentSelected = {
                    showAttachmentSheet = false
                    documentLauncher.launch("*/*")
                },
                onCameraSelected = {
                    showAttachmentSheet = false
                    launchCamera()
                },
                onGallerySelected = {
                    showAttachmentSheet = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onAudioSelected = {
                    showAttachmentSheet = false
                    audioLauncher.launch("audio/*")
                },
                onLocationSelected = {
                    showAttachmentSheet = false
                    showSendLocationScreen = true
                },
                onContactSelected = {
                    showAttachmentSheet = false
                }
            )
        }
    }

    // Real Send Location screen
    if (showSendLocationScreen) {
        SendLocationScreen(
            onBack = { showSendLocationScreen = false },
            onSendLocation = { lat, lng, name, address ->
                viewModel.shareLocation(lat, lng, name, address)
                showSendLocationScreen = false
            },
            onSendLiveLocation = { durationText ->
                viewModel.shareLiveLocation(durationText)
                showSendLocationScreen = false
            }
        )
    }

    // Media Pre-send preview dialog
    if (pendingAttachment != null) {
        ChatMediaPreviewDialog(
            pending = pendingAttachment!!,
            errorMessage = fileSizeError,
            onCaptionChanged = { viewModel.updatePendingCaption(it) },
            onToggleViewOnce = { viewModel.togglePendingViewOnce() },
            onDismiss = { viewModel.dismissPendingAttachment() },
            onSend = { viewModel.sendPendingAttachment() }
        )
    }

    // Full screen Calling overlay
    if (activeCall != null) {
        ChatCallingOverlay(
            session = activeCall!!,
            onEndCall = { AppServiceContainer.callService.endCall() },
            onToggleMute = { AppServiceContainer.callService.toggleMute() },
            onToggleSpeaker = { AppServiceContainer.callService.toggleSpeaker() },
            onToggleVideo = { AppServiceContainer.callService.toggleVideo() },
            onSwitchCamera = { AppServiceContainer.callService.switchCamera() }
        )
    }

    // Full screen Media Viewer
    if (activeViewerMessage != null) {
        ChatMediaViewer(
            message = activeViewerMessage!!,
            onClose = { viewModel.activeViewerMessage.value = null },
            onDelete = {
                viewModel.deleteSelectedForMe()
                viewModel.activeViewerMessage.value = null
            }
        )
    }

    // Contact Info sheet
    if (showContactInfoSheet) {
        ChatContactInfoSheet(
            conversation = conversationInfo,
            contactName = contactName,
            contactAvatarRes = contactAvatarRes,
            mediaMessages = mediaMessages,
            documentMessages = documentMessages,
            onClose = { showContactInfoSheet = false },
            onVoiceCall = {
                showContactInfoSheet = false
                viewModel.startAudioCall()
            },
            onVideoCall = {
                showContactInfoSheet = false
                viewModel.startVideoCall()
            },
            onSetDisappearingMessages = { duration ->
                viewModel.setDisappearingMessages(duration)
            },
            onClearChat = {
                viewModel.clearEntireChat()
            },
            onToggleMute = { isMuted ->
                viewModel.setMuted(isMuted)
            },
            onBlockContact = {
                viewModel.setBlocked(true)
            },
            onUnblockContact = {
                viewModel.setBlocked(false)
            }
        )
    }

    // Block Contact alert dialog
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Block $contactName?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Blocked contacts will no longer be able to call you or send you messages.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockDialog = false
                        viewModel.setBlocked(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335))
                ) {
                    Text("Block", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel", color = Color(0xFF667781))
                }
            }
        )
    }

    // Unblock Contact alert dialog
    if (showUnblockDialog) {
        AlertDialog(
            onDismissRequest = { showUnblockDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Unblock $contactName?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You will be able to send and receive messages and calls with $contactName.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnblockDialog = false
                        viewModel.setBlocked(false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen)
                ) {
                    Text("Unblock", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnblockDialog = false }) {
                    Text("Cancel", color = Color(0xFF667781))
                }
            }
        )
    }

    // Clear Chat alert dialog
    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Clear this chat?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Messages will be deleted from this chat and cannot be recovered.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearChatDialog = false
                        viewModel.clearEntireChat()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335))
                ) {
                    Text("Clear chat", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Cancel", color = Color(0xFF667781))
                }
            }
        )
    }

    // Mute Notifications Dialog
    if (showMuteDialog) {
        var selectedMuteDuration by remember { mutableStateOf("8 hours") }
        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Mute notifications for...",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    listOf("8 hours", "1 week", "Always").forEach { durationText ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedMuteDuration = durationText }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedMuteDuration == durationText,
                                onClick = { selectedMuteDuration = durationText },
                                colors = RadioButtonDefaults.colors(selectedColor = WhatsAppFabGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = durationText,
                                fontSize = 15.sp,
                                color = Color(0xFF111B21)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMuteDialog = false
                        viewModel.setMuted(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false }) {
                    Text("Cancel", color = Color(0xFF667781))
                }
            }
        )
    }

    // Delete message dialog (Delete for me vs Delete for everyone)
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color.White,
            title = { Text("Delete message?", color = Color(0xFF111B21), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "You can delete this message just for yourself or for everyone in the chat.",
                    color = Color(0xFF667781)
                )
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteSelectedForEveryone()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete for everyone", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteSelectedForMe()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete for me", color = Color(0xFFEA4335))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = Color(0xFF667781))
                }
            }
        )
    }

    // Forward dialog (Select contact to forward to)
    if (showForwardDialog) {
        AlertDialog(
            onDismissRequest = { showForwardDialog = false },
            containerColor = Color.White,
            title = { Text("Forward to...", color = Color(0xFF111B21), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "No other conversations available",
                        color = Color(0xFF667781),
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showForwardDialog = false }) {
                    Text("Cancel", color = WhatsAppFabGreen)
                }
            }
        )
    }
}

// Subcomponents: Top Bars & Composers
@Composable
fun ChatMainTopBar(
    contactName: String,
    presenceText: String,
    avatarRes: Int?,
    onBack: () -> Unit,
    onHeaderClick: () -> Unit,
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onViewContact: () -> Unit,
    onClearChat: () -> Unit,
    onMuteClick: () -> Unit = {},
    onDisappearingClick: () -> Unit = {},
    onBlockToggleClick: () -> Unit = {},
    isBlocked: Boolean = false
) {
    Surface(
        color = WhatsAppChatDarkTeal,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25D366))
                ) {
                    if (avatarRes != null) {
                        Image(
                            painter = painterResource(id = avatarRes),
                            contentDescription = contactName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onHeaderClick)
            ) {
                Text(
                    text = contactName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = presenceText,
                    color = if (presenceText.contains("typing") || presenceText.contains("recording")) Color(0xFF80CBC4) else Color(0xFFB2DFDB),
                    fontSize = 12.5.sp,
                    maxLines = 1
                )
            }

            IconButton(onClick = onVideoCall) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = "Video Call",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = onVoiceCall) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = "Voice Call",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                    modifier = Modifier.background(Color.White)
                ) {
                    DropdownMenuItem(
                        text = { Text("View contact", color = GeometricTextDark) },
                        onClick = onViewContact
                    )
                    DropdownMenuItem(
                        text = { Text("Search", color = GeometricTextDark) },
                        onClick = {
                            onDismissMenu()
                            onSearchClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Mute notifications", color = GeometricTextDark) },
                        onClick = {
                            onDismissMenu()
                            onMuteClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Disappearing messages", color = GeometricTextDark) },
                        onClick = {
                            onDismissMenu()
                            onDisappearingClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear chat", color = Color(0xFFD32F2F)) },
                        onClick = {
                            onDismissMenu()
                            onClearChat()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isBlocked) "Unblock contact" else "Block contact",
                                color = if (isBlocked) WhatsAppFabGreen else Color(0xFFD32F2F)
                            )
                        },
                        onClick = {
                            onDismissMenu()
                            onBlockToggleClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatSelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        color = WhatsAppChatDarkTeal,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Close selection", tint = Color.White)
            }
            Text(
                text = "$selectedCount",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onReply) {
                Icon(Icons.Filled.Reply, contentDescription = "Reply", tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            }
            IconButton(onClick = onForward) {
                Icon(Icons.Filled.Forward, contentDescription = "Forward", tint = Color.White)
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color.White)
            }
        }
    }
}

@Composable
fun ChatSearchTopBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    onCloseSearch: () -> Unit,
    onNextMatch: () -> Unit,
    onPreviousMatch: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCloseSearch) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search", tint = Color(0xFF111B21))
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                textStyle = TextStyle(color = Color(0xFF111B21), fontSize = 16.sp),
                cursorBrush = SolidColor(WhatsAppFabGreen),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text("Search in chat...", color = Color(0xFF8696A0), fontSize = 16.sp)
                    }
                    innerTextField()
                }
            )
            if (query.isNotEmpty()) {
                Text(
                    text = if (matchCount > 0) "$currentIndex of $matchCount" else "0 of 0",
                    fontSize = 12.sp,
                    color = Color(0xFF667781)
                )
                IconButton(onClick = onPreviousMatch) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous", tint = Color(0xFF111B21))
                }
                IconButton(onClick = onNextMatch) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next", tint = Color(0xFF111B21))
                }
            }
        }
    }
}

@Composable
fun ReplyComposerBanner(
    senderName: String,
    text: String,
    onDismiss: () -> Unit
) {
    Surface(
        color = Color(0xFFF0F2F5),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .background(WhatsAppFabGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Replying to $senderName",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = WhatsAppChatTeal
                )
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = Color(0xFF54656F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color(0xFF8696A0))
            }
        }
    }
}

@Composable
fun ChatVoiceRecordingBar(
    durationSeconds: Int,
    amplitudes: List<Float>,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Red pulsing dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEA4335))
                )
                Spacer(modifier = Modifier.width(10.dp))
                // Duration timer (0:05)
                val mins = durationSeconds / 60
                val secs = durationSeconds % 60
                Text(
                    text = String.format("%d:%02d", mins, secs),
                    color = Color(0xFF111B21),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Waveform visualization bars
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val bars = if (amplitudes.isEmpty()) listOf(0.3f, 0.5f, 0.8f, 0.4f, 0.6f) else amplitudes
                    bars.forEach { amp ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height((amp * 20).dp.coerceAtLeast(4.dp))
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color(0xFFEA4335))
                        )
                    }
                }

                // Slide to cancel / Trash button
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Cancel",
                        tint = Color(0xFF8696A0)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        FloatingActionButton(
            onClick = onSend,
            shape = CircleShape,
            containerColor = WhatsAppFabGreen,
            contentColor = Color.White,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send voice note",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun ChatComposerBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit,
    onStartVoiceRecording: () -> Unit,
    isEmojiPickerOpen: Boolean = false,
    onEmojiClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 2.dp,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEmojiClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isEmojiPickerOpen) Icons.Filled.Keyboard else Icons.Outlined.EmojiEmotions,
                        contentDescription = if (isEmojiPickerOpen) "Keyboard" else "Emoji",
                        tint = if (isEmojiPickerOpen) WhatsAppFabGreen else Color(0xFF8696A0),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(text = "Message", color = Color(0xFF8696A0), fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChanged,
                        textStyle = TextStyle(color = Color(0xFF111B21), fontSize = 16.sp),
                        cursorBrush = SolidColor(WhatsAppFabGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_input_field")
                    )
                }

                IconButton(onClick = onAttachClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (text.isEmpty()) {
                    IconButton(onClick = onCameraClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Camera",
                            tint = Color(0xFF8696A0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        FloatingActionButton(
            onClick = {
                if (text.isNotBlank()) onSend()
                else onStartVoiceRecording()
            },
            shape = CircleShape,
            containerColor = WhatsAppFabGreen,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(2.dp),
            modifier = Modifier
                .size(48.dp)
                .testTag("chat_send_button")
        ) {
            AnimatedContent(targetState = text.isNotBlank(), label = "send_mic") { hasText ->
                if (hasText) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Record",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WhatsAppWallpaperBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(WhatsAppChatBg)) {
        val doodleColor = Color(0xFF4A4036).copy(alpha = 0.045f)
        val strokeWidth = 1.2f
        val stepX = 140f
        val stepY = 140f
        var x = 20f
        while (x < size.width) {
            var y = 20f
            var index = 0
            while (y < size.height) {
                when (index % 5) {
                    0 -> {
                        val path = Path().apply {
                            moveTo(x, y)
                            lineTo(x + 22f, y)
                            lineTo(x + 22f, y + 16f)
                            lineTo(x + 8f, y + 16f)
                            lineTo(x + 3f, y + 21f)
                            lineTo(x + 4f, y + 16f)
                            lineTo(x, y + 16f)
                            close()
                        }
                        drawPath(path, doodleColor, style = Stroke(strokeWidth))
                    }
                    1 -> {
                        drawCircle(doodleColor, radius = 4f, center = Offset(x + 4f, y + 16f))
                        drawLine(doodleColor, Offset(x + 7f, y + 16f), Offset(x + 7f, y), strokeWidth)
                        drawLine(doodleColor, Offset(x + 7f, y), Offset(x + 16f, y + 3f), strokeWidth)
                    }
                    2 -> {
                        drawRoundRect(
                            doodleColor,
                            topLeft = Offset(x, y + 4f),
                            size = androidx.compose.ui.geometry.Size(18f, 14f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                            style = Stroke(strokeWidth)
                        )
                    }
                    else -> {
                        drawCircle(doodleColor, radius = 2.5f, center = Offset(x + 8f, y + 8f))
                    }
                }
                y += stepY
                index++
            }
            x += stepX
        }
    }
}

@Composable
fun WhatsAppAttachmentSheetContent(
    onDocumentSelected: () -> Unit,
    onCameraSelected: () -> Unit,
    onGallerySelected: () -> Unit,
    onAudioSelected: () -> Unit,
    onLocationSelected: () -> Unit,
    onContactSelected: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Share Content",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = GeometricTextDark,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            AttachmentIconItem(icon = Icons.Filled.Description, label = "Document", color = Color(0xFF7F66FF), onClick = onDocumentSelected)
            AttachmentIconItem(icon = Icons.Filled.CameraAlt, label = "Camera", color = Color(0xFFD3396D), onClick = onCameraSelected)
            AttachmentIconItem(icon = Icons.Filled.Image, label = "Gallery", color = Color(0xFFAC44CF), onClick = onGallerySelected)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            AttachmentIconItem(icon = Icons.Filled.Headphones, label = "Audio", color = Color(0xFFE56A38), onClick = onAudioSelected)
            AttachmentIconItem(icon = Icons.Filled.LocationOn, label = "Location", color = Color(0xFF1FA855), onClick = onLocationSelected)
            AttachmentIconItem(icon = Icons.Filled.Person, label = "Contact", color = Color(0xFF009DE2), onClick = onContactSelected)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AttachmentIconItem(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF54656F)
        )
    }
}

fun queryFileInfo(context: Context, uri: Uri, fallbackName: String): Pair<String, Long> {
    var name = fallbackName
    var size = 1024 * 1024L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    cursor.getString(nameIndex)?.let { name = it }
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        // Fallback default
    }
    return Pair(name, size)
}
