package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onRestartFlow: () -> Unit
) {
    val viewModel: com.example.ui.viewmodel.DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dbConversations by viewModel.conversations.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    val agoraCallState by com.example.di.AppServiceContainer.agoraService.callState.collectAsState()

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
        containerColor = Color.White,
        topBar = {
            if (selectedTab != DashboardTab.PROFILE) {
                Column {
                    WhatsAppTopHeader(
                        onCameraClick = { showCameraDialog = true },
                        onMenuClick = { showTopMenu = true },
                        showMenu = showTopMenu,
                        onDismissMenu = { showTopMenu = false },
                        onOpenProfile = { selectedTab = DashboardTab.PROFILE },
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
                        onClick = { showGoLiveDialog = true },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = TriggerFabGreen,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(3.dp),
                        modifier = Modifier.testTag("go_live_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LiveTv,
                            contentDescription = "Go Live",
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
                            imageVector = Icons.Filled.AddCall,
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
                        onGoLive = { showGoLiveDialog = true }
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
                        onBack = { selectedTab = DashboardTab.CHATS }
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
            containerColor = Color(0xFF111B21),
            title = {
                Text(
                    text = "${showStatusStoryDialog}'s Status",
                    color = Color.White,
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
                        color = Color(0xFF8696A0),
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusStoryDialog = null }) {
                    Text("Close", color = WhatsAppFabGreen)
                }
            }
        )
    }

    // Camera preview dialog
    if (showCameraDialog) {
        AlertDialog(
            onDismissRequest = { showCameraDialog = false },
            containerColor = Color(0xFF1F2C34),
            title = {
                Text("Camera", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
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
            }
        )
    }
}

@Composable
fun WhatsAppTopHeader(
    onCameraClick: () -> Unit,
    onMenuClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onRestartOnboarding: () -> Unit,
    onToggleNetwork: () -> Unit = {}
) {
    Surface(
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand name "Trigger App"
            Text(
                text = "Trigger App",
                color = TriggerHeaderGreen,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f)
            )

            // Top action icons: Camera & MoreVert
            IconButton(
                onClick = onCameraClick,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = "Camera",
                    tint = Color(0xFF111B21),
                    modifier = Modifier.size(24.dp)
                )
            }

            Box {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = Color(0xFF111B21),
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
                        text = { Text("New broadcast", color = GeometricTextDark) },
                        onClick = onDismissMenu
                    )
                    DropdownMenuItem(
                        text = { Text("Linked devices", color = GeometricTextDark) },
                        onClick = onDismissMenu
                    )
                    DropdownMenuItem(
                        text = { Text("Simulate Network (Online / Offline)", color = GeometricTextDark) },
                        onClick = {
                            onDismissMenu()
                            onToggleNetwork()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Starred messages", color = GeometricTextDark) },
                        onClick = onDismissMenu
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = GeometricTextDark) },
                        onClick = {
                            onDismissMenu()
                            onOpenProfile()
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
    Surface(
        color = WhatsAppBottomBarBg,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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
            // Status item 1: Jonathan Miller
            StatusItemRow(
                name = "Jonathan Miller",
                time = "32 minutes ago",
                color = 0xFF43A047,
                onClick = { onViewStatus("Jonathan Miller") }
            )
        }

        item {
            // Status item 2: Lillian Evaro
            StatusItemRow(
                name = "Lillian Evaro",
                time = "Today, 8:03 AM",
                color = 0xFF00ACC1,
                onClick = { onViewStatus("Lillian Evaro") }
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
fun CallsTabContent(onCallContact: (String) -> Unit) {
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
                text = "Recent",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = GeometricTextDark
            )
        }

        item {
            CallLogItem(
                name = "darling",
                time = "Today, 10:20 AM",
                isVideo = true,
                isIncoming = true,
                avatarRes = R.drawable.img_darling_avatar,
                onCall = { onCallContact("darling") }
            )
        }

        item {
            CallLogItem(
                name = "Maya Townsend",
                time = "Yesterday, 6:45 PM",
                isVideo = false,
                isIncoming = false,
                avatarRes = null,
                onCall = { onCallContact("Maya Townsend") }
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
