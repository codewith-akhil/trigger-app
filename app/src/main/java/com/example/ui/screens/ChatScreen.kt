package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import coil.compose.AsyncImage
import com.example.R
import com.example.di.AppServiceContainer
import com.example.model.*
import com.example.service.VaultMediaItem
import com.example.ui.theme.*
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.SearchFilter
import kotlinx.coroutines.launch
import java.io.File

/** WhatsApp-style day separator label: Today / Yesterday / "7 September 2026". */
internal fun formatDateSeparatorLabel(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val today = java.util.Calendar.getInstance()
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return when {
        cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
        cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) -> "Yesterday"
        else -> java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(cal.time)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String = "",
    peerId: String = "",
    contactName: String = "",
    contactAvatarRes: Int? = null,
    onBack: () -> Unit,
    // Opens the peer's full profile screen (wired from the avatar + name taps
    // in ChatMainTopBar). The ⋮ "View profile" item keeps opening the info sheet.
    onOpenProfile: () -> Unit = {}
) {
    // H4: the chat MUST run on the real conversation uuid. Entry points that
    // only know the peer's user uuid resolve (or create) the conversation
    // BEFORE the ViewModel is constructed, so every flow keys on it.
    var resolvedConversationId by remember { mutableStateOf(conversationId.ifBlank { null }) }
    var resolvedPeerId by remember { mutableStateOf<String?>(peerId.ifBlank { null }) }
    LaunchedEffect(conversationId, peerId) {
        if (conversationId.isBlank()) {
            resolvedConversationId =
                (com.example.di.AppServiceContainer.messageService as? com.example.service.MessageServiceImpl)
                    ?.resolveOrCreateConversation(peerId)
                    // Resolution failed (offline / no session): fall back to the
                    // legacy peer key — send-message self-heals server-side.
                    ?: peerId
            if (resolvedPeerId == null) resolvedPeerId = peerId
        } else {
            // Opened by conversation uuid (dashboard) — recover the peer for
            // presence/typing/calls from the local conversation row (v6 column).
            if (resolvedPeerId == null) {
                val conv = com.example.di.AppServiceContainer.chatRepository
                    .getConversationByIdOnce(conversationId)
                resolvedPeerId = conv?.peerId ?: ""
            }
            // Canonical-row heal: the pair may hold two mirror conversation
            // rows while messages live on the OLDEST one. If we were opened
            // with a mirror id, switch to the canonical id so the thread
            // matches the peer's (critical after accepting a message request).
            val canonical = (com.example.di.AppServiceContainer.messageService as? com.example.service.MessageServiceImpl)
                ?.resolveCanonicalConversationId(conversationId)
            if (!canonical.isNullOrBlank() && canonical != conversationId) {
                resolvedConversationId = canonical
            }
        }
    }

    val readyConvId = resolvedConversationId
    val readyPeerId = resolvedPeerId
    if (readyConvId == null || readyPeerId == null) {
        // Conversation/peer resolution in flight (first chat with a new contact).
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Scoped to the navigation back-stack entry: when the user leaves the
    // chat, onCleared() releases the MediaRecorder (mic), stops playback and
    // cancels timers. The previous remember{} kept all of that alive forever.
    val viewModel: ChatViewModel = viewModel(
        key = "chat_$readyConvId",
        factory = viewModelFactory {
            initializer {
                ChatViewModel(readyConvId, readyPeerId, contactName, contactAvatarRes)
            }
        }
    )

    val messages by viewModel.messages.collectAsState()
    // Single-flight text-send guard: send buttons disable while a send is running.
    val isSending by viewModel.isSending.collectAsState()
    // Peer avatar URL (profiles fetch) for the top bar; null/blank → drawable fallback.
    val peerAvatarUrl by viewModel.peerAvatarUrl.collectAsState()
    val presence by viewModel.contactPresence.collectAsState()
    val conversationMeta by viewModel.conversationMeta.collectAsState()
    val requestNotice by viewModel.requestNotice.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val activeUploads by viewModel.activeUploads.collectAsState()
    val conversationInfo by viewModel.conversationInfo.collectAsState()
    // C7: live-location status (mine + the peer's realtime coordinates)
    val myLiveShare by viewModel.myLiveLocation.collectAsState()
    val peerLiveShare by viewModel.peerLiveLocation.collectAsState()

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
    // Inline audio playback failure + voice recording error (both were
    // previously swallowed — failures were totally silent).
    val audioPlaybackFailedId by viewModel.audioPlaybackFailedId.collectAsState()
    val audioPlaybackError by viewModel.audioPlaybackError.collectAsState()
    val voiceError by viewModel.voiceErrorMessage.collectAsState()

    val context = LocalContext.current
    val isBlocked = conversationInfo?.isBlocked ?: false

    // Message-request state (Instagram model)
    val isRequestPending = conversationMeta?.requestStatus == "pending"
    val isRequestReceiver = isRequestPending && conversationMeta?.isRequester == false
    val isRequestRequester = isRequestPending && conversationMeta?.isRequester == true

    // Blocked-send / action notices surface as a toast (same channel the
    // rest of the screen uses for lightweight feedback).
    LaunchedEffect(requestNotice) {
        requestNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearRequestNotice()
        }
    }

    var showContactInfoSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showSendLocationScreen by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showUnblockDialog by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    // Auto-delete (disappearing messages) dialog — opened from the ⋮ menu and
    // from the in-list notice's "Change" link.
    var showAutoDeleteDialog by remember { mutableStateOf(false) }
    var showReactionPickerForId by remember { mutableStateOf<String?>(null) }
    // Message id whose FULL emoji reaction sheet is open ("+" in the quick bar).
    var showReactionEmojiPickerForId by remember { mutableStateOf<String?>(null) }
    var isEmojiPickerOpen by remember { mutableStateOf(false) }
    var isGifPickerOpen by remember { mutableStateOf(false) }
    var showArchiveConfirmDialog by remember { mutableStateOf(false) }

    // ---- Vault attach flow (third attach-sheet row) ----
    // PIN is verified on EVERY entry — the unlock is never cached for this flow.
    var showVaultPinDialog by remember { mutableStateOf(false) }
    var showVaultPickerSheet by remember { mutableStateOf(false) }

    // Track the long-pressed message so we can show Edit/Reply/Delete/Copy
    // actions in the selection action bar.
    val activeMessageIds by viewModel.selectedMessageIds.collectAsState()
    val editingMessage by viewModel.editingMessage.collectAsState()
    val editingText by viewModel.editingText.collectAsState()
    val searchFilter by viewModel.searchFilter.collectAsState()
    val searchResultsEx by viewModel.searchResultsEx.collectAsState()
    val starredMessages by viewModel.starredMessages.collectAsState()
    val sharedLinks by viewModel.sharedLinks.collectAsState()
    val conversationArchived = conversationInfo?.isArchived ?: false

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Real Camera capture launcher — uses ActivityResultContracts.TakePicture
    // (full-resolution JPEG via FileProvider URI) instead of the low-res
    // TakePicturePreview bitmap path. See ProfileScreen.kt for the same pattern.
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = cameraImageUri
        if (success && uri != null) {
            try {
                val file = File(context.cacheDir, uri.lastPathSegment ?: "camera_${System.currentTimeMillis()}.jpg")
                viewModel.selectMediaForPreview(
                    type = MessageType.IMAGE,
                    fileName = file.name,
                    fileSize = file.length().coerceAtLeast(1024L),
                    previewUrl = uri.toString(),
                    filePath = uri.toString(),
                    mimeType = "image/jpeg"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        cameraImageUri = null
    }

    fun launchCameraCapture() {
        try {
            val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture()
        }
    }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture()
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
                previewUrl = uri.toString(),
                filePath = uri.toString(),
                mimeType = mimeType
            )
        }
    }

    // Real Document file picker
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri, "document_${System.currentTimeMillis()}.pdf")
            val docMime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            viewModel.selectMediaForPreview(
                type = MessageType.DOCUMENT,
                fileName = name,
                fileSize = size,
                previewUrl = uri.toString(),
                filePath = uri.toString(),
                mimeType = docMime
            )
        }
    }

    // Real Audio file picker
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri, "audio_${System.currentTimeMillis()}.mp3")
            val audioMime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            viewModel.selectMediaForPreview(
                type = MessageType.AUDIO,
                fileName = name,
                fileSize = size,
                previewUrl = uri.toString(),
                filePath = uri.toString(),
                mimeType = audioMime
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

    // ---- Contact picker (for sharing a contact in chat) ----
    // Reads Android's contact picker — requires READ_CONTACTS permission on
    // some OEMs, but most accept it without via PickContact contract.
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                        val contactId = if (idIndex >= 0) it.getString(idIndex) else ""
                        val contactName = if (nameIndex >= 0) it.getString(nameIndex) ?: "" else ""
                        val hasPhone = if (hasPhoneIndex >= 0) it.getInt(hasPhoneIndex) > 0 else false
                        var phone = ""
                        if (hasPhone && contactId.isNotEmpty()) {
                            val phones = context.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null
                            )
                            phones?.use { p ->
                                if (p.moveToFirst()) {
                                    val pIdx = p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                    if (pIdx >= 0) phone = p.getString(pIdx) ?: ""
                                }
                            }
                        }
                        if (contactName.isNotBlank()) {
                            viewModel.shareContact(contactName, phone)
                        }
                    }
                }
            } catch (e: Exception) {
                // Permission denied or contact not readable — ignore
            }
        }
    }

    val readContactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try { contactPickerLauncher.launch(null) } catch (_: Exception) {}
        }
    }

    fun launchContactPicker() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try { contactPickerLauncher.launch(null) } catch (_: Exception) {}
        } else {
            readContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // ---- Pagination: load more when user scrolls to the top ----
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIdx ->
                if (firstIdx <= 2 && messages.isNotEmpty()) {
                    viewModel.loadMoreMessages()
                }
            }
    }

    // ---- Scroll-to-message: jump to a specific message id (used by search & reply) ----
    // The auto-delete notice is ALWAYS list item 0 whenever an auto-delete
    // choice was ever made (duration != OFF, or OFF after a previous choice).
    // All message indices are therefore shifted by +1 in scroll math below.
    val hasSystemHeader = conversationInfo?.disappearingUpdatedAtMillis != null
    val latestItemIndex = if (messages.isEmpty()) 0 else if (hasSystemHeader) messages.size else messages.size - 1

    fun scrollToMessageId(messageId: String) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            coroutineScope.launch {
                val target = if (hasSystemHeader) idx + 1 else idx
                listState.animateScrollToItem(target)
            }
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
                val target = if (hasSystemHeader) matchingIndices[currentMatchIndex] + 1 else matchingIndices[currentMatchIndex]
                listState.animateScrollToItem(target)
            }
        }
    }

    // Auto-scroll on new messages:
    // Moves the viewport to the latest messages upon receiving a new message in the chat.
    val lastMessageId = messages.lastOrNull()?.id
    var previousLastMessageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lastMessageId) {
        if (lastMessageId != null && messages.isNotEmpty()) {
            if (previousLastMessageId != null && previousLastMessageId != lastMessageId) {
                // A new message arrived in the chat (sent or received) — auto-scroll to the latest message!
                listState.animateScrollToItem(latestItemIndex)
            }
            previousLastMessageId = lastMessageId
        }
    }

    // Open the chat at the NEWEST message on initial launch / conversation switch
    var didInitialScroll by rememberSaveable(readyConvId) { mutableStateOf(false) }
    LaunchedEffect(readyConvId) {
        didInitialScroll = false
        previousLastMessageId = null
    }
    LaunchedEffect(messages.size, readyConvId) {
        if (!didInitialScroll && messages.isNotEmpty()) {
            listState.scrollToItem(latestItemIndex)
            didInitialScroll = true
            previousLastMessageId = messages.lastOrNull()?.id
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isImeOpen = imeBottom > 0
    val view = LocalView.current
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }

    // Intercept back button when custom emoji or GIF picker is open to dismiss it first
    BackHandler(enabled = isEmojiPickerOpen || isGifPickerOpen) {
        isEmojiPickerOpen = false
        isGifPickerOpen = false
    }

    // When software keyboard opens, close custom emoji and GIF pickers & scroll viewport to latest message
    LaunchedEffect(isImeOpen) {
        if (isImeOpen) {
            if (isEmojiPickerOpen) isEmojiPickerOpen = false
            if (isGifPickerOpen) isGifPickerOpen = false
            if (messages.isNotEmpty()) {
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(latestItemIndex)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_screen"),
        containerColor = WhatsAppChatBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // The SendLocation overlay covers the whole screen with its OWN
            // header — keeping the chat topBar composed underneath made two
            // stacked headers flicker on every map repaint.
            if (!showSendLocationScreen) {
                when {
                    selectedIds.isNotEmpty() -> {
                        // Selection Mode Action Bar — with Edit, Reply, Delete, Copy
                        val firstSelected = messages.find { it.id == selectedIds.first() }
                        val canEdit = selectedIds.size == 1 && firstSelected != null &&
                            firstSelected.isOutgoing &&
                            firstSelected.type == MessageType.TEXT &&
                            (System.currentTimeMillis() - firstSelected.timestampMillis) <= 15L * 60 * 1000
                        ChatSelectionTopBar(
                            selectedCount = selectedIds.size,
                            onClearSelection = { viewModel.clearSelection() },
                            onReply = {
                                val msg = messages.find { it.id == selectedIds.first() }
                                if (msg != null) viewModel.replyToSelected(msg)
                            },
                            onDelete = { showDeleteDialog = true },
                            onCopy = {
                                val selectedTexts = messages
                                    .filter { selectedIds.contains(it.id) }
                                    .joinToString("\n") { it.text }
                                clipboardManager.setText(AnnotatedString(selectedTexts))
                                viewModel.clearSelection()
                            },
                            onEdit = if (canEdit && firstSelected != null) {
                                { viewModel.startEditing(firstSelected) }
                            } else null
                        )
                    }

                    isSearchMode -> {
                        // In-Chat Search Bar with filter chips
                        ChatSearchTopBar(
                            query = inChatSearchQuery,
                            onQueryChanged = {
                                viewModel.inChatSearchQuery.value = it
                                // Re-run server-side search when query changes
                                if (it.isNotBlank()) {
                                    viewModel.runSearchEx(searchFilter, query = it)
                                } else {
                                    viewModel.searchResultsEx.value = emptyList()
                                }
                            },
                            matchCount = matchingIndices.size,
                            currentIndex = if (matchingIndices.isEmpty()) 0 else currentMatchIndex + 1,
                            onCloseSearch = {
                                viewModel.isSearchMode.value = false
                                viewModel.inChatSearchQuery.value = ""
                                viewModel.searchResultsEx.value = emptyList()
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
                            },
                            searchFilter = searchFilter,
                            onFilterSelected = { filter ->
                                viewModel.runSearchEx(filter, query = inChatSearchQuery)
                            }
                        )
                    }

                    else -> {
                        // Normal Chat Top App Bar
                        ChatMainTopBar(
                            contactName = contactName,
                            presenceText = when {
                                isBlocked -> "Blocked"
                                isRequestPending -> "Message request"
                                else -> presence.second
                            },
                            avatarRes = contactAvatarRes,
                            avatarUrl = peerAvatarUrl,
                            onBack = onBack,
                            onOpenProfile = onOpenProfile,
                            onVideoCall = {
                                if (isBlocked) showUnblockDialog = true
                                else viewModel.startVideoCall()
                            },
                            onVoiceCall = {
                                if (isBlocked) showUnblockDialog = true
                                else viewModel.startAudioCall()
                            },
                            onMenuClick = { showOptionsMenu = true },
                            showMenu = showOptionsMenu,
                            onDismissMenu = { showOptionsMenu = false },
                            onViewContact = {
                                showOptionsMenu = false
                                viewModel.refreshChatInfo()
                                showContactInfoSheet = true
                            },
                            onClearChat = {
                                showOptionsMenu = false
                                showClearChatDialog = true
                            },
                            onDisappearingClick = {
                                showAutoDeleteDialog = true
                            },
                            onBlockToggleClick = {
                                if (isBlocked) showUnblockDialog = true
                                else showBlockDialog = true
                            },
                            onArchiveClick = {
                                showOptionsMenu = false
                                showArchiveConfirmDialog = true
                            },
                            isArchived = conversationArchived,
                            isBlocked = isBlocked
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.union(WindowInsets.ime)
                    )
            ) {
                // Message-request banner — RECEIVER must accept/decline before
                // the conversation unlocks (replying is blocked server-side too).
                if (isRequestReceiver) {
                    MessageRequestReceiverBanner(
                        senderName = contactName,
                        onAccept = { viewModel.acceptMessageRequest() },
                        onDecline = { viewModel.declineMessageRequest() }
                    )
                } else if (isRequestRequester) {
                    MessageRequestRequesterBanner(messagesSent = conversationMeta?.myRequestMessageCount ?: 0)
                }
                // Voice recording failure — surfaced inline (tap to dismiss)
                // instead of being swallowed silently.
                voiceError?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.clearVoiceError() }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = err,
                            color = Color(0xFFEA4335),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (isBlocked) {
                    // Blocked contact notice banner
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
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
                            onBannerClick = { replyingTo?.let { scrollToMessageId(it.id) } },
                            onDismiss = { viewModel.replyingTo.value = null }
                        )
                    }

                    // C7: live-location status bar (sharer side + peer side)
                    myLiveShare?.let { share ->
                        LiveLocationStatusBar(
                            title = "Sharing live location",
                            subtitle = "Updating in realtime • ends " + formatLiveExpiry(share.expiresAtMillis),
                            actionLabel = "Stop",
                            onAction = { viewModel.stopLiveLocationShare() }
                        )
                    }
                    peerLiveShare?.let { share ->
                        val ageSec = ((System.currentTimeMillis() - share.updatedAtMillis) / 1000L).coerceAtLeast(0)
                        LiveLocationStatusBar(
                            title = "Live location shared with you",
                            subtitle = "%.5f, %.5f • updated %ds ago • ends %s".format(
                                share.latitude, share.longitude, ageSec, formatLiveExpiry(share.expiresAtMillis)
                            ),
                            actionLabel = null,
                            onAction = {}
                        )
                    }

                    // Bottom Input or Voice Recording Bar
                    if (isRecordingVoice) {
                        ChatVoiceRecordingBar(
                            durationSeconds = recordingDurationSec.toInt(),
                            amplitudes = recordingAmplitudes,
                            onCancel = { viewModel.cancelVoiceRecording() },
                            onSend = { viewModel.sendVoiceMessage() }
                        )
                    } else if (editingMessage != null) {
                        // Inline edit bar — replaces the composer while editing
                        ChatEditBar(
                            originalText = editingMessage?.text ?: "",
                            editedText = editingText,
                            onTextChanged = { viewModel.editingText.value = it },
                            onCancel = { viewModel.cancelEditing() },
                            onSave = { viewModel.saveEdit() }
                        )
                    } else if (!isRequestReceiver) {
                        ChatComposerBar(
                            text = inputText,
                            isSending = isSending,
                            onTextChanged = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                viewModel.onInputTextChanged(it)
                            },
                            onSend = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                viewModel.sendTextMessage()
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(50)
                                    listState.animateScrollToItem(latestItemIndex)
                                }
                            },
                            onAttachClick = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                keyboardController?.hide()
                                showAttachmentSheet = true
                            },
                            onCameraClick = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                keyboardController?.hide()
                                launchCamera()
                            },
                            onStartVoiceRecording = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                keyboardController?.hide()
                                startVoiceRecordingWithPermission()
                            },
                            isEmojiPickerOpen = isEmojiPickerOpen,
                            isGifPickerOpen = isGifPickerOpen,
                            focusRequester = focusRequester,
                            onInputFocus = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                            },
                            onEmojiClick = {
                                isGifPickerOpen = false
                                if (!isEmojiPickerOpen) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                                    isEmojiPickerOpen = true
                                } else {
                                    isEmojiPickerOpen = false
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            },
                            onGifClick = {
                                isEmojiPickerOpen = false
                                if (!isGifPickerOpen) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                                    isGifPickerOpen = true
                                } else {
                                    isGifPickerOpen = false
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
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
                                },
                                onStickerSelected = { sticker ->
                                    // Sticker taps send the emoji immediately as a TEXT message.
                                    // Blocked while a send is already in flight.
                                    if (!isSending) {
                                        viewModel.onInputTextChanged(sticker)
                                        viewModel.sendTextMessage()
                                    }
                                }
                            )
                        }

                        // GIF picker
                        if (isGifPickerOpen) {
                            ChatGifPicker(
                                onGifSelected = { gif ->
                                    // GIF/sticker taps send the emoji immediately as a TEXT message.
                                    // Blocked while a send is already in flight.
                                    if (!isSending) {
                                        viewModel.onInputTextChanged(gif)
                                        viewModel.sendTextMessage()
                                    }
                                }
                            )
                        }
                    } else {
                        // RECEIVER still deciding — composer locked until accept.
                        MessageRequestComposerLocked()
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

                // Per-message day separators — computed from timestampMillis.
                // (A hardcoded "Today" separator previously labeled ALL history
                // as today.)
                val daySeparators = remember(messages) {
                    val cal = java.util.Calendar.getInstance()
                    var prevDay = Long.MIN_VALUE
                    messages.map { m ->
                        cal.timeInMillis = m.timestampMillis
                        val day = cal.get(java.util.Calendar.YEAR) * 1000L +
                                cal.get(java.util.Calendar.DAY_OF_YEAR)
                        val label = if (day != prevDay) formatDateSeparatorLabel(m.timestampMillis) else null
                        prevDay = day
                        label
                    }
                }

                // Messages list
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {

                    if (hasSystemHeader) {
                        // Auto-delete notice — always list item 0 (the +1 scroll
                        // offsets above depend on this placement).
                        item(key = "auto_delete_notice") {
                            ChatAutoDeleteNotice(
                                duration = conversationInfo?.disappearingDuration ?: DisappearingDuration.OFF,
                                onChangeClick = { showAutoDeleteDialog = true }
                            )
                        }
                    }

                    itemsIndexed(messages, key = { _, m -> m.id }) { msgIndex, message ->
                        val isSelected = selectedIds.contains(message.id)
                        val uploadTask = activeUploads.find { it.messageId == message.id }
                        val isAudioPlaying = currentlyPlayingAudioId == message.id

                        Box(modifier = Modifier.fillMaxWidth()) {
                            daySeparators.getOrNull(msgIndex)?.let { label ->
                                DateSeparator(dateText = label)
                            }
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
                                    viewModel.togglePlayVoice(message)
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
                                },
                                onQuoteClick = { replySourceId ->
                                    // Graceful no-op when the original message
                                    // is no longer in the list.
                                    scrollToMessageId(replySourceId)
                                },
                                audioErrorText = if (audioPlaybackFailedId == message.id) {
                                    audioPlaybackError ?: "Playback failed"
                                } else null,
                                onSeekAudio = { fraction ->
                                    viewModel.seekAudioTo(message.id, fraction)
                                }
                            )

                            // Floating Reaction bar if selected for reaction
                            if (showReactionPickerForId == message.id) {
                                MessageReactionBar(
                                    onReactionSelected = { emoji ->
                                        viewModel.addReaction(message.id, emoji)
                                        showReactionPickerForId = null
                                    },
                                    onPickMore = {
                                        showReactionPickerForId = null
                                        showReactionEmojiPickerForId = message.id
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
                    launchContactPicker()
                },
                onVaultSelected = {
                    showAttachmentSheet = false
                    showVaultPinDialog = true
                }
            )
        }
    }

    // Vault PIN gate — compact dialog with the app's keypad style. The PIN is
    // verified on EVERY entry; the unlock is never cached for this flow.
    if (showVaultPinDialog) {
        VaultPinGateDialog(
            onDismiss = { showVaultPinDialog = false },
            onVerified = {
                showVaultPinDialog = false
                showVaultPickerSheet = true
            }
        )
    }

    // Vault media picker — 3-column grid of the decrypted vault files; a tap
    // feeds the local file straight into the normal media pre-send preview
    // (UploadServiceImpl.resolveUploadSource handles plain file paths).
    if (showVaultPickerSheet) {
        VaultMediaPickerSheet(
            onDismiss = { showVaultPickerSheet = false },
            onPick = { item ->
                showVaultPickerSheet = false
                viewModel.selectMediaForPreview(
                    type = if (item.isVideo) MessageType.VIDEO else MessageType.IMAGE,
                    fileName = item.name,
                    fileSize = item.sizeBytes,
                    previewUrl = item.filePath,
                    filePath = item.filePath,
                    mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
                )
            }
        )
    }

    // Full emoji reaction picker sheet ("+" in the quick reaction bar).
    // Rendered at root level (not inside the lazy item) so an open sheet is
    // never disposed by lazy-list recycling while the user scrolls.
    val reactionPickerMessageId = showReactionEmojiPickerForId
    if (reactionPickerMessageId != null) {
        ReactionEmojiPickerSheet(
            onEmojiSelected = { emoji ->
                viewModel.addReaction(reactionPickerMessageId, emoji)
                showReactionEmojiPickerForId = null
            },
            onDismiss = { showReactionEmojiPickerForId = null }
        )
    }

    // Real Send Location screen
    if (showSendLocationScreen) {
        SendLocationScreen(
            onBack = { showSendLocationScreen = false },
            onSendLocation = { lat, lng, name, address ->
                viewModel.shareLocation(lat, lng, name, address)
                showSendLocationScreen = false
            },
            onSendLiveLocation = { latitude, longitude, durationText, comment ->
                viewModel.shareLiveLocation(latitude, longitude, durationText, comment)
                showSendLocationScreen = false
            }
        )
    }

    // Media Pre-send preview dialog
    val pendingAttachmentValue = pendingAttachment
    if (pendingAttachmentValue != null) {
        ChatMediaPreviewDialog(
            pending = pendingAttachmentValue,
            errorMessage = fileSizeError,
            onCaptionChanged = { viewModel.updatePendingCaption(it) },
            onToggleViewOnce = { viewModel.togglePendingViewOnce() },
            onDismiss = { viewModel.dismissPendingAttachment() },
            onSend = { viewModel.sendPendingAttachment() }
        )
    }

    // Full screen Calling overlay
    val activeCallValue = activeCall
    if (activeCallValue != null) {
        ChatCallingOverlay(
            session = activeCallValue,
            onEndCall = { AppServiceContainer.callService.endCall() },
            onToggleMute = { AppServiceContainer.callService.toggleMute() },
            onToggleSpeaker = { AppServiceContainer.callService.toggleSpeaker() },
            onToggleVideo = { AppServiceContainer.callService.toggleVideo() },
            onSwitchCamera = { AppServiceContainer.callService.switchCamera() },
            onAcceptCall = { AppServiceContainer.callService.acceptIncomingCall() },
            onDeclineCall = { AppServiceContainer.callService.declineCall() }
        )
    }

    // Full screen Media Viewer
    val activeViewerMessageValue = activeViewerMessage

    // Screenshot prevention (C10): FLAG_SECURE while a VIEW-ONCE media is on
    // screen — previously there was zero screenshot protection anywhere.
    val secureViewer = activeViewerMessageValue?.isViewOnce == true
    if (secureViewer) {
        DisposableEffect(Unit) {
            val window = (context as? android.app.Activity)?.window
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    if (activeViewerMessageValue != null) {
        ChatMediaViewer(
            message = activeViewerMessageValue,
            onClose = { viewModel.activeViewerMessage.value = null },
            onReact = { emoji ->
                viewModel.addReaction(activeViewerMessageValue.id, emoji)
            },
            onSendReply = { text ->
                viewModel.sendReplyFromViewer(text)
            },
            onDelete = {
                viewModel.deleteMessageForMe(activeViewerMessageValue)
                viewModel.activeViewerMessage.value = null
            },
            onShare = {
                val mediaUrl = activeViewerMessageValue.mediaUrl
                if (!mediaUrl.isNullOrEmpty()) {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (activeViewerMessageValue.type == MessageType.VIDEO) "video/*"
                                   else if (activeViewerMessageValue.type == MessageType.DOCUMENT) "*/*"
                                   else "image/*"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(mediaUrl))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
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
            starredMessages = starredMessages,
            sharedLinks = sharedLinks,
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
            },
            onReportUser = { reason ->
                // Use the RESOLVED peer id — the raw nav arg can be blank when
                // the chat was opened by conversation id (Calls tab entry),
                // which made the report fail server-side.
                viewModel.reportUser(readyPeerId, reason) {
                    showContactInfoSheet = false
                }
            },
            onJumpToMessage = { messageId ->
                // Close the sheet then scroll to the message
                showContactInfoSheet = false
                scrollToMessageId(messageId)
            }
        )
    }

    // Block Contact alert dialog (compact style)
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "Block $contactName?",
                    color = Color(0xFF111B21),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "They won't be able to message or call you anymore.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp,
                    lineHeight = 19.sp
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
                    Text("Cancel", color = Color(0xFF008069))
                }
            }
        )
    }

    // Unblock Contact alert dialog (compact style)
    if (showUnblockDialog) {
        AlertDialog(
            onDismissRequest = { showUnblockDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "Unblock $contactName?",
                    color = Color(0xFF111B21),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "They will be able to message and call you again.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnblockDialog = false
                        viewModel.setBlocked(false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884))
                ) {
                    Text("Unblock", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnblockDialog = false }) {
                    Text("Cancel", color = Color(0xFF008069))
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

    // Delete message dialog — compact: red Delete button + optional
    // "Also delete for <contact>" checkbox (shown only when EVERY selected
    // message is outgoing; delete-for-everyone is sender-only server-side).
    if (showDeleteDialog) {
        val selectedMessages = messages.filter { selectedIds.contains(it.id) }
        val canDeleteForEveryone = selectedMessages.isNotEmpty() && selectedMessages.all { it.isOutgoing }
        var alsoDeleteForEveryone by remember(showDeleteDialog) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "DELETE MESSAGE",
                    color = Color(0xFF111B21),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Are you sure you want to delete these messages?",
                        color = Color(0xFF667781),
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                    if (canDeleteForEveryone) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { alsoDeleteForEveryone = !alsoDeleteForEveryone },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = alsoDeleteForEveryone,
                                onCheckedChange = { alsoDeleteForEveryone = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF008069))
                            )
                            Text(
                                text = "Also delete for $contactName",
                                fontSize = 14.sp,
                                color = Color(0xFF111B21)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        if (canDeleteForEveryone && alsoDeleteForEveryone) {
                            viewModel.deleteSelectedForEveryone()
                        } else {
                            viewModel.deleteSelectedForMe()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335))
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = Color(0xFF008069))
                }
            }
        )
    }

    // Archive chat confirm dialog
    if (showArchiveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirmDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = if (conversationArchived) "Unarchive chat?" else "Archive chat?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (conversationArchived) {
                        "This chat will be moved back to your main chat list."
                    } else {
                        "This chat will be archived. You can unarchive it any time from the menu."
                    },
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showArchiveConfirmDialog = false
                        viewModel.setArchived(!conversationArchived)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen)
                ) {
                    Text(if (conversationArchived) "Unarchive" else "Archive", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF667781))
                }
            }
        )
    }

    // Auto delete (disappearing messages) dialog — opened from the ⋮ menu and
    // from the in-list notice's "Change" link.
    if (showAutoDeleteDialog) {
        val currentDuration = conversationInfo?.disappearingDuration ?: DisappearingDuration.OFF
        var autoDeleteSelection by remember(showAutoDeleteDialog) { mutableStateOf(currentDuration) }
        AutoDeleteDialog(
            current = currentDuration,
            selected = autoDeleteSelection,
            onSelect = { autoDeleteSelection = it },
            onConfirm = {
                showAutoDeleteDialog = false
                viewModel.setDisappearingMessages(autoDeleteSelection)
            },
            onDismiss = { showAutoDeleteDialog = false }
        )
    }
}

