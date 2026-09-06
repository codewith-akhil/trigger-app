package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ChatItem
import com.example.model.ChatRepository
import com.example.ui.theme.*

enum class DashboardTab {
    CHATS, UPDATES, STREAM, CALLS, PROFILE
}

enum class ChatFilter {
    ALL, UNREAD, GROUPS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppDashboardScreen(
    onOpenChat: (contactId: String, contactName: String, avatarRes: Int?) -> Unit,
    onOpenSelectContact: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNavigateToScheduleStream: () -> Unit = {},
    onNavigateToStreamHistory: () -> Unit = {},
    onRestartFlow: () -> Unit
) {
    val viewModel: com.example.ui.viewmodel.DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dbConversations by viewModel.conversations.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    val agoraCallState by com.example.di.AppServiceContainer.agoraService.callState.collectAsState()
    val agoraState by com.example.di.AppServiceContainer.agoraService.agoraState.collectAsState()

    var selectedTab by remember { mutableStateOf(DashboardTab.CHATS) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var showStatusStoryDialog by remember { mutableStateOf<String?>(null) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var showGoLiveDialog by remember { mutableStateOf(false) }
    var showActiveCallDialog by remember { mutableStateOf(false) }
    var activeCallContactName by remember { mutableStateOf("Contact") }
    var activeCallIsVideo by remember { mutableStateOf(false) }

    val allChats = remember(dbConversations) {
        if (dbConversations.isNotEmpty()) {
            dbConversations.map { conv ->
                ChatItem(
                    id = conv.id,
                    name = conv.name,
                    avatarRes = conv.avatarRes,
                    initialColor = conv.initialColor,
                    lastMessage = conv.lastMessage,
                    timestamp = conv.timestamp,
                    unreadCount = conv.unreadCount,
                    isPinned = conv.isPinned,
                    hasStatusUpdate = conv.hasStatusUpdate,
                    isGroup = conv.isGroup,
                    isOnline = conv.isOnline
                )
            }
        } else {
            ChatRepository.initialChats
        }
    }

    val displayedChats = allChats
    val totalUnread = remember(allChats) { allChats.sumOf { it.unreadCount } }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trigger_dashboard_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.White,
        topBar = {
            if (selectedTab != DashboardTab.PROFILE) {
                val isStreamTab = selectedTab == DashboardTab.STREAM
                Column {
                    WhatsAppTopHeader(
                        title = if (isStreamTab) "Live Stream" else "Trigger App",
                        isStreamHeader = isStreamTab,
                        onNewStreamClick = onNavigateToScheduleStream,
                        onCameraClick = { showCameraDialog = true },
                        onMenuClick = { showTopMenu = true },
                        showMenu = showTopMenu,
                        onDismissMenu = { showTopMenu = false },
                        onOpenProfile = { selectedTab = DashboardTab.PROFILE },
                        onOpenSettings = onOpenSettings,
                        onRestartOnboarding = onRestartFlow,
                        onToggleNetwork = { viewModel.toggleNetworkConnection() }
                    )
                    if (connectionState == com.example.model.PresenceStatus.OFFLINE) {
                        Surface(color = Color(0xFFE53935), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Waiting for network...", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "Reconnect",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { viewModel.toggleNetworkConnection() }
                                )
                            }
                        }
                    } else if (connectionState == com.example.model.PresenceStatus.RECONNECTING) {
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
                }
            } else {
                ProfileTopHeader(
                    onBack = { selectedTab = DashboardTab.CHATS }
                )
            }
        },
        bottomBar = {
            WhatsAppBottomNavBar(
                selectedTab = selectedTab,
                unreadChatsCount = totalUnread,
                onTabSelected = { selectedTab = it }
            )
        },
        floatingActionButton = {
            when (selectedTab) {
                DashboardTab.CHATS -> {
                    FloatingActionButton(
                        onClick = { onOpenSelectContact() },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = TriggerFabGreen,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(3.dp),
                        modifier = Modifier.testTag("new_chat_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddComment,
                            contentDescription = "New Chat",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                DashboardTab.STREAM -> {
                    FloatingActionButton(
                        onClick = onNavigateToScheduleStream,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = TriggerFabGreen,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(3.dp),
                        modifier = Modifier.testTag("new_stream_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoCall,
                            contentDescription = "New Stream",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                DashboardTab.CALLS -> {
                    FloatingActionButton(
                        onClick = {
                            activeCallContactName = "Live Contact"
                            activeCallIsVideo = true
                            com.example.di.AppServiceContainer.agoraService.startCall("call_general", isVideo = true)
                            showActiveCallDialog = true
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = TriggerFabGreen,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(3.dp),
                        modifier = Modifier.testTag("new_call_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddIcCall,
                            contentDescription = "New Call",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                else -> {}
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                DashboardTab.CHATS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // "Search or Ask Trigger AI" Pill Box
                        MetaAISearchBar(
                            query = searchQuery,
                            onQueryChanged = { viewModel.setSearchQuery(it) }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Filter Chips Row: All, Unread, Groups
                        FilterChipsRow(
                            selectedFilter = selectedFilter,
                            onFilterSelected = { viewModel.setFilter(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Chat List
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(displayedChats, key = { it.id }) { chat ->
                                ChatListItem(
                                    chat = chat,
                                    onClick = {
                                        onOpenChat(chat.id, chat.name, chat.avatarRes)
                                    },
                                    onAvatarClick = {
                                        if (chat.hasStatusUpdate) {
                                            showStatusStoryDialog = chat.name
                                        } else {
                                            onOpenChat(chat.id, chat.name, chat.avatarRes)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                DashboardTab.UPDATES -> {
                    UpdatesTabContent(
                        onViewStatus = { name -> showStatusStoryDialog = name },
                        onNewChat = { onOpenSelectContact() }
                    )
                }

                DashboardTab.STREAM -> {
                    StreamTabContent(
                        onGoLive = { showGoLiveDialog = true },
                        onNavigateToScheduleStream = onNavigateToScheduleStream,
                        onNavigateToStreamHistory = onNavigateToStreamHistory
                    )
                }

                DashboardTab.CALLS -> {
                    CallsTabContent(
                        onCallContact = { name ->
                            activeCallContactName = name
                            activeCallIsVideo = false
                            com.example.di.AppServiceContainer.agoraService.startCall("call_${name.lowercase().replace(" ", "_")}", isVideo = false)
                            showActiveCallDialog = true
                        },
                        onVideoCallContact = { name ->
                            activeCallContactName = name
                            activeCallIsVideo = true
                            com.example.di.AppServiceContainer.agoraService.startCall("call_${name.lowercase().replace(" ", "_")}", isVideo = true)
                            showActiveCallDialog = true
                        }
                    )
                }

                DashboardTab.PROFILE -> {
                    ProfileScreen(
                        onBack = { selectedTab = DashboardTab.CHATS },
                        onLogout = onRestartFlow,
                        showHeader = false
                    )
                }
            }
        }
    }

    // Agora Active Call Dialog
    if (showActiveCallDialog) {
        AgoraActiveCallDialog(
            contactName = activeCallContactName,
            isVideo = activeCallIsVideo,
            onDismiss = {
                com.example.di.AppServiceContainer.agoraService.endCall()
                showActiveCallDialog = false
            }
        )
    }

    // Live Stream Player Overlay
    if (agoraState.mode == com.example.service.webrtc.AgoraCallMode.LIVE_STREAM &&
        agoraState.status == com.example.service.webrtc.AgoraCallStatus.CONNECTED) {
        LiveStreamPlayerScreen(
            onDismiss = {
                com.example.di.AppServiceContainer.agoraService.endCall()
            }
        )
    }

    // Agora Go Live Dialog
    if (showGoLiveDialog) {
        AgoraGoLiveDialog(
            onDismiss = { showGoLiveDialog = false }
        )
    }

    // New Chat modal sheet
    if (showNewChatDialog) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatDialog = false },
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Select Contact",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = GeometricTextDark,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                allChats.forEach { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showNewChatDialog = false
                                onOpenChat(contact.id, contact.name, contact.avatarRes)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(contact.initialColor)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (contact.avatarRes != null) {
                                Image(
                                    painter = painterResource(id = contact.avatarRes),
                                    contentDescription = contact.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = contact.name.take(1),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = contact.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = GeometricTextDark
                            )
                            Text(
                                text = if (contact.isOnline) "Online" else "Available",
                                fontSize = 13.sp,
                                color = GeometricTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Status story preview dialog
    if (showStatusStoryDialog != null) {
        AlertDialog(
            onDismissRequest = { showStatusStoryDialog = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "${showStatusStoryDialog}'s Status",
                    color = GeometricTextDark,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(WhatsAppHeaderGreen, Color(0xFF1B5E20))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Enjoying the sunshine today! ☀️🌴",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Posted 42 minutes ago",
                        color = GeometricTextSecondary,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusStoryDialog = null }) {
                    Text("Close", color = WhatsAppHeaderGreen, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Camera preview dialog
    if (showCameraDialog) {
        AlertDialog(
            onDismissRequest = { showCameraDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Camera", color = GeometricTextDark, fontWeight = FontWeight.Bold)
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF0F2F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = WhatsAppHeaderGreen,
                        modifier = Modifier.size(54.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showCameraDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen)
                ) {
                    Text("Take Photo", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraDialog = false }) {
                    Text("Cancel", color = GeometricTextSecondary)
                }
            }
        )
    }
}

@Composable
fun WhatsAppTopHeader(
    title: String = "Trigger App",
    isStreamHeader: Boolean = false,
    onNewStreamClick: () -> Unit = {},
    onCameraClick: () -> Unit,
    onMenuClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRestartOnboarding: () -> Unit,
    onToggleNetwork: () -> Unit = {}
) {
    val headerBgColor = if (isStreamHeader) TriggerHeaderGreen else Color.White
    val titleColor = if (isStreamHeader) Color.White else TriggerHeaderGreen
    val iconColor = if (isStreamHeader) Color.White else Color(0xFF111B21)

    Surface(
        color = headerBgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Area above the header section (status bar) with header green background
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(TriggerHeaderGreen)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Brand name or "Live Stream"
            Text(
                text = title,
                color = titleColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f)
            )

            if (isStreamHeader) {
                // New stream icon navigating to Stream Create page
                IconButton(
                    onClick = onNewStreamClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoCall,
                        contentDescription = "New Stream",
                        tint = iconColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else {
                // Camera icon on regular tabs
                IconButton(
                    onClick = onCameraClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = "Camera",
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                    modifier = Modifier.background(Color.White)
                ) {
                    DropdownMenuItem(
                        text = { Text("Profile", color = GeometricTextDark, fontWeight = FontWeight.SemiBold) },
                        onClick = {
                            onDismissMenu()
                            onOpenProfile()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New group", color = GeometricTextDark) },
                        onClick = onDismissMenu
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = GeometricTextDark) },
                        onClick = {
                            onDismissMenu()
                            onOpenSettings()
                        }
                    )
                    HorizontalDivider(color = GeometricBorderLight)
                    DropdownMenuItem(
                        text = { Text("Restart Onboarding Flow", color = WhatsAppHeaderGreen, fontWeight = FontWeight.SemiBold) },
                        onClick = {
                            onDismissMenu()
                            onRestartOnboarding()
                        }
                    )
                }
            }
        }
        }
    }
}

@Composable
fun MetaAISearchBar(
    query: String,
    onQueryChanged: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = WhatsAppSearchBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trigger AI Colorful Ring Icon (Gradient emerald-teal-cyan)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFF00A884),
                                Color(0xFF008069),
                                Color(0xFF00C6FF),
                                Color(0xFF0A56D1),
                                Color(0xFF00A884)
                            )
                        ),
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search or Ask Trigger AI",
                        color = Color(0xFF667781),
                        fontSize = 15.sp
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    textStyle = TextStyle(
                        color = Color(0xFF111B21),
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(TriggerHeaderGreen),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_search_input")
                )
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChanged("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear",
                        tint = Color(0xFF667781),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FilterChipsRow(
    selectedFilter: ChatFilter,
    onFilterSelected: (ChatFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipItem(
            title = "All",
            isSelected = selectedFilter == ChatFilter.ALL,
            onClick = { onFilterSelected(ChatFilter.ALL) }
        )

        FilterChipItem(
            title = "Unread",
            isSelected = selectedFilter == ChatFilter.UNREAD,
            onClick = { onFilterSelected(ChatFilter.UNREAD) }
        )

        FilterChipItem(
            title = "Groups",
            isSelected = selectedFilter == ChatFilter.GROUPS,
            onClick = { onFilterSelected(ChatFilter.GROUPS) }
        )
    }
}

@Composable
fun FilterChipItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) WhatsAppFilterActiveBg else WhatsAppFilterInactiveBg,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("filter_chip_$title")
    ) {
        Text(
            text = title,
            color = if (isSelected) WhatsAppFilterActiveText else WhatsAppFilterInactiveText,
            fontSize = 13.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun ChatListItem(
    chat: ChatItem,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chat_item_${chat.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with status ring if active status update
        Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.Center
        ) {
            if (chat.hasStatusUpdate) {
                // Status ring around avatar
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(
                            width = 2.dp,
                            color = WhatsAppFabGreen,
                            shape = CircleShape
                        )
                )
            }

            Box(
                modifier = Modifier
                    .size(if (chat.hasStatusUpdate) 44.dp else 50.dp)
                    .clip(CircleShape)
                    .background(Color(chat.initialColor)),
                contentAlignment = Alignment.Center
            ) {
                if (chat.avatarRes != null) {
                    Image(
                        painter = painterResource(id = chat.avatarRes),
                        contentDescription = chat.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = chat.name.take(1),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Contact Name & Last Message snippet
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.name,
                color = Color(0xFF111B21),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.hasCheckmarks) {
                    Icon(
                        imageVector = Icons.Filled.DoneAll,
                        contentDescription = "Read",
                        tint = WhatsAppCheckmarkBlue,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 3.dp)
                    )
                }

                Text(
                    text = chat.lastMessage,
                    color = if (chat.unreadCount > 0) Color(0xFF111B21) else Color(0xFF667781),
                    fontSize = 14.sp,
                    fontWeight = if (chat.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right side: Timestamp & Unread badge or Pin icon
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = chat.timestamp,
                color = if (chat.unreadCount > 0) WhatsAppFabGreen else Color(0xFF667781),
                fontSize = 12.sp,
                fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(WhatsAppFabGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chat.unreadCount.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (chat.isPinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = Color(0xFF8696A0),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun WhatsAppBottomNavBar(
    selectedTab: DashboardTab,
    unreadChatsCount: Int,
    onTabSelected: (DashboardTab) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = WhatsAppBottomBarBg,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab 1: Chats
                BottomNavItem(
                    title = "Chats",
                    isSelected = selectedTab == DashboardTab.CHATS,
                    badgeCount = unreadChatsCount,
                    icon = { isSelected ->
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.Chat else Icons.Outlined.Chat,
                            contentDescription = "Chats",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    onClick = { onTabSelected(DashboardTab.CHATS) }
                )

                // Tab 2: Updates
                BottomNavItem(
                    title = "Updates",
                    isSelected = selectedTab == DashboardTab.UPDATES,
                    icon = { isSelected ->
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.Update else Icons.Outlined.Update,
                            contentDescription = "Updates",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    onClick = { onTabSelected(DashboardTab.UPDATES) }
                )

                // Tab 3: Stream
                BottomNavItem(
                    title = "Stream",
                    isSelected = selectedTab == DashboardTab.STREAM,
                    icon = { isSelected ->
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.LiveTv else Icons.Outlined.LiveTv,
                            contentDescription = "Stream",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    onClick = { onTabSelected(DashboardTab.STREAM) }
                )

                // Tab 4: Calls
                BottomNavItem(
                    title = "Calls",
                    isSelected = selectedTab == DashboardTab.CALLS,
                    icon = { isSelected ->
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.Call else Icons.Outlined.Call,
                            contentDescription = "Calls",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    onClick = { onTabSelected(DashboardTab.CALLS) }
                )

                // Tab 5: Profile
                BottomNavItem(
                    title = "Profile",
                    isSelected = selectedTab == DashboardTab.PROFILE,
                    icon = { isSelected ->
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.Person else Icons.Outlined.Person,
                            contentDescription = "Profile",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    onClick = { onTabSelected(DashboardTab.PROFILE) }
                )
            }
        }

        // Below the mobile nav section - styled with header background color
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(TriggerHeaderGreen)
        )
    }
}

@Composable
fun BottomNavItem(
    title: String,
    isSelected: Boolean,
    badgeCount: Int = 0,
    icon: @Composable (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            // Pill background for active tab matching modern WhatsApp
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) WhatsAppFilterActiveBg else Color.Transparent)
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides if (isSelected) WhatsAppFilterActiveText else Color(0xFF54656F)
                ) {
                    icon(isSelected)
                }
            }

            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = 4.dp, y = (-2).dp)
                        .clip(CircleShape)
                        .background(WhatsAppFabGreen)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = title,
            color = if (isSelected) WhatsAppFilterActiveText else Color(0xFF54656F),
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun UpdatesTabContent(
    onViewStatus: (String) -> Unit,
    onNewChat: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Status",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = GeometricTextDark
            )
        }

        item {
            // My status row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E4E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(WhatsAppFabGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add Status",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "My status",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GeometricTextDark
                    )
                    Text(
                        text = "Tap to add status update",
                        fontSize = 13.5.sp,
                        color = GeometricTextSecondary
                    )
                }
            }
        }

        item {
            Text(
                text = "Recent updates",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeometricTextSecondary
            )
        }

        item {
            Text(
                text = "No recent updates",
                fontSize = 14.sp,
                color = GeometricTextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
fun StatusItemRow(
    name: String,
    time: String,
    color: Long,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .border(2.5.dp, WhatsAppFabGreen, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(Color(color)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeometricTextDark
            )
            Text(
                text = time,
                fontSize = 13.sp,
                color = GeometricTextSecondary
            )
        }
    }
}

@Composable
fun CommunitiesTabContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(WhatsAppFilterActiveBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                tint = WhatsAppHeaderGreen,
                modifier = Modifier.size(50.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Stay connected with a community",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = GeometricTextDark,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Communities bring members together in topic-based groups, and make it easy to get admin announcements.",
            fontSize = 14.sp,
            color = GeometricTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { },
            colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Start your community", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CallsTabContent(
    onCallContact: (String) -> Unit,
    onVideoCallContact: (String) -> Unit = onCallContact
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Create call link
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(WhatsAppFabGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Create call link",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GeometricTextDark
                    )
                    Text(
                        text = "Share a link for your WhatsApp call",
                        fontSize = 13.sp,
                        color = GeometricTextSecondary
                    )
                }
            }
        }

        item {
            Text(
                text = "No recent calls",
                fontSize = 14.sp,
                color = GeometricTextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
fun CallLogItem(
    name: String,
    time: String,
    isVideo: Boolean,
    isIncoming: Boolean,
    avatarRes: Int?,
    onCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCall)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF80CBC4)),
            contentAlignment = Alignment.Center
        ) {
            if (avatarRes != null) {
                Image(
                    painter = painterResource(id = avatarRes),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = name.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeometricTextDark
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isIncoming) Icons.Filled.CallReceived else Icons.Filled.CallMade,
                    contentDescription = null,
                    tint = if (isIncoming) WhatsAppFabGreen else Color(0xFFD32F2F),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = time,
                    fontSize = 13.sp,
                    color = GeometricTextSecondary
                )
            }
        }

        IconButton(onClick = onCall) {
            Icon(
                imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                contentDescription = "Call",
                tint = WhatsAppHeaderGreen,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun StreamTabContent(
    onGoLive: () -> Unit = {},
    onNavigateToScheduleStream: () -> Unit = {},
    onNavigateToStreamHistory: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val agoraState by com.example.di.AppServiceContainer.agoraService.agoraState.collectAsState()
    val scheduledStreams by com.example.di.AppServiceContainer.streamScheduleService.scheduledStreams.collectAsState()
    val streamHistory by com.example.di.AppServiceContainer.streamScheduleService.streamHistory.collectAsState()

    var selectedSection by remember { mutableStateOf("My Stream") }
    var selectedStreamForBooking by remember { mutableStateOf<com.example.service.ScheduledStream?>(null) }
    var actionToastMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stream_tab_content"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Tabs Row (My Stream, Upcoming, Live Now, History)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedSection == "My Stream",
                    onClick = { selectedSection = "My Stream" },
                    label = { Text("My Stream (${streamHistory.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE8F5E9),
                        selectedLabelColor = TriggerHeaderGreen
                    )
                )
                FilterChip(
                    selected = selectedSection == "Upcoming",
                    onClick = { selectedSection = "Upcoming" },
                    label = { Text("Upcoming (${scheduledStreams.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE8F5E9),
                        selectedLabelColor = TriggerHeaderGreen
                    )
                )
                FilterChip(
                    selected = selectedSection == "Live Now",
                    onClick = { selectedSection = "Live Now" },
                    label = { Text("Live Now (${agoraState.activeStreams.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE8F5E9),
                        selectedLabelColor = TriggerHeaderGreen
                    )
                )
                FilterChip(
                    selected = selectedSection == "History",
                    onClick = { selectedSection = "History" },
                    label = { Text("History (${streamHistory.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE8F5E9),
                        selectedLabelColor = TriggerHeaderGreen
                    )
                )

                if (selectedSection == "History" || selectedSection == "My Stream") {
                    TextButton(onClick = onNavigateToStreamHistory) {
                        Text("View All", color = TriggerHeaderGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section Content
        when (selectedSection) {
            "My Stream" -> {
                // Show previous ended streaming
                if (streamHistory.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, tint = Color(0xFF667781), modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No Previous Streams", fontWeight = FontWeight.Bold, color = GeometricTextDark)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Your completed and ended streaming broadcasts will appear here.", fontSize = 12.sp, color = Color(0xFF667781))
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = onNavigateToScheduleStream,
                                    colors = ButtonDefaults.buttonColors(containerColor = TriggerHeaderGreen),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.Filled.VideoCall, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Create Stream", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(streamHistory, key = { it.id }) { item ->
                        StreamHistoryCard(item)
                    }
                }
            }

            "Upcoming" -> {
                // Show upcoming scheduled stream details
                if (scheduledStreams.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.EventAvailable, contentDescription = null, tint = Color(0xFF667781), modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No Upcoming Streams", fontWeight = FontWeight.Bold, color = GeometricTextDark)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Schedule upcoming streams with attendee slots and monetization.", fontSize = 12.sp, color = Color(0xFF667781))
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = onNavigateToScheduleStream,
                                    colors = ButtonDefaults.buttonColors(containerColor = TriggerHeaderGreen),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Schedule a Stream", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(scheduledStreams, key = { it.id }) { stream ->
                        ScheduledStreamCardItem(
                            stream = stream,
                            onBookClick = { selectedStreamForBooking = stream },
                            onShareClick = {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Join my live stream on Trigger App!\n📌 Title: ${stream.title}\n📅 Date: ${stream.date} at ${stream.time}\n🔗 Link: ${stream.shareLink}"
                                    )
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Stream Invite"))
                            }
                        )
                    }
                }
            }

            "Live Now" -> {
                if (agoraState.activeStreams.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.Podcasts, contentDescription = null, tint = Color(0xFF667781), modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No Active Live Streams", fontWeight = FontWeight.Bold, color = GeometricTextDark)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("There are no live streams broadcasting right now.", fontSize = 12.sp, color = Color(0xFF667781))
                            }
                        }
                    }
                } else {
                    items(agoraState.activeStreams, key = { it.id }) { stream ->
                        LiveStreamCard(
                            stream = stream,
                            onJoin = {
                                com.example.di.AppServiceContainer.agoraService.joinLiveStream(stream)
                            }
                        )
                    }
                }
            }

            "History" -> {
                if (streamHistory.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, tint = Color(0xFF667781), modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No Broadcast History", fontWeight = FontWeight.Bold, color = GeometricTextDark)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Completed broadcasts will appear here.", fontSize = 12.sp, color = Color(0xFF667781))
                            }
                        }
                    }
                } else {
                    items(streamHistory, key = { it.id }) { item ->
                        StreamHistoryCard(item)
                    }
                }
            }
        }
    }

    // Stream Booking & Slot Verification Dialog
    selectedStreamForBooking?.let { stream ->
        StreamBookingDialog(
            stream = stream,
            onDismiss = { selectedStreamForBooking = null },
            onBookingSuccess = {
                selectedStreamForBooking = null
            }
        )
    }
}

@Composable
fun ScheduledStreamCardItem(
    stream: com.example.service.ScheduledStream,
    onBookClick: () -> Unit,
    onShareClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFE8F5E9)
                ) {
                    Text(
                        text = stream.category,
                        color = Color(0xFF2E7D32),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (stream.type == com.example.service.StreamPricingType.PAID) Color(0xFFFFF3E0) else Color(0xFFE3F2FD)
                ) {
                    Text(
                        text = if (stream.type == com.example.service.StreamPricingType.PAID) "PAID (${stream.priceDisplay})" else "FREE",
                        color = if (stream.type == com.example.service.StreamPricingType.PAID) Color(0xFFE65100) else Color(0xFF1565C0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Capacity status
                val slotText = if (stream.isUnlimitedSlots) {
                    "Unlimited Slots"
                } else {
                    "${stream.slotsBooked} / ${stream.maxSlots} Booked"
                }
                Text(
                    text = slotText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (stream.isFull) Color(0xFFD32F2F) else Color(0xFF008069)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stream.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GeometricTextDark
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Hosted by ${stream.hostName}",
                fontSize = 13.sp,
                color = Color(0xFF667781)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = Color(0xFF00A884), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stream.date, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GeometricTextDark)
                Spacer(modifier = Modifier.width(12.dp))
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color(0xFF00A884), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stream.time, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GeometricTextDark)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFF1F5F9))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Share link button
                OutlinedButton(
                    onClick = onShareClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF008069))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Link", fontSize = 12.sp, color = Color(0xFF008069))
                }

                // Booking / Join action
                Button(
                    onClick = onBookClick,
                    enabled = !stream.isFull,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stream.isJoined) Color(0xFF4CAF50) else Color(0xFF008069)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when {
                            stream.isFull -> "Sold Out"
                            stream.isJoined -> "Slot Booked ✓"
                            stream.type == com.example.service.StreamPricingType.PAID -> "Pay ${stream.priceDisplay} & Book"
                            else -> "Reserve Free Slot"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun LiveStreamCard(
    stream: com.example.service.webrtc.AgoraLiveStream,
    onJoin: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJoin)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFE8F5E9)
                ) {
                    Text(
                        text = stream.category,
                        color = Color(0xFF2E7D32),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFEBEE)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD32F2F))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${stream.viewerCount} watching",
                            color = Color(0xFFD32F2F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stream.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GeometricTextDark,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(TriggerChatTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stream.streamerName.take(1),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = stream.streamerName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = GeometricTextDark,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onJoin,
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Join", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AgoraActiveCallDialog(
    contactName: String,
    isVideo: Boolean,
    onDismiss: () -> Unit
) {
    val agoraState by com.example.di.AppServiceContainer.agoraService.agoraState.collectAsState()
    val minutes = agoraState.durationSeconds / 60
    val seconds = agoraState.durationSeconds % 60
    val durationFormatted = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = TriggerChatTeal
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isVideo && agoraState.isVideoEnabled) {
                    val engineState by com.example.di.AppServiceContainer.agoraRtcEngineManager.engineState.collectAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E2830)),
                        contentAlignment = Alignment.Center
                    ) {
                        val remoteUid = engineState.remoteUid
                        if (remoteUid != null) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { ctx ->
                                    android.view.SurfaceView(ctx).apply {
                                        com.example.di.AppServiceContainer.agoraRtcEngineManager.setupRemoteVideo(this, remoteUid)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = TriggerFabGreen, strokeWidth = 3.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Connecting Agora Video Feed…",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Channel: ${agoraState.channelName.ifEmpty { "call_channel" }}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Local camera PiP in corner
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 90.dp, end = 16.dp)
                                .size(width = 100.dp, height = 140.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black,
                            shadowElevation = 6.dp
                        ) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { ctx ->
                                    android.view.SurfaceView(ctx).apply {
                                        setZOrderMediaOverlay(true)
                                        com.example.di.AppServiceContainer.agoraRtcEngineManager.setupLocalVideo(this)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(TriggerHeaderGreen, TriggerChatTeal)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(CircleShape)
                                    .background(TriggerChatTeal),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (if (agoraState.remoteUserName.isNotEmpty()) agoraState.remoteUserName else contactName).take(1),
                                    color = Color.White,
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Top bar info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (agoraState.remoteUserName.isNotEmpty()) agoraState.remoteUserName else contactName,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (agoraState.status) {
                            com.example.service.webrtc.AgoraCallStatus.DIALING -> "Dialing…"
                            com.example.service.webrtc.AgoraCallStatus.RINGING -> "Ringing…"
                            com.example.service.webrtc.AgoraCallStatus.CONNECTED -> "Connected • $durationFormatted"
                            com.example.service.webrtc.AgoraCallStatus.DISCONNECTED -> "Call Ended"
                            com.example.service.webrtc.AgoraCallStatus.FAILED -> "Call Failed"
                            else -> "Connecting…"
                        },
                        color = if (agoraState.status == com.example.service.webrtc.AgoraCallStatus.CONNECTED) TriggerFabGreen else Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Bottom Call Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute button
                    IconButton(
                        onClick = { com.example.di.AppServiceContainer.agoraService.toggleMute() },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (agoraState.isMuted) Color.White else Color(0x33FFFFFF))
                    ) {
                        Icon(
                            imageVector = if (agoraState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Mute",
                            tint = if (agoraState.isMuted) Color.Black else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    if (isVideo) {
                        IconButton(
                            onClick = { com.example.di.AppServiceContainer.agoraService.toggleVideo() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (!agoraState.isVideoEnabled) Color.White else Color(0x33FFFFFF))
                        ) {
                            Icon(
                                imageVector = if (agoraState.isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                contentDescription = "Video",
                                tint = if (!agoraState.isVideoEnabled) Color.Black else Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = { com.example.di.AppServiceContainer.agoraService.switchCamera() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FlipCameraAndroid,
                                contentDescription = "Switch Camera",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { com.example.di.AppServiceContainer.agoraService.toggleSpeaker() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (agoraState.isSpeakerOn) Color.White else Color(0x33FFFFFF))
                        ) {
                            Icon(
                                imageVector = if (agoraState.isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                                contentDescription = "Speaker",
                                tint = if (agoraState.isSpeakerOn) Color.Black else Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // End Call button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgoraGoLiveDialog(
    onDismiss: () -> Unit
) {
    var streamTitle by remember { mutableStateOf("") }
    var streamCategory by remember { mutableStateOf("Tech & Dev") }
    val categories = listOf("Tech & Dev", "Live Talk", "Gaming", "Music", "Q&A")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Start Live Broadcast",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = GeometricTextDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Stream real-time video with Agora WebRTC Engine",
                fontSize = 13.sp,
                color = GeometricTextSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Stream Title",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeometricTextDark
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = streamTitle,
                onValueChange = { streamTitle = it },
                placeholder = { Text("e.g. My First Live Stream") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Category",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = GeometricTextDark
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.take(3).forEach { cat ->
                    val isSelected = streamCategory == cat
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) TriggerFabGreen else Color(0xFFF0F2F5),
                        modifier = Modifier.clickable { streamCategory = cat }
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.White else GeometricTextDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val finalTitle = streamTitle.ifBlank { "Live Broadcast" }
                    val channel = "stream_" + System.currentTimeMillis()
                    com.example.di.AppServiceContainer.agoraService.startLiveStream(finalTitle, channel)
                    onDismiss()
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.LiveTv, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Go Live Now",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
