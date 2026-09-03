package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ScreenGreenHeader = Color(0xFF008069)
private val ScreenBg = Color(0xFFF7F8FA)
private val CardBg = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF111B21)
private val TextSecondary = Color(0xFF667781)
private val IconTint = Color(0xFF54656F)
private val DividerColor = Color(0xFFF0F2F5)
private val SwitchGreen = Color(0xFF00A884)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var readReceiptsEnabled by remember { mutableStateOf(true) }
    var fingerprintLockEnabled by remember { mutableStateOf(false) }

    var lastSeenChoice by remember { mutableStateOf("Everyone") }
    var profilePhotoChoice by remember { mutableStateOf("My contacts") }
    var aboutChoice by remember { mutableStateOf("Everyone") }
    var disappearingTimerChoice by remember { mutableStateOf("Off") }

    var showPickerTitle by remember { mutableStateOf<String?>(null) }
    var pickerOptions by remember { mutableStateOf(listOf<String>()) }
    var onSelectedPickerOption by remember { mutableStateOf<(String) -> Unit>({}) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("privacy_settings_screen"),
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Privacy",
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
            Text(
                text = "Who can see my personal info",
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
                    PrivacyOptionRow(
                        title = "Last seen and online",
                        value = lastSeenChoice,
                        onClick = {
                            showPickerTitle = "Last seen and online"
                            pickerOptions = listOf("Everyone", "My contacts", "Nobody")
                            onSelectedPickerOption = { lastSeenChoice = it }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    PrivacyOptionRow(
                        title = "Profile photo",
                        value = profilePhotoChoice,
                        onClick = {
                            showPickerTitle = "Profile photo"
                            pickerOptions = listOf("Everyone", "My contacts", "Nobody")
                            onSelectedPickerOption = { profilePhotoChoice = it }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    PrivacyOptionRow(
                        title = "About",
                        value = aboutChoice,
                        onClick = {
                            showPickerTitle = "About"
                            pickerOptions = listOf("Everyone", "My contacts", "Nobody")
                            onSelectedPickerOption = { aboutChoice = it }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    // Read receipts
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Read receipts",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "If turned off, you won't send or receive read receipts (blue ticks)",
                                fontSize = 13.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = readReceiptsEnabled,
                            onCheckedChange = { readReceiptsEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Disappearing messages & Security",
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
                    PrivacyOptionRow(
                        title = "Default message timer",
                        value = disappearingTimerChoice,
                        onClick = {
                            showPickerTitle = "Default message timer"
                            pickerOptions = listOf("24 hours", "7 days", "90 days", "Off")
                            onSelectedPickerOption = { disappearingTimerChoice = it }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    // Fingerprint lock
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fingerprint / App lock",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (fingerprintLockEnabled) "Unlock with biometric" else "Disabled",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = fingerprintLockEnabled,
                            onCheckedChange = { fingerprintLockEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    // Blocked contacts
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { /* View blocked contacts */ }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blocked contacts",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "None",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Selection Dialog for options
    if (showPickerTitle != null) {
        AlertDialog(
            onDismissRequest = { showPickerTitle = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(showPickerTitle.orEmpty(), fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    pickerOptions.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectedPickerOption(opt)
                                    showPickerTitle = null
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (opt == lastSeenChoice || opt == profilePhotoChoice || opt == aboutChoice || opt == disappearingTimerChoice),
                                onClick = {
                                    onSelectedPickerOption(opt)
                                    showPickerTitle = null
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = SwitchGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = opt, fontSize = 15.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPickerTitle = null }) {
                    Text("Done", color = ScreenGreenHeader)
                }
            }
        )
    }
}

@Composable
private fun PrivacyOptionRow(
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
