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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
private val SwitchGreen = Color(0xFF00A884)

/** Options for the social visibility pickers (Activity / Profile photo / About). */
private val VisibilityOptions = listOf("Everyone", "Followers only", "Following", "Nobody")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onNavigateBlockedUsers: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val client = AppServiceContainer.supabaseClient

    var lastSeenChoice by remember { mutableStateOf("Everyone") }
    var profilePhotoChoice by remember { mutableStateOf("Everyone") }
    var aboutChoice by remember { mutableStateOf("Everyone") }

    var showPickerTitle by remember { mutableStateOf<String?>(null) }
    var pickerOptions by remember { mutableStateOf(listOf<String>()) }
    var pickerCurrentValue by remember { mutableStateOf("") }
    var onSelectedPickerOption by remember { mutableStateOf<(String) -> Unit>({}) }

    // Hydrate from server (best-effort).
    LaunchedEffect(Unit) {
        when (val res = client.invokeFunction("get-my-profile")) {
            is SupabaseResult.Success -> {
                val settings = res.data.optJSONObject("settings")
                if (settings != null) {
                    if (settings.has("lastSeen"))
                        lastSeenChoice = serverVisibilityToLabel(settings.optString("lastSeen"))
                    if (settings.has("profilePhotoVisibility"))
                        profilePhotoChoice = serverVisibilityToLabel(settings.optString("profilePhotoVisibility"))
                    if (settings.has("aboutVisibility"))
                        aboutChoice = serverVisibilityToLabel(settings.optString("aboutVisibility"))
                }
            }
            is SupabaseResult.Error -> Unit
        }
    }

    fun persistSetting(key: String, value: String) {
        val payload = JSONObject().put(key, value)
        coroutineScope.launch {
            client.invokeFunction("update-user-settings", payload)
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
                        title = "Activity",
                        value = lastSeenChoice,
                        onClick = {
                            showPickerTitle = "Activity"
                            pickerOptions = VisibilityOptions
                            pickerCurrentValue = lastSeenChoice
                            onSelectedPickerOption = {
                                lastSeenChoice = it
                                persistSetting("lastSeen", labelToServerVisibility(it))
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F5), modifier = Modifier.padding(start = 16.dp))

                    PrivacyOptionRow(
                        title = "Profile photo",
                        value = profilePhotoChoice,
                        onClick = {
                            showPickerTitle = "Profile photo"
                            pickerOptions = VisibilityOptions
                            pickerCurrentValue = profilePhotoChoice
                            onSelectedPickerOption = {
                                profilePhotoChoice = it
                                persistSetting("profilePhotoVisibility", labelToServerVisibility(it))
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F5), modifier = Modifier.padding(start = 16.dp))

                    PrivacyOptionRow(
                        title = "About",
                        value = aboutChoice,
                        onClick = {
                            showPickerTitle = "About"
                            pickerOptions = VisibilityOptions
                            pickerCurrentValue = aboutChoice
                            onSelectedPickerOption = {
                                aboutChoice = it
                                persistSetting("aboutVisibility", labelToServerVisibility(it))
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Security",
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
                    // Blocked users — dedicated screen (BlockedUsersScreen).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateBlockedUsers() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blocked Users",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Manage the users you have blocked",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Selection Dialog for options - tightly wrapped, no dead space
    if (showPickerTitle != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showPickerTitle = null }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        showPickerTitle.orEmpty(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        pickerOptions.forEach { opt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onSelectedPickerOption(opt)
                                        showPickerTitle = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = opt == pickerCurrentValue,
                                    onClick = {
                                        onSelectedPickerOption(opt)
                                        showPickerTitle = null
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = SwitchGreen)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(text = opt, fontSize = 15.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Map UI labels ("Everyone"/"Followers only"/"Following"/"Nobody") to server
 *  tokens. The UI writes ONLY the new social tokens; the server keeps
 *  accepting legacy 'contacts' for old clients. */
private fun labelToServerVisibility(label: String): String = when (label) {
    "Everyone" -> "everyone"
    "Followers only" -> "followers"
    "Following" -> "following"
    "Nobody" -> "nobody"
    else -> "everyone"
}

private fun serverVisibilityToLabel(token: String): String = when (token.lowercase()) {
    "everyone" -> "Everyone"
    // Legacy 'contacts' rows hydrate as "Followers only" (same effective gate).
    "contacts", "followers" -> "Followers only"
    "following" -> "Following"
    "nobody" -> "Nobody"
    else -> "Everyone"
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
