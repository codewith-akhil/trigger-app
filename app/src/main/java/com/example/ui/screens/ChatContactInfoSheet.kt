package com.example.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.DisappearingDuration
import com.example.model.DomainConversation
import com.example.model.DomainMessage
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContactInfoSheet(
    conversation: DomainConversation?,
    contactName: String,
    contactAvatarRes: Int?,
    mediaMessages: List<DomainMessage>,
    documentMessages: List<DomainMessage>,
    onClose: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSetDisappearingMessages: (DisappearingDuration) -> Unit,
    onClearChat: () -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onBlockContact: () -> Unit = {},
    onUnblockContact: () -> Unit = {}
) {
    var selectedMediaTab by remember { mutableStateOf(0) } // 0: Media, 1: Docs
    var showDisappearingDialog by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showUnblockDialog by remember { mutableStateOf(false) }
    var showMuteDialog by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(conversation?.isMuted ?: false) }
    val isBlocked = conversation?.isBlocked ?: false

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
                                    .background(Color(0xFF25D366)),
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
                                text = "+1 (555) 382-9014",
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
                                    .clickable { showDisappearingDialog = true }
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
                                        text = "Disappearing messages",
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
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (selectedMediaTab == 0) {
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
                                            Image(
                                                painter = painterResource(id = R.drawable.img_media_sample),
                                                contentDescription = "Shared media",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                        }
                                    }
                                }
                            } else {
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
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(30.dp)) }
            }
        }
    }

    // Disappearing Messages picker dialog
    if (showDisappearingDialog) {
        AlertDialog(
            onDismissRequest = { showDisappearingDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Disappearing messages",
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "For more privacy and storage, new messages will disappear from this chat for everyone after the selected duration.",
                        fontSize = 14.sp,
                        color = Color(0xFF667781)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    DisappearingDuration.values().forEach { duration ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetDisappearingMessages(duration)
                                    showDisappearingDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = conversation?.disappearingDuration == duration,
                                onClick = {
                                    onSetDisappearingMessages(duration)
                                    showDisappearingDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = WhatsAppFabGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = duration.displayName,
                                fontSize = 15.sp,
                                color = Color(0xFF111B21)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDisappearingDialog = false }) {
                    Text("Cancel", color = WhatsAppFabGreen)
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
                        onBlockContact()
                        onClose()
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
                        onUnblockContact()
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

    // Mute Notifications options dialog
    if (showMuteDialog) {
        var selectedDuration by remember { mutableStateOf("8 hours") }
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
