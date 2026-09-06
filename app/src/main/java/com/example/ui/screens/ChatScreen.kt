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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.*
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
import com.example.R
import com.example.di.AppServiceContainer
import com.example.model.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.SearchFilter
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
    // Scoped to the navigation back-stack entry: when the user leaves the
    // chat, onCleared() releases the MediaRecorder (mic), stops playback and
    // cancels timers. The previous remember{} kept all of that alive forever.
    val viewModel: ChatViewModel = viewModel(
        key = "chat_$contactId",
        factory = viewModelFactory {
            initializer {
                ChatViewModel(contactId, contactName, contactAvatarRes)
            }
        }
    )

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
    var isGifPickerOpen by remember { mutableStateOf(false) }
    var showArchiveConfirmDialog by remember { mutableStateOf(false) }

    // Track the long-pressed message so we can show Edit/Pin/Star/Forward/Delete
    // actions in the selection action bar.
    val activeMessageIds by viewModel.selectedMessageIds.collectAsState()
    val editingMessage by viewModel.editingMessage.collectAsState()
    val editingText by viewModel.editingText.collectAsState()
    val searchFilter by viewModel.searchFilter.collectAsState()
    val searchResultsEx by viewModel.searchResultsEx.collectAsState()
    val allConversations by viewModel.allConversations.collectAsState()
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
    fun scrollToMessageId(messageId: String) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(idx)
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
                listState.animateScrollToItem(matchingIndices[currentMatchIndex])
            }
        }
    }

    // Auto-scroll on new messages ONLY when the user is already near the
    // bottom — previously a history page-load (prepend) yanked them to the
    // end of the list mid-read.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = lastVisible >= messages.size - 2
            if (nearBottom) {
                listState.animateScrollToItem(messages.size - 1)
            }
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

    var maxHeightWithoutIme by remember { mutableStateOf(0.dp) }

    // Intercept back button when custom emoji or GIF picker is open to dismiss it first
    BackHandler(enabled = isEmojiPickerOpen || isGifPickerOpen) {
        isEmojiPickerOpen = false
        isGifPickerOpen = false
    }

    // When software keyboard opens, close custom emoji and GIF pickers & scroll to bottom
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            if (isEmojiPickerOpen) isEmojiPickerOpen = false
            if (isGifPickerOpen) isGifPickerOpen = false
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_screen")
    ) {
        val currentHeight = maxHeight
        if (!isImeOpen && currentHeight > maxHeightWithoutIme) {
            maxHeightWithoutIme = currentHeight
        }
        val windowPhysicallyResized = isImeOpen && (maxHeightWithoutIme - currentHeight > 100.dp)

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = WhatsAppChatBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            when {
                selectedIds.isNotEmpty() -> {
                    // Selection Mode Action Bar — with Edit, Pin, Star, Forward, Delete, Copy
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
                        onForward = { showForwardDialog = true },
                        onCopy = {
                            val selectedTexts = messages
                                .filter { selectedIds.contains(it.id) }
                                .joinToString("\n") { it.text }
                            clipboardManager.setText(AnnotatedString(selectedTexts))
                            viewModel.clearSelection()
                        },
                        onEdit = if (canEdit && firstSelected != null) {
                            { viewModel.startEditing(firstSelected) }
                        } else null,
                        onPin = if (selectedIds.size == 1 && firstSelected != null) {
                            {
                                viewModel.togglePinMessage(firstSelected)
                                viewModel.clearSelection()
                            }
                        } else null,
                        onStar = if (selectedIds.size == 1 && firstSelected != null) {
                            {
                                viewModel.toggleStarMessage(firstSelected)
                                viewModel.clearSelection()
                            }
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
                            viewModel.refreshChatInfo()
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
                        onArchiveClick = {
                            showOptionsMenu = false
                            showArchiveConfirmDialog = true
                        },
                        isArchived = conversationArchived,
                        isBlocked = isBlocked
                    )
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isImeOpen && windowPhysicallyResized) {
                            // The system window was already resized by Android WindowManager (adjustResize).
                            // No additional IME padding is needed — composer sits directly on top of keyboard with 0 gap!
                            Modifier
                        } else if (isImeOpen) {
                            // The system window was not resized — apply IME padding so composer floats above keyboard
                            Modifier.imePadding()
                        } else {
                            // Keyboard is closed — apply navigation bar padding so composer sits above the Android bottom nav
                            Modifier.navigationBarsPadding()
                        }
                    )
            ) {
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
                            onDismiss = { viewModel.replyingTo.value = null }
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
                    } else {
                        ChatComposerBar(
                            text = inputText,
                            onTextChanged = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                viewModel.onInputTextChanged(it)
                            },
                            onSend = {
                                isEmojiPickerOpen = false
                                isGifPickerOpen = false
                                viewModel.sendTextMessage()
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
                                    // Sticker taps send the emoji immediately as a TEXT message
                                    viewModel.onInputTextChanged(sticker)
                                    viewModel.sendTextMessage()
                                }
                            )
                        }

                        // GIF picker
                        if (isGifPickerOpen) {
                            ChatGifPicker(
                                onGifSelected = { gif ->
                                    // GIF/sticker taps send the emoji immediately as a TEXT message
                                    viewModel.onInputTextChanged(gif)
                                    viewModel.sendTextMessage()
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
                // Report the contact (peer) — we use the contactId as the reported user id
                viewModel.reportUser(contactId, reason) {
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

    // Forward dialog (Select contact to forward to) — uses Room conversations
    if (showForwardDialog) {
        val otherConversations = allConversations.filter { it.id != contactId }
        var selectedForwardIds by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = {
                showForwardDialog = false
                selectedForwardIds = emptySet()
            },
            containerColor = Color.White,
            title = { Text("Forward to...", color = Color(0xFF111B21), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (otherConversations.isEmpty()) {
                        Text(
                            text = "No other conversations available",
                            color = Color(0xFF667781),
                            fontSize = 14.sp
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(otherConversations, key = { conv -> conv.id }) { conv ->
                                val isChecked = selectedForwardIds.contains(conv.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedForwardIds = if (isChecked) {
                                                selectedForwardIds - conv.id
                                            } else {
                                                selectedForwardIds + conv.id
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = {
                                            selectedForwardIds = if (isChecked) {
                                                selectedForwardIds - conv.id
                                            } else {
                                                selectedForwardIds + conv.id
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = WhatsAppFabGreen)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = conv.name,
                                        fontSize = 15.sp,
                                        color = Color(0xFF111B21)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showForwardDialog = false
                        selectedForwardIds = emptySet()
                    }) {
                        Text("Cancel", color = Color(0xFF667781))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (selectedForwardIds.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.forwardSelectedTo(selectedForwardIds.toList())
                                showForwardDialog = false
                                selectedForwardIds = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen)
                        ) {
                            Text("Forward (${selectedForwardIds.size})", color = Color.White)
                        }
                    }
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
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onStar: (() -> Unit)? = null
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
                if (onPin != null) {
                    IconButton(onClick = onPin) {
                        Icon(Icons.Filled.PushPin, contentDescription = "Pin", tint = Color.White)
                    }
                }
                if (onStar != null) {
                    IconButton(onClick = onStar) {
                        Icon(Icons.Filled.Star, contentDescription = "Star", tint = Color.White)
                    }
                }
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
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
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

