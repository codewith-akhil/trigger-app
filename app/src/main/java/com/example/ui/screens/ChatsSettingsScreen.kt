package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
fun ChatsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    // Persisted chat settings — initialised from the backend on launch, mirrored
    // back via `update-user-settings` on every change. Defaults are conservative
    // so the UI is usable before the network round-trip completes.
    var enterIsSend by remember { mutableStateOf(false) }
    var mediaVisibility by remember { mutableStateOf(true) }
    var fontSizeChoice by remember { mutableStateOf("Medium") }
    var isBackingUp by remember { mutableStateOf(false) }
    var isClearingChats by remember { mutableStateOf(false) }
    var backupStatusText by remember { mutableStateOf("Never") }
    var backupErrorText by remember { mutableStateOf<String?>(null) }

    var showClearChatsDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showWallpaperSnackbar by remember { mutableStateOf(false) }
    var clearChatsSuccessSnackbar by remember { mutableStateOf(false) }
    var clearChatsErrorSnackbar by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // --- Persist settings back to the server whenever a toggle changes ---------
    fun persistSetting(payload: JSONObject) {
        coroutineScope.launch {
            try {
                AppServiceContainer.supabaseClient.invokeFunction("update-user-settings", payload)
            } catch (e: Exception) {
                // Fire-and-forget — best-effort persistence; UI state already updated.
            }
        }
    }

    // --- Hydrate toggles + last-backup time on screen entry --------------------
    LaunchedEffect(Unit) {
        // 1) Current user settings via get-my-profile. The function returns
        //    the profile row, but `user_settings` fields may be embedded as a
        //    `settings` object or as top-level fields. We check both shapes
        //    defensively so the screen still works if the backend evolves.
        val profileResult = AppServiceContainer.supabaseClient.invokeFunction("get-my-profile", JSONObject())
        if (profileResult is SupabaseResult.Success) {
            val profile = profileResult.data.optJSONObject("profile")
            val settings = profile?.optJSONObject("settings") ?: profile
            if (settings != null) {
                if (settings.has("enterIsSend")) {
                    enterIsSend = settings.optBoolean("enterIsSend", enterIsSend)
                } else if (settings.has("enter_is_send")) {
                    enterIsSend = settings.optBoolean("enter_is_send", enterIsSend)
                }
                val mediaVis = settings.optString("mediaVisibility", settings.optString("media_visibility", ""))
                if (mediaVis.isNotEmpty()) {
                    mediaVisibility = (mediaVis == "on")
                }
                val fontRaw = settings.optString("fontSize", settings.optString("font_size", ""))
                if (fontRaw.isNotEmpty()) {
                    fontSizeChoice = when (fontRaw.lowercase(Locale.ROOT)) {
                        "small" -> "Small"
                        "large" -> "Large"
                        else -> "Medium"
                    }
                }
            }
        }

        // 2) Latest backup metadata (if any) via backup-messages action=list.
        val listPayload = JSONObject().put("action", "list").put("limit", 1)
        val backupResult = AppServiceContainer.supabaseClient.invokeFunction("backup-messages", listPayload)
        if (backupResult is SupabaseResult.Success) {
            val latest = backupResult.data.optJSONObject("latest")
            if (latest != null) {
                val createdAt = latest.optString("created_at", "")
                val sizeBytes = latest.optLong("file_size", 0L)
                val sizeText = if (sizeBytes > 0) formatBackupSize(sizeBytes) else ""
                backupStatusText = if (createdAt.isNotEmpty()) {
                    "${formatBackupDate(createdAt)}${if (sizeText.isNotEmpty()) " ($sizeText)" else ""}"
                } else {
                    "Never"
                }
            } else {
                backupStatusText = "Never"
            }
        }
    }

    // --- Auto-dismiss snackbar messages after a short delay --------------------
    LaunchedEffect(showWallpaperSnackbar) {
        if (showWallpaperSnackbar) {
            kotlinx.coroutines.delay(2500)
            showWallpaperSnackbar = false
        }
    }
    LaunchedEffect(clearChatsSuccessSnackbar) {
        if (clearChatsSuccessSnackbar) {
            kotlinx.coroutines.delay(2500)
            clearChatsSuccessSnackbar = false
        }
    }
    LaunchedEffect(clearChatsErrorSnackbar) {
        if (clearChatsErrorSnackbar) {
            kotlinx.coroutines.delay(2500)
            clearChatsErrorSnackbar = false
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("chats_settings_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = ScreenBg,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Chats",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        },
        snackbarHost = {
            Box {
                if (showWallpaperSnackbar) {
                    Snackbar(modifier = Modifier.padding(16.dp)) {
                        Text("Wallpaper customized for all chats!")
                    }
                }
                if (clearChatsSuccessSnackbar) {
                    Snackbar(modifier = Modifier.padding(16.dp)) {
                        Text("All chats cleared")
                    }
                }
                if (clearChatsErrorSnackbar) {
                    Snackbar(modifier = Modifier.padding(16.dp)) {
                        Text("Failed to clear all chats — try again")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Display Section
            Text(
                text = "Display",
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
                    ChatSettingItemRow(
                        title = "Theme",
                        subtitle = "Light (System default)",
                        onClick = { /* System light */ }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))
                    ChatSettingItemRow(
                        title = "Wallpaper",
                        subtitle = "Classic light doodle pattern",
                        onClick = { showWallpaperSnackbar = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Chat settings
            Text(
                text = "Chat settings",
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
                    // Enter is send
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enter is send",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Enter key will send your message",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = enterIsSend,
                            onCheckedChange = { newValue ->
                                enterIsSend = newValue
                                persistSetting(JSONObject().put("enterIsSend", newValue))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    // Media visibility
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Media visibility",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Show newly downloaded media in your device's gallery",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = mediaVisibility,
                            onCheckedChange = { newValue ->
                                mediaVisibility = newValue
                                persistSetting(
                                    JSONObject().put("mediaVisibility", if (newValue) "on" else "off")
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    ChatSettingItemRow(
                        title = "Font size",
                        subtitle = fontSizeChoice,
                        onClick = { showFontSizeDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Chat backup
            Text(
                text = "Backup & History",
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Last Backup",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Local backup: $backupStatusText",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    val backupError = backupErrorText
                    if (backupError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = backupError,
                            fontSize = 12.sp,
                            color = Color(0xFFD32F2F)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            if (!isBackingUp) {
                                isBackingUp = true
                                backupErrorText = null
                                coroutineScope.launch {
                                    // Real call to backup-messages edge function.
                                    // We supply a unique storagePath so the create
                                    // action succeeds on the server side.
                                    val storagePath = "backups/manual_${System.currentTimeMillis()}.json"
                                    val payload = JSONObject()
                                        .put("action", "create")
                                        .put("storagePath", storagePath)
                                        .put("fileName", "manual_backup.json")
                                    val result = AppServiceContainer.supabaseClient
                                        .invokeFunction("backup-messages", payload)
                                    isBackingUp = false
                                    if (result is SupabaseResult.Success) {
                                        val backup = result.data.optJSONObject("backup")
                                        val sizeBytes = backup?.optLong("file_size", 0L) ?: 0L
                                        val sizeText = if (sizeBytes > 0) " (${formatBackupSize(sizeBytes)})" else ""
                                        backupStatusText = "Just now$sizeText"
                                        backupErrorText = null
                                    } else {
                                        backupErrorText = "Backup failed — try again"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ScreenGreenHeader),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isBackingUp
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Backing up messages...", color = Color.White)
                        } else {
                            Text("Back Up Now", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isClearingChats) {
                                showClearChatsDialog = true
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Clear all chats",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isClearingChats) TextSecondary else Color(0xFFD32F2F),
                            modifier = Modifier.weight(1f)
                        )
                        if (isClearingChats) {
                            CircularProgressIndicator(
                                color = Color(0xFFD32F2F),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Font size picker
    if (showFontSizeDialog) {
        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Font size", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    listOf("Small", "Medium", "Large").forEach { size ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    fontSizeChoice = size
                                    showFontSizeDialog = false
                                    val apiValue = when (size) {
                                        "Small" -> "small"
                                        "Large" -> "large"
                                        else -> "medium"
                                    }
                                    persistSetting(JSONObject().put("fontSize", apiValue))
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (fontSizeChoice == size),
                                onClick = {
                                    fontSizeChoice = size
                                    showFontSizeDialog = false
                                    val apiValue = when (size) {
                                        "Small" -> "small"
                                        "Large" -> "large"
                                        else -> "medium"
                                    }
                                    persistSetting(JSONObject().put("fontSize", apiValue))
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = SwitchGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = size, fontSize = 15.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Clear chats confirmation
    if (showClearChatsDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatsDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Clear all chats?", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
            },
            text = {
                Text(
                    "Messages in all chats will be deleted from this device.",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearChatsDialog = false
                        if (!isClearingChats) {
                            isClearingChats = true
                            coroutineScope.launch {
                                try {
                                    // Iterate every conversation and clear its messages.
                                    val conversations = AppServiceContainer.chatRepository
                                        .getAllConversations()
                                        .first()
                                    conversations.forEach { conv ->
                                        AppServiceContainer.chatRepository.clearChat(conv.id)
                                    }
                                    clearChatsSuccessSnackbar = true
                                } catch (e: Exception) {
                                    clearChatsErrorSnackbar = true
                                } finally {
                                    isClearingChats = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    enabled = !isClearingChats
                ) {
                    Text("Clear chats", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatsDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun ChatSettingItemRow(
    title: String,
    subtitle: String,
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
                text = subtitle,
                fontSize = 13.sp,
                color = TextSecondary
            )
        }
    }
}

/** Formats a byte count as a compact human-readable string (KB / MB / GB). */
private fun formatBackupSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "${"%.1f".format(gb)} GB"
        mb >= 1.0 -> "${"%.0f".format(mb)} MB"
        kb >= 1.0 -> "${"%.0f".format(kb)} KB"
        else -> "$bytes B"
    }
}

/** Parses an ISO-8601 timestamp (e.g. `2026-01-02T03:04:05Z`) into "Today, h:mm a"
 *  or "MMM d, h:mm a" for older entries. Returns the raw input on parse failure. */
private fun formatBackupDate(isoTimestamp: String): String {
    return try {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val fallbackIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val parsed = isoFormat.parse(isoTimestamp) ?: fallbackIso.parse(isoTimestamp) ?: return isoTimestamp
        val today = Date()
        val cal1 = java.util.Calendar.getInstance().apply { time = parsed }
        val cal2 = java.util.Calendar.getInstance().apply { time = today }
        val sameDay = cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
                cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        if (sameDay) {
            "Today, ${timeFmt.format(parsed)}"
        } else {
            val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            dateFmt.format(parsed)
        }
    } catch (e: Exception) {
        isoTimestamp
    }
}
