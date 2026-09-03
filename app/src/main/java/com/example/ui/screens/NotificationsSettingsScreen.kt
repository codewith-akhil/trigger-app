package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ScreenGreenHeader = Color(0xFF008069)
private val ScreenBg = Color(0xFFF7F8FA)
private val CardBg = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF111B21)
private val TextSecondary = Color(0xFF667781)
private val DividerColor = Color(0xFFF0F2F5)
private val SwitchGreen = Color(0xFF00A884)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var conversationTones by remember { mutableStateOf(true) }
    var highPriorityMessages by remember { mutableStateOf(true) }
    var messageTone by remember { mutableStateOf("Default (Trigger Bell)") }
    var messageVibrate by remember { mutableStateOf("Default") }
    var groupTone by remember { mutableStateOf("Chime") }
    var callRingtone by remember { mutableStateOf("Trigger Call") }

    var tonePickerTitle by remember { mutableStateOf<String?>(null) }
    var toneOptions by remember { mutableStateOf(listOf<String>()) }
    var onToneSelected by remember { mutableStateOf<(String) -> Unit>({}) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("notifications_settings_screen"),
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notifications",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenGreenHeader)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Conversation tones
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Conversation tones",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Play sounds for incoming and outgoing messages",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = conversationTones,
                        onCheckedChange = { conversationTones = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SwitchGreen
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Messages section
            Text(
                text = "Messages",
                color = ScreenGreenHeader,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NotificationToneRow(
                        title = "Notification tone",
                        value = messageTone,
                        onClick = {
                            tonePickerTitle = "Message Notification tone"
                            toneOptions = listOf("Default (Trigger Bell)", "Chime", "Whistle", "Silent")
                            onToneSelected = { messageTone = it }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    NotificationToneRow(
                        title = "Vibrate",
                        value = messageVibrate,
                        onClick = {
                            tonePickerTitle = "Vibrate"
                            toneOptions = listOf("Off", "Default", "Short", "Long")
                            onToneSelected = { messageVibrate = it }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use high priority notifications",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Show previews of notifications at the top of the screen",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = highPriorityMessages,
                            onCheckedChange = { highPriorityMessages = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Groups section
            Text(
                text = "Groups",
                color = ScreenGreenHeader,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NotificationToneRow(
                        title = "Group Notification tone",
                        value = groupTone,
                        onClick = {
                            tonePickerTitle = "Group Notification tone"
                            toneOptions = listOf("Default (Trigger Bell)", "Chime", "Echo", "Silent")
                            onToneSelected = { groupTone = it }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Calls section
            Text(
                text = "Calls",
                color = ScreenGreenHeader,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NotificationToneRow(
                        title = "Ringtone",
                        value = callRingtone,
                        onClick = {
                            tonePickerTitle = "Call Ringtone"
                            toneOptions = listOf("Trigger Call", "Classic Phone", "Acoustic", "Digital")
                            onToneSelected = { callRingtone = it }
                        }
                    )
                }
            }
        }
    }

    if (tonePickerTitle != null) {
        AlertDialog(
            onDismissRequest = { tonePickerTitle = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(tonePickerTitle.orEmpty(), fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    toneOptions.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToneSelected(opt)
                                    tonePickerTitle = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (opt == messageTone || opt == messageVibrate || opt == groupTone || opt == callRingtone),
                                onClick = {
                                    onToneSelected(opt)
                                    tonePickerTitle = null
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = SwitchGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = opt, fontSize = 15.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun NotificationToneRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                color = TextSecondary
            )
        }
    }
}