// Subcomponents: Top Bars & Composers
@Composable
fun ChatMainTopBar(
    contactName: String,
    presenceText: String,
    avatarRes: Int?,
    avatarUrl: String? = null,
    onBack: () -> Unit,
    // Avatar + peer name open the peer's PROFILE screen (the ⋮ "View profile"
    // item keeps opening the in-chat ContactInfoSheet).
    onOpenProfile: () -> Unit = {},
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
    onMenuClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onViewContact: () -> Unit,
    onClearChat: () -> Unit,
    onDisappearingClick: () -> Unit = {},
    onBlockToggleClick: () -> Unit = {},
    onArchiveClick: () -> Unit = {},
    isArchived: Boolean = false,
    isBlocked: Boolean = false
) {
    Surface(
        color = WhatsAppChatDarkTeal,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Avatar is its own click target → opens the peer's profile
                // (no longer nested inside the back row). 48dp touch target
                // around the 40dp visual circle.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenProfile),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF25D366))
                    ) {
                        when {
                            !avatarUrl.isNullOrBlank() -> {
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = "Open profile photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            avatarRes != null -> {
                                Image(
                                    painter = painterResource(id = avatarRes),
                                    contentDescription = "Open profile photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = "Open profile photo",
                                    tint = Color.White,
                                    modifier = Modifier.fillMaxSize().padding(8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenProfile)
                ) {
                    Text(
                        text = contactName,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    // Blank subtitle = presence hidden by privacy — render nothing.
                    if (presenceText.isNotEmpty()) {
                        Text(
                            text = presenceText,
                            color = if (presenceText.contains("typing") || presenceText.contains("recording")) Color(0xFF80CBC4) else Color(0xFFB2DFDB),
                            fontSize = 12.5.sp,
                            maxLines = 1
                        )
                    }
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
                            text = { Text("View profile", color = GeometricTextDark) },
                            onClick = onViewContact
                        )
                        DropdownMenuItem(
                            text = { Text("Auto delete", color = GeometricTextDark) },
                            onClick = {
                                onDismissMenu()
                                onDisappearingClick()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isArchived) "Unarchive chat" else "Archive chat",
                                    color = GeometricTextDark
                                )
                            },
                            onClick = {
                                onDismissMenu()
                                onArchiveClick()
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
}

@Composable
fun ChatSelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Surface(
        color = WhatsAppChatDarkTeal,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color.White)
                    }
                }
                IconButton(onClick = onReply) {
                    Icon(Icons.Filled.Reply, contentDescription = "Reply", tint = Color.White)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color.White)
                }
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
    onPreviousMatch: () -> Unit,
    searchFilter: SearchFilter = SearchFilter.ALL,
    onFilterSelected: (SearchFilter) -> Unit = {}
) {
    Column {
        Surface(
            color = Color.White,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
        // Filter chips row
        Surface(color = Color(0xFFF0F2F5)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SearchFilter.values().forEach { filter ->
                    val isSelected = filter == searchFilter
                    val label = when (filter) {
                        SearchFilter.ALL -> "All"
                        SearchFilter.MEDIA -> "Media"
                        SearchFilter.DOCUMENTS -> "Docs"
                        SearchFilter.LINKS -> "Links"
                        SearchFilter.DATE -> "Date"
                    }
                    Surface(
                        color = if (isSelected) WhatsAppFilterActiveBg else Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .clickable { onFilterSelected(filter) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = if (isSelected) WhatsAppFilterActiveText else WhatsAppFilterInactiveText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReplyComposerBanner(
    senderName: String,
    text: String,
    onBannerClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Surface(
        color = Color(0xFFF0F2F5),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBannerClick)
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
    // Single-flight guard: block FAB + IME sends while a send is in flight.
    isSending: Boolean = false,
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit,
    onStartVoiceRecording: () -> Unit,
    isEmojiPickerOpen: Boolean = false,
    isGifPickerOpen: Boolean = false,
    focusRequester: FocusRequester? = null,
    onInputFocus: () -> Unit = {},
    onEmojiClick: () -> Unit = {},
    onGifClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (isEmojiPickerOpen || isGifPickerOpen) {
                                onInputFocus()
                            }
                            focusRequester?.requestFocus()
                        }
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
                        keyboardActions = KeyboardActions(onSend = { if (!isSending) onSend() }),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_input_field")
                            .then(
                                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                                else Modifier
                            )
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    onInputFocus()
                                }
                            }
                    )
                }

                // GIF picker button
                IconButton(onClick = onGifClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Gif,
                        contentDescription = "GIF",
                        tint = if (isGifPickerOpen) WhatsAppFabGreen else Color(0xFF8696A0),
                        modifier = Modifier.size(24.dp)
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
                if (isSending) return@FloatingActionButton
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
                if (isSending && hasText) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else if (hasText) {
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
    onContactSelected: () -> Unit,
    onVaultSelected: () -> Unit = {}
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

        Spacer(modifier = Modifier.height(20.dp))

        // Third row — media from the PIN-protected Secret Vault
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            AttachmentIconItem(
                icon = Icons.Outlined.Lock,
                label = "Vault",
                color = Color(0xFF0F665E),
                onClick = onVaultSelected
            )
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

/**
 * Compact vault PIN gate (22-e dialog conventions: white, 16dp corners, 16sp
 * bold title, green accents). Minimal 4x3 keypad + masked dots — the vault
 * screen's CustomPinKeypad is dark-theme-coupled. NO fake autofill: the PIN
 * must be typed on EVERY entry (never cached for this flow).
 */
@Composable
fun VaultPinGateDialog(
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }

    fun appendDigit(d: String) {
        if (verifying) return
        errorMessage = null
        if (pin.length < 6) pin += d
    }

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Enter vault PIN",
                color = Color(0xFF111B21),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Masked dots
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { index ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < pin.length) Color(0xFF008069)
                                    else Color(0xFF667781).copy(alpha = 0.3f)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Inline error (the service API exposes no attempts-left count)
                Text(
                    text = errorMessage ?: " ",
                    color = Color(0xFFEA4335),
                    fontSize = 12.sp,
                    minLines = 1
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Minimal 4x3 keypad — 180dp wide, 48dp keys
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "⌫")
                )
                Column(
                    modifier = Modifier.width(180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    for (row in rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (btn in row) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF0F2F5))
                                        .clickable {
                                            when (btn) {
                                                "⌫" -> { if (!verifying && pin.isNotEmpty()) pin = pin.dropLast(1) }
                                                "C" -> { if (!verifying) pin = "" }
                                                else -> appendDigit(btn)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (btn == "⌫") {
                                        Icon(
                                            imageVector = Icons.Filled.Backspace,
                                            contentDescription = "Backspace",
                                            tint = Color(0xFF54656F),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            text = btn,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF111B21)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = { onDismiss() },
                enabled = !verifying
            ) {
                Text("Cancel", color = Color(0xFF008069))
            }
        }
    )

    // Auto-verify as soon as 6 digits are entered. verifyPin is synchronous
    // (local-hash result; the server-side check runs inside the service and
    // keeps isUnlocked in sync). Compile-safe + defensive against the
    // SecretVaultService API evolving under parallel work.
    LaunchedEffect(pin) {
        if (pin.length == 6 && !verifying) {
            verifying = true
            val ok = try {
                AppServiceContainer.secretVaultService.verifyPin(pin)
            } catch (e: Exception) {
                android.util.Log.w("ChatScreen", "vault verifyPin failed: ${e.message}")
                false
            }
            if (ok) {
                onVerified()
            } else {
                errorMessage = "Wrong PIN"
                pin = ""
            }
            verifying = false
        }
    }
}

/**
 * Vault media picker — 3-column grid (100dp cells) of the user's decrypted
 * vault files. Tapping an item feeds the LOCAL file path into the normal
 * media pre-send preview flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultMediaPickerSheet(
    onDismiss: () -> Unit,
    onPick: (VaultMediaItem) -> Unit
) {
    val vaultService = AppServiceContainer.secretVaultService
    val vaultItems by vaultService.vaultItems.collectAsState()

    // Refresh the local listing when the sheet opens (best-effort; the
    // service exposes loadVaultItems() — newer API names are handled by
    // the caller if the parallel vault task lands them).
    LaunchedEffect(Unit) {
        try {
            vaultService.loadVaultItems()
        } catch (e: Exception) {
            android.util.Log.w("ChatScreen", "vault loadVaultItems failed: ${e.message}")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Vault",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF111B21),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (vaultItems.isEmpty()) {
                Text(
                    text = "No media in your vault yet — add some from Settings → Secret Vault.",
                    color = Color(0xFF667781),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(320.dp)
                ) {
                    items(vaultItems, key = { it.id }) { item ->
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE9EDEF))
                                .clickable { onPick(item) }
                        ) {
                            AsyncImage(
                                model = File(item.filePath),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (item.isVideo) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Video",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline edit bar — replaces the ChatComposerBar while the user is editing a
 * previously sent TEXT message. Shows the original text + Save / Cancel
 * buttons. The Save button calls viewModel.saveEdit() which invokes the
 * edit-message edge function.
 */
@Composable
fun ChatEditBar(
    originalText: String,
    editedText: String,
    onTextChanged: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        color = Color(0xFFF0F2F5),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel edit",
                    tint = Color(0xFF8696A0),
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            ) {
                BasicTextField(
                    value = editedText,
                    onValueChange = onTextChanged,
                    textStyle = TextStyle(color = Color(0xFF111B21), fontSize = 16.sp),
                    cursorBrush = SolidColor(WhatsAppFabGreen),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                if (editedText.isEmpty()) {
                    Text(text = originalText, color = Color(0xFF8696A0), fontSize = 16.sp)
                }
            }
            FloatingActionButton(
                onClick = onSave,
                shape = CircleShape,
                containerColor = WhatsAppFabGreen,
                contentColor = Color.White,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Save edit",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}


/** C7: compact live-location status card shown above the composer. */
@Composable
private fun LiveLocationStatusBar(
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFDCF8C6),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = Color(0xFF1FA855),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF183B2A))
                Text(subtitle, fontSize = 11.sp, color = Color(0xFF51705F))
            }
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "2h 14m" / "14m" until the live share expires. */
private fun formatLiveExpiry(expiresAtMillis: Long): String {
    val mins = ((expiresAtMillis - System.currentTimeMillis()) / 60000L).coerceAtLeast(0)
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}

// ============================================================================
// Message-request banners (Instagram model)
// ============================================================================

/** RECEIVER: accept / decline an incoming message request. */
@Composable
private fun MessageRequestReceiverBanner(
    senderName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFD8FDD2),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PersonAddAlt1,
                    contentDescription = null,
                    tint = Color(0xFF0B614E),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$senderName wants to chat with you",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF183B2A),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884)),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp)
                ) {
                    Text("Accept", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onDecline,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F))
                ) {
                    Text("Decline", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

/** REQUESTER: waiting-for-acceptance status with the 3-message budget. */
@Composable
private fun MessageRequestRequesterBanner(messagesSent: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFDF3D8)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = Color(0xFF8A6D1A),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Message request • $messagesSent of 3 messages sent — " +
                    "${(3 - messagesSent).coerceAtLeast(0)} left until they accept",
                fontSize = 12.sp,
                color = Color(0xFF6B5616),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** RECEIVER: composer placeholder while the request is still undecided. */
@Composable
private fun MessageRequestComposerLocked() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF0F2F5)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Color(0xFF667781),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Accept the request above to reply",
                fontSize = 13.sp,
                color = Color(0xFF667781)
            )
        }
    }
}

