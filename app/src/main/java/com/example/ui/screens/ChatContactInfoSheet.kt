package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.example.ui.components.TriggerAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.DisappearingDuration
import com.example.model.DomainConversation
import com.example.model.DomainMessage
import com.example.service.SharedLink
import com.example.ui.theme.*
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContactInfoSheet(
    conversation: DomainConversation?,
    contactName: String,
    contactAvatarRes: Int?,
    mediaMessages: List<DomainMessage>,
    documentMessages: List<DomainMessage>,
    starredMessages: List<DomainMessage> = emptyList(),
    sharedLinks: List<SharedLink> = emptyList(),
    onClose: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onOpenAutoDelete: () -> Unit,
    onClearChat: () -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onBlockContact: () -> Unit = {},
    onUnblockContact: () -> Unit = {},
    onReportUser: (String) -> Unit = {},
    onJumpToMessage: (String) -> Unit = {}
) {
    var selectedMediaTab by remember { mutableStateOf(0) } // 0: Media, 1: Docs, 2: Links, 3: Starred
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showUnblockDialog by remember { mutableStateOf(false) }
    var showMuteDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(conversation?.isMuted ?: false) }
    val isBlocked = conversation?.isBlocked ?: false
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("contact_info_screen"),
        color = Color(0xFFF0F2F5)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Surface(
                color = WhatsAppChatDarkTeal,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Contact info",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Contact Header Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFD81B60)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (contactAvatarRes != null) {
                                    Image(
                                        painter = painterResource(id = contactAvatarRes),
                                        contentDescription = contactName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = contactName,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111B21)
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Contact details",
                                fontSize = 15.sp,
                                color = Color(0xFF667781)
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            // Action buttons: Audio & Video Call
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                ActionPillButton(
                                    icon = Icons.Filled.Call,
                                    label = "Audio",
                                    onClick = onVoiceCall
                                )
                                ActionPillButton(
                                    icon = Icons.Filled.Videocam,
                                    label = "Video",
                                    onClick = onVideoCall
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(10.dp)) }

                // Settings Section (Mute & Disappearing)
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Mute Switch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isMuted) {
                                            isMuted = false
                                            onToggleMute(false)
                                        } else {
                                            showMuteDialog = true
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Notifications,
                                    contentDescription = null,
                                    tint = Color(0xFF667781)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Mute notifications",
                                        fontSize = 16.sp,
                                        color = Color(0xFF111B21)
                                    )
                                }
                                Switch(
                                    checked = isMuted,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            showMuteDialog = true
                                        } else {
                                            isMuted = false
                                            onToggleMute(false)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = WhatsAppFabGreen
                                    )
                                )
                            }

                            HorizontalDivider(color = Color(0xFFF0F2F5))

                            // Disappearing Messages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenAutoDelete() }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Timer,
                                    contentDescription = null,
                                    tint = Color(0xFF667781)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Auto delete",
                                        fontSize = 16.sp,
                                        color = Color(0xFF111B21)
                                    )
                                    Text(
                                        text = conversation?.disappearingDuration?.displayName ?: "Off",
                                        fontSize = 13.sp,
                                        color = Color(0xFF667781)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = Color(0xFF8696A0)
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(10.dp)) }

                // Media, Links, and Docs Section
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Media, links, and docs",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF111B21)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            TabRow(
                                selectedTabIndex = selectedMediaTab,
                                containerColor = Color.Transparent,
                                contentColor = WhatsAppFabGreen
                            ) {
                                Tab(
                                    selected = selectedMediaTab == 0,
                                    onClick = { selectedMediaTab = 0 },
                                    text = { Text("Media (${mediaMessages.size})") }
                                )
                                Tab(
                                    selected = selectedMediaTab == 1,
                                    onClick = { selectedMediaTab = 1 },
                                    text = { Text("Docs (${documentMessages.size})") }
                                )
                                Tab(
                                    selected = selectedMediaTab == 2,
                                    onClick = { selectedMediaTab = 2 },
                                    text = { Text("Links (${sharedLinks.size})") }
                                )
                                Tab(
                                    selected = selectedMediaTab == 3,
                                    onClick = { selectedMediaTab = 3 },
                                    text = { Text("Starred (${starredMessages.size})") }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            when (selectedMediaTab) {
                                0 -> {
                                    if (mediaMessages.isEmpty()) {
                                        Text(
                                            text = "No shared media yet",
                                            color = Color(0xFF8696A0),
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(vertical = 20.dp)
                                        )
                                    } else {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            mediaMessages.take(4).forEach {
                                                AsyncImage(
                                                    // Task 24: stable bucket/path cache keys
                                                    // for private-bucket media.
                                                    model = privateMediaModel(
                                                        it.mediaBucket,
                                                        it.mediaThumbnail ?: it.mediaUrl,
                                                        if (it.mediaThumbnail != null) null else it.mediaPath
                                                    ),
                                                    contentDescription = "Shared media",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                                1 -> {
                                    if (documentMessages.isEmpty()) {
                                        Text(
                                            text = "No shared documents yet",
                                            color = Color(0xFF8696A0),
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(vertical = 20.dp)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            documentMessages.forEach { doc ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Description,
                                                        contentDescription = null,
                                                        tint = Color(0xFF7F66FF),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = doc.fileName ?: "Document.pdf",
                                                        fontSize = 14.sp,
                                                        color = Color(0xFF111B21),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                2 -> {
                                    // Shared links tab — queries the shared_links view
                                    if (sharedLinks.isEmpty()) {
                                        Text(
                                            text = "No shared links yet",
                                            color = Color(0xFF8696A0),
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(vertical = 20.dp)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            sharedLinks.forEach { link ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            // Open the link in the browser
                                                            val url = link.text.trim()
                                                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                                                try {
                                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                                    context.startActivity(intent)
                                                                } catch (_: Exception) {}
                                                            }
                                                        }
                                                        .padding(vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Link,
                                                        contentDescription = null,
                                                        tint = WhatsAppFabGreen,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = link.text,
                                                        fontSize = 14.sp,
                                                        color = WhatsAppChatTeal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                3 -> {
                                    // Starred messages tab — tap to jump to the message
                                    if (starredMessages.isEmpty()) {
                                        Text(
                                            text = "No starred messages yet",
                                            color = Color(0xFF8696A0),
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(vertical = 20.dp)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            starredMessages.forEach { msg ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { onJumpToMessage(msg.id) }
                                                        .padding(vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Star,
                                                        contentDescription = null,
                                                        tint = Color(0xFFFFC107),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = msg.text,
                                                        fontSize = 14.sp,
                                                        color = Color(0xFF111B21),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
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

                item { Spacer(modifier = Modifier.height(10.dp)) }

                // Clear Chat action
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showClearChatDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFEA4335)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "Clear chat",
                                    fontSize = 16.sp,
                                    color = Color(0xFFEA4335),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            HorizontalDivider(color = Color(0xFFF0F2F5))

                            // Block / Unblock Contact action
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isBlocked) showUnblockDialog = true
                                        else showBlockDialog = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Block,
                                    contentDescription = null,
                                    tint = if (isBlocked) WhatsAppFabGreen else Color(0xFFEA4335)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = if (isBlocked) "Unblock $contactName" else "Block $contactName",
                                    fontSize = 16.sp,
                                    color = if (isBlocked) WhatsAppFabGreen else Color(0xFFEA4335),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            HorizontalDivider(color = Color(0xFFF0F2F5))

                            // Report user action
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showReportDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Report,
                                    contentDescription = null,
                                    tint = Color(0xFFEA4335)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "Report $contactName",
                                    fontSize = 16.sp,
                                    color = Color(0xFFEA4335),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(30.dp)) }
            }
        }
    }

    // Disappearing Messages picker removed — "Auto delete" now opens the
    // shared AutoDeleteDialog on the chat screen (single source of truth for
    // the spec copy: title, body, 24h/7d/30d/Off options, UPDATE/CANCEL).

    // Clear Chat alert dialog
    if (showClearChatDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Clear this chat?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "Messages will be removed from this device and cannot be recovered.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearChatDialog = false
                        onClearChat()
                        onClose()
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

    // Block Contact alert dialog — compact WhatsApp-style card
    if (showBlockDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showBlockDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Block $contactName?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "They won't be able to message or call you anymore.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockDialog = false
                        onBlockContact()
                        onClose()
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335))
                ) {
                    Text("Block", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel", color = Color(0xFFAD1457), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // Unblock Contact alert dialog — compact WhatsApp-style card
    if (showUnblockDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showUnblockDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Unblock $contactName?",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "They will be able to message and call you again.",
                    color = Color(0xFF667781),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnblockDialog = false
                        onUnblockContact()
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppFabGreen)
                ) {
                    Text("Unblock", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnblockDialog = false }) {
                    Text("Cancel", color = Color(0xFFAD1457), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // Mute Notifications options dialog
    if (showMuteDialog) {
        var selectedDuration by remember { mutableStateOf("8 hours") }
        TriggerAlertDialog(
            onDismissRequest = { showMuteDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Mute notifications for...",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column {
                    listOf("8 hours", "1 week", "Always").forEach { durationText ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDuration = durationText }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedDuration == durationText,
                                onClick = { selectedDuration = durationText },
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
                        isMuted = true
                        onToggleMute(true)
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

    // Report user dialog — compact WhatsApp-style card (2-line text + block
    // checkbox + Cancel/Report text buttons). No icon, no free-text field.
    if (showReportDialog) {
        var reportAndBlock by remember { mutableStateOf(false) }
        TriggerAlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Report $contactName",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "The last 5 messages in this chat will be sent to Trigger. $contactName won't know you reported them.",
                        fontSize = 14.sp,
                        color = Color(0xFF667781),
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = reportAndBlock,
                            onCheckedChange = { reportAndBlock = it }
                        )
                        Column {
                            Text(
                                text = "Block $contactName",
                                fontSize = 15.sp,
                                color = Color(0xFF111B21)
                            )
                            Text(
                                text = "They won't be able to message or call you.",
                                fontSize = 12.5.sp,
                                color = Color(0xFF667781)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (reportAndBlock) onBlockContact()
                        onReportUser("Reported from chat info")
                        showReportDialog = false
                    }
                ) {
                    Text("Report", color = Color(0xFFAD1457), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Cancel", color = Color(0xFFAD1457), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
fun ActionPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFFF0F2F5)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = WhatsAppFabGreen,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = Color(0xFF111B21),
            fontWeight = FontWeight.Medium
        )
    }
}
