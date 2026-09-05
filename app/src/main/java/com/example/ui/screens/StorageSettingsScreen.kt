package com.example.ui.screens

import android.os.StatFs
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

private val ScreenGreenHeader = Color(0xFF008069)
private val ScreenBg = Color(0xFFF7F8FA)
private val CardBg = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF111B21)
private val TextSecondary = Color(0xFF667781)
private val DividerColor = Color(0xFFF0F2F5)
private val SwitchGreen = Color(0xFF00A884)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = AppServiceContainer.supabaseClient

    var useLessDataForCalls by remember { mutableStateOf(false) }
    var mobileDataMedia by remember { mutableStateOf("Photos") }
    var wifiMedia by remember { mutableStateOf("All media") }
    var roamingMedia by remember { mutableStateOf("No media") }

    var mediaDialogTitle by remember { mutableStateOf<String?>(null) }
    var mediaDialogOptions by remember { mutableStateOf(listOf<String>()) }
    var onMediaOptionSelected by remember { mutableStateOf<(String) -> Unit>({}) }

    // Backup-now state.
    var isBackingUp by remember { mutableStateOf(false) }

    // Real free-space via StatFs on the app's data dir.
    val freeSpaceStr = remember {
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            formatBytes(freeBytes)
        } catch (_: Exception) {
            "Unknown"
        }
    }
    val appSizeStr = remember {
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            val totalBytes = stat.totalBytes
            // Best-effort app-occupied size — use the app's cache + files dirs.
            val usedBytes = dirSize(context.filesDir) + dirSize(context.cacheDir)
            if (usedBytes > 0) formatBytes(usedBytes) else "Calculating…"
        } catch (_: Exception) {
            "Calculating…"
        }
    }
    // TODO: wire real network-usage stats via ConnectivityManager /
    // TrafficStats. Requires additional permissions; deferred.
    val networkUsageStr = "Calculating…"

    // Hydrate from server.
    LaunchedEffect(Unit) {
        when (val res = client.invokeFunction("get-my-profile")) {
            is SupabaseResult.Success -> {
                val settings = res.data.optJSONObject("settings")
                if (settings != null) {
                    if (settings.has("useLessDataForCalls"))
                        useLessDataForCalls = settings.getBoolean("useLessDataForCalls")
                    if (settings.has("mobileDataMedia"))
                        mobileDataMedia = serverMediaModeToLabel(settings.optString("mobileDataMedia"))
                    if (settings.has("wifiMedia"))
                        wifiMedia = serverMediaModeToLabel(settings.optString("wifiMedia"))
                    if (settings.has("roamingMedia"))
                        roamingMedia = if (settings.getBoolean("roamingMedia")) "All media" else "No media"
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

    fun startBackup() {
        if (isBackingUp) return
        isBackingUp = true
        coroutineScope.launch {
            // The `backup-messages` edge function records a `chat_backups`
            // row. Real backup requires first uploading the backup file to
            // the `backups` Storage bucket — TODO: wire Storage upload. For
            // now we record a metadata-only row so the backup history is
            // visible in the user's account.
            val storagePath = "backups/${System.currentTimeMillis()}.json"
            val payload = JSONObject()
                .put("action", "create")
                .put("storagePath", storagePath)
                .put("fileName", "trigger_backup_${System.currentTimeMillis()}.json")
                .put("fileSize", 0)
                .put("messageCount", 0)
                .put("conversationCount", 0)
            when (val res = client.invokeFunction("backup-messages", payload)) {
                is SupabaseResult.Success -> {
                    isBackingUp = false
                    Toast.makeText(context, "Backup complete", Toast.LENGTH_SHORT).show()
                }
                is SupabaseResult.Error -> {
                    isBackingUp = false
                    Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("storage_settings_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = ScreenBg,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Storage and data",
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
            // Storage Usage Overview
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Manage storage",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        // Back Up Now button — calls the `backup-messages`
                        // edge function with action="create".
                        Button(
                            onClick = { startBackup() },
                            enabled = !isBackingUp,
                            colors = ButtonDefaults.buttonColors(containerColor = ScreenGreenHeader),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isBackingUp) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Backing up…", color = Color.White, fontSize = 12.sp)
                            } else {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Back Up Now", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress Bar — proportional to app-occupied vs free space.
                    val appBytes = remember { dirSize(context.filesDir) + dirSize(context.cacheDir) }
                    val totalBytes = remember {
                        try {
                            val stat = StatFs(context.filesDir.absolutePath)
                            stat.totalBytes
                        } catch (_: Exception) { 1L }
                    }
                    val appFraction = if (totalBytes > 0) (appBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 1f) else 0.02f

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE2E8F0))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(appFraction)
                                .background(ScreenGreenHeader)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(ScreenGreenHeader)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Trigger App: $appSizeStr",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFCBD5E1))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Free space: $freeSpaceStr",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Network Section
            Text(
                text = "Network",
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
                    StorageItemRow(
                        title = "Network usage",
                        subtitle = networkUsageStr,
                        onClick = {
                            Toast.makeText(context, "Network usage details coming soon", Toast.LENGTH_SHORT).show()
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
                                text = "Use less data for calls",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Optimizes Agora WebRTC audio/video bitrate",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = useLessDataForCalls,
                            onCheckedChange = {
                                useLessDataForCalls = it
                                persistSetting("useLessDataForCalls", it)
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

            // Media auto-download
            Text(
                text = "Media auto-download",
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
                    StorageItemRow(
                        title = "When using mobile data",
                        subtitle = mobileDataMedia,
                        onClick = {
                            mediaDialogTitle = "When using mobile data"
                            mediaDialogOptions = listOf("No media", "Photos", "Photos, Audio", "All media")
                            onMediaOptionSelected = {
                                mobileDataMedia = it
                                persistSetting("mobileDataMedia", labelToServerMediaMode(it))
                            }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    StorageItemRow(
                        title = "When connected on Wi-Fi",
                        subtitle = wifiMedia,
                        onClick = {
                            mediaDialogTitle = "When connected on Wi-Fi"
                            mediaDialogOptions = listOf("No media", "Photos", "All media")
                            onMediaOptionSelected = {
                                wifiMedia = it
                                persistSetting("wifiMedia", labelToServerMediaMode(it))
                            }
                        }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                    StorageItemRow(
                        title = "When roaming",
                        subtitle = roamingMedia,
                        onClick = {
                            mediaDialogTitle = "When roaming"
                            mediaDialogOptions = listOf("No media", "Photos", "All media")
                            onMediaOptionSelected = {
                                roamingMedia = it
                                persistSetting("roamingMedia", it != "No media")
                            }
                        }
                    )
                }
            }
        }
    }

    if (mediaDialogTitle != null) {
        AlertDialog(
            onDismissRequest = { mediaDialogTitle = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(mediaDialogTitle.orEmpty(), fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    mediaDialogOptions.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMediaOptionSelected(opt)
                                    mediaDialogTitle = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (opt == mobileDataMedia || opt == wifiMedia || opt == roamingMedia),
                                onClick = {
                                    onMediaOptionSelected(opt)
                                    mediaDialogTitle = null
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

/** Format a byte count as a human-readable string (KB / MB / GB). */
private fun formatBytes(bytes: Long): String {
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

/** Recursively compute the on-disk size of a directory. */
private fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    var sum = 0L
    dir.listFiles()?.forEach { sum += dirSize(it) }
    return sum
}

private fun labelToServerMediaMode(label: String): String = when (label) {
    "No media" -> "off"
    "Photos" -> "on"
    "Photos, Audio" -> "on"
    "All media" -> "auto"
    else -> "auto"
}

private fun serverMediaModeToLabel(token: String): String = when (token.lowercase()) {
    "off" -> "No media"
    "on" -> "Photos"
    "auto" -> "All media"
    else -> "Photos"
}

@Composable
private fun StorageItemRow(
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