// ============================================================================
// Auto delete (disappearing messages): in-list notice + settings dialog
// ============================================================================

/** Colors for the auto-delete notice (our palette, not WhatsApp's yellow). */
private val AutoDeleteNoticeBg = Color(0xFFE7F0EC)
private val AutoDeleteNoticeText = Color(0xFF3D4A44)
private val WhatsAppDeepGreen = Color(0xFF008069)

/**
 * In-list system notice for auto delete — rendered as list item 0 whenever an
 * auto-delete choice was ever made (duration != OFF, or OFF after a previous
 * choice). Shows the current behavior plus a "Change" shortcut.
 */
@Composable
private fun ChatAutoDeleteNotice(
    duration: DisappearingDuration,
    onChangeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = AutoDeleteNoticeBg,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = AutoDeleteNoticeText,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (duration != DisappearingDuration.OFF) {
                            "You turned on auto delete. New messages will automatically " +
                                "delete from both ends ${duration.displayName.lowercase()} after they're sent."
                        } else {
                            "Auto delete is off in this chat."
                        },
                        color = AutoDeleteNoticeText,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Change",
                    color = WhatsAppDeepGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onChangeClick)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Compact auto-delete settings dialog (radio list of the supported durations).
 * "Update" is enabled only when the selection differs from the current value;
 * persistence happens via viewModel.setDisappearingMessages (server-side).
 */
