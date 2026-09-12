package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import com.example.ui.components.TriggerAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onNavigateToDeleteAccount: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Initialised from server (get-my-profile -> settings) on first launch; fall
    // back to false if the server response doesn't include a settings object.
    var securityNotificationsEnabled by remember { mutableStateOf(false) }
    var twoStepEnabled by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    var showTwoStepDialog by remember { mutableStateOf(false) }
    var showRequestReportSnackbar by remember { mutableStateOf(false) }

    // Hydrate toggles from the server once. get-my-profile returns `settings`
    // at the TOP LEVEL of the response (next to `profile`) — the old code read
    // profile.settings which never exists, so the toggles always rendered the
    // defaults. twoStepEnabled also falls back to the profiles column.
    LaunchedEffect(Unit) {
        try {
            val result = AppServiceContainer.supabaseClient.invokeFunction("get-my-profile")
            if (result is SupabaseResult.Success) {
                val settings = result.data.optJSONObject("settings")
                val profile = result.data.optJSONObject("profile")
                if (settings != null) {
                    securityNotificationsEnabled = settings.optBoolean("securityNotifications", false)
                    twoStepEnabled = settings.optBoolean("twoStepEnabled", twoStepEnabled)
                }
                if (profile != null && profile.has("two_step_enabled")) {
                    twoStepEnabled = profile.optBoolean("two_step_enabled", twoStepEnabled)
                }
            }
        } catch (_: Exception) {
            // Silent — defaults remain false.
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("account_settings_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = ScreenBg,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Account",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        },
        snackbarHost = {
            if (showRequestReportSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showRequestReportSnackbar = false }) {
                            Text("OK", color = SwitchGreen)
                        }
                    }
                ) {
                    Text("Your account info report will be ready in 3 days.")
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
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Security notifications
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = IconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Security notifications",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Get notified when a device security code changes",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = securityNotificationsEnabled,
                            onCheckedChange = { newValue ->
                                securityNotificationsEnabled = newValue
                                // Fire-and-forget sync to update-user-settings edge fn.
                                scope.launch {
                                    try {
                                        AppServiceContainer.supabaseClient.invokeFunction(
                                            "update-user-settings",
                                            JSONObject().put("securityNotifications", newValue)
                                        )
                                    } catch (_: Exception) { /* silent */ }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SwitchGreen
                            )
                        )
                    }

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 56.dp))

                    // Two-step verification
                    AccountActionRow(
                        icon = Icons.Outlined.Pin,
                        title = "Two-step verification",
                        subtitle = if (twoStepEnabled) "Enabled with 6-digit PIN" else "Disabled",
                        onClick = { showTwoStepDialog = true }
                    )

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 56.dp))

                    // Request account info
                    AccountActionRow(
                        icon = Icons.Outlined.Description,
                        title = "Request account info",
                        subtitle = "Download a report of your Trigger App account information",
                        onClick = { showRequestReportSnackbar = true }
                    )

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 56.dp))

                    // Delete my account — navigates directly to the OTP-confirmed
                    // delete flow (DeleteAccountScreen). No local confirm dialog.
                    AccountActionRow(
                        icon = Icons.Outlined.DeleteForever,
                        title = "Delete my account",
                        subtitle = "Erase your account, message history and groups",
                        isDestructive = true,
                        onClick = onNavigateToDeleteAccount
                    )
                }
            }
        }
    }

    // Two-Step Verification Dialog
    if (showTwoStepDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showTwoStepDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = if (twoStepEnabled) "Disable Two-Step PIN?" else "Enable Two-Step Verification",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = if (twoStepEnabled)
                        "Disabling two-step verification will remove the extra security layer from your Trigger App."
                    else
                        "Two-step verification adds an extra layer of protection to your account with a secret PIN.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newValue = !twoStepEnabled
                        twoStepEnabled = newValue
                        showTwoStepDialog = false
                        // Fire-and-forget sync to update-user-settings edge fn.
                        scope.launch {
                            try {
                                AppServiceContainer.supabaseClient.invokeFunction(
                                    "update-user-settings",
                                    JSONObject().put("twoStepEnabled", newValue)
                                )
                            } catch (_: Exception) { /* silent */ }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (twoStepEnabled) Color(0xFFD32F2F) else ScreenGreenHeader
                    )
                ) {
                    Text(if (twoStepEnabled) "Disable" else "Enable", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTwoStepDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun AccountActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) Color(0xFFD32F2F) else IconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDestructive) Color(0xFFD32F2F) else TextPrimary
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
