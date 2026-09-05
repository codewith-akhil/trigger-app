package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    val coroutineScope = rememberCoroutineScope()
    val client = AppServiceContainer.supabaseClient

    var readReceiptsEnabled by remember { mutableStateOf(true) }
    var fingerprintLockEnabled by remember { mutableStateOf(false) }

    var lastSeenChoice by remember { mutableStateOf("Everyone") }
    var profilePhotoChoice by remember { mutableStateOf("My contacts") }
    var aboutChoice by remember { mutableStateOf("Everyone") }
    var disappearingTimerChoice by remember { mutableStateOf("Off") }

    var showPickerTitle by remember { mutableStateOf<String?>(null) }
    var pickerOptions by remember { mutableStateOf(listOf<String>()) }
    var onSelectedPickerOption by remember { mutableStateOf<(String) -> Unit>({}) }

    // Blocked-contacts state.
    data class BlockedContact(val id: String, val identifier: String)
    var blockedContacts by remember { mutableStateOf<List<BlockedContact>>(emptyList()) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var blockedLoading by remember { mutableStateOf(false) }
    var blockedError by remember { mutableStateOf<String?>(null) }
    val blockedSubtitle = when {
        blockedContacts.isEmpty() -> "None"
        blockedContacts.size == 1 -> "1 blocked"
        else -> "${blockedContacts.size} blocked"
    }

    // Hydrate from server (best-effort).
    LaunchedEffect(Unit) {
        when (val res = client.invokeFunction("get-my-profile")) {
            is SupabaseResult.Success -> {
                val settings = res.data.optJSONObject("settings")
                if (settings != null) {
                    if (settings.has("readReceipts"))
                        readReceiptsEnabled = settings.getBoolean("readReceipts")
                    if (settings.has("fingerprintLock"))
                        fingerprintLockEnabled = settings.getBoolean("fingerprintLock")
                    if (settings.has("lastSeen"))
                        lastSeenChoice = serverVisibilityToLabel(settings.optString("lastSeen"))
                    if (settings.has("profilePhotoVisibility"))
                        profilePhotoChoice = serverVisibilityToLabel(settings.optString("profilePhotoVisibility"))
                    if (settings.has("aboutVisibility"))
                        aboutChoice = serverVisibilityToLabel(settings.optString("aboutVisibility"))
                    if (settings.has("disappearingDefault"))
                        disappearingTimerChoice = serverDisappearingToLabel(settings.optString("disappearingDefault"))
                }
            }
            is SupabaseResult.Error -> Unit
        }
    }

    fun persistSetting(key: String, value: Any) {
        val payload = JSONObject()
        when (value) {
            is Boolean -> payload.put(key, value)
            is String -> payload.put(key, value)
        }
        coroutineScope.launch {
            client.invokeFunction("update-user-settings", payload)
        }
    }

    fun loadBlockedContacts() {
        blockedLoading = true
        blockedError = null
        coroutineScope.launch {
            val payload = JSONObject().put("action", "list")
            when (val res = client.invokeFunction("manage-blocked-contacts", payload)) {
                is SupabaseResult.Success -> {
                    val arr = res.data.optJSONArray("blocked")
                    val list = ArrayList<BlockedContact>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val row = arr.getJSONObject(i)
                            list += BlockedContact(
                                id = row.optString("id", ""),
                                identifier = row.optString("blocked_identifier", "Unknown")
                            )
                        }
                    }
                    blockedContacts = list
                    blockedLoading = false
                }
                is SupabaseResult.Error -> {
                    blockedError = res.message
                    blockedLoading = false
                }
            }
        }
    }

    fun unblockContact(contact: BlockedContact) {
        coroutineScope.launch {
            val payload = JSONObject()
                .put("action", "unblock")
                .put("blockedIdentifier", contact.identifier)
            when (val res = client.invokeFunction("manage-blocked-contacts", payload)) {
                is SupabaseResult.Success -> loadBlockedContacts()
                is SupabaseResult.Error -> blockedError = res.message
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("privacy_settings_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = ScreenBg,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Privacy",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
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
                            onSelectedPickerOption = {
                                lastSeenChoice = it
                                persistSetting("lastSeen", labelToServerVisibility(it))
                            }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    PrivacyOptionRow(
                        title = "Profile photo",
                        value = profilePhotoChoice,
                        onClick = {
                            showPickerTitle = "Profile photo"
                            pickerOptions = listOf("Everyone", "My contacts", "Nobody")
                            onSelectedPickerOption = {
                                profilePhotoChoice = it
                                persistSetting("profilePhotoVisibility", labelToServerVisibility(it))
                            }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    PrivacyOptionRow(
                        title = "About",
                        value = aboutChoice,
                        onClick = {
                            showPickerTitle = "About"
                            pickerOptions = listOf("Everyone", "My contacts", "Nobody")
                            onSelectedPickerOption = {
                                aboutChoice = it
                                persistSetting("aboutVisibility", labelToServerVisibility(it))
                            }
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
                            onCheckedChange = {
                                readReceiptsEnabled = it
                                persistSetting("readReceipts", it)
                            },
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
                            onSelectedPickerOption = {
                                disappearingTimerChoice = it
                                persistSetting("disappearingDefault", labelToServerDisappearing(it))
                            }
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
                            onCheckedChange = {
                                fingerprintLockEnabled = it
                                persistSetting("fingerprintLock", it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    // Blocked contacts — wired to manage-blocked-contacts edge fn.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showBlockedDialog = true
                                loadBlockedContacts()
                            }
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
                                text = blockedSubtitle,
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

    // Blocked Contacts dialog
    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Blocked contacts", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (blockedLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Loading…", color = TextSecondary, fontSize = 14.sp)
                        }
                    } else if (blockedError != null) {
                        Text(blockedError ?: "Failed to load", color = Color(0xFFD32F2F), fontSize = 13.sp)
                    } else if (blockedContacts.isEmpty()) {
                        Text("No blocked contacts", color = TextSecondary, fontSize = 14.sp)
                    } else {
                        blockedContacts.forEach { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE8F5E9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Block, contentDescription = null, tint = ScreenGreenHeader, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = contact.identifier,
                                    fontSize = 14.sp,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { unblockContact(contact) }) {
                                    Text("Unblock", color = ScreenGreenHeader, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlockedDialog = false }) {
                    Text("Close", color = ScreenGreenHeader)
                }
            }
        )
    }
}

/** Map UI labels ("Everyone"/"My contacts"/"Nobody") to server tokens. */
private fun labelToServerVisibility(label: String): String = when (label) {
    "Everyone" -> "everyone"
    "My contacts" -> "contacts"
    "Nobody" -> "nobody"
    else -> "everyone"
}

private fun serverVisibilityToLabel(token: String): String = when (token.lowercase()) {
    "everyone" -> "Everyone"
    "contacts" -> "My contacts"
    "nobody" -> "Nobody"
    else -> "Everyone"
}

private fun labelToServerDisappearing(label: String): String = when (label) {
    "24 hours" -> "24H"
    "7 days" -> "7D"
    "90 days" -> "90D"
    "Off" -> "OFF"
    else -> "OFF"
}

private fun serverDisappearingToLabel(token: String): String = when (token.uppercase()) {
    "24H" -> "24 hours"
    "7D" -> "7 days"
    "90D" -> "90 days"
    "OFF" -> "Off"
    else -> "Off"
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