@Composable
private fun AutoDeleteDialog(
    current: DisappearingDuration,
    selected: DisappearingDuration,
    onSelect: (DisappearingDuration) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        DisappearingDuration.HOURS_24,
        DisappearingDuration.DAYS_7,
        DisappearingDuration.DAYS_30,
        DisappearingDuration.OFF
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Auto delete messages",
                color = Color(0xFF111B21),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "For more privacy, new messages will automatically delete " +
                        "from both ends after the selected duration.",
                    color = Color(0xFF667781),
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(option) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { onSelect(option) },
                            colors = RadioButtonDefaults.colors(selectedColor = WhatsAppDeepGreen)
                        )
                        Text(
                            text = option.displayName,
                            fontSize = 15.sp,
                            color = Color(0xFF111B21)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = selected != current,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhatsAppDeepGreen,
                    disabledContainerColor = WhatsAppDeepGreen.copy(alpha = 0.45f),
                    contentColor = Color.White,
                    disabledContentColor = Color.White
                )
            ) {
                Text("Update", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WhatsAppDeepGreen)
            }
        }
    )
}

/**
 * Full emoji reaction picker (bottom sheet) opened from the "+" in the quick
 * reaction bar. Reuses the reaction emoji categories from ChatEmojiPicker.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionEmojiPickerSheet(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White
    ) {
        var categoryIndex by remember { mutableStateOf(0) }
        val categories = REACTION_EMOJI_CATEGORIES
        val safeIndex = categoryIndex.coerceIn(0, categories.lastIndex)
        val currentCategory = categories[safeIndex]

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = currentCategory.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF667781),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 44.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(currentCategory.emojis, key = { emoji -> emoji }) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 26.sp)
                    }
                }
            }

            // Category bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                categories.forEachIndexed { index, category ->
                    val isSelected = index == safeIndex
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFE7FCE3) else Color.Transparent)
                            .clickable { categoryIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.icon,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }
    }
}
