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
import com.example.model.UserRepository

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
    onDeleteAccountConfirmed: () -> Unit = onBack,
    modifier: Modifier = Modifier
) {
    val userProfile by UserRepository.profile.collectAsState()
    var securityNotificationsEnabled by remember { mutableStateOf(true) }
    var twoStepEnabled by remember { mutableStateOf(false) }

    var showChangeEmailDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showTwoStepDialog by remember { mutableStateOf(false) }
    var showRequestReportSnackbar by remember { mutableStateOf(false) }

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
                            onCheckedChange = { securityNotificationsEnabled = it },
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

                    // Change email
                    AccountActionRow(
                        icon = Icons.Outlined.Email,
                        title = "Change email address",
                        subtitle = userProfile.email.ifEmpty { "Add an email address" },
                        onClick = { showChangeEmailDialog = true }
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

                    // Delete my account
                    AccountActionRow(
                        icon = Icons.Outlined.DeleteForever,
                        title = "Delete my account",
                        subtitle = "Erase your account, message history and groups",
                        isDestructive = true,
                        onClick = { showDeleteAccountDialog = true }
                    )
                }
            }
        }
    }

    // Two-Step Verification Dialog
    if (showTwoStepDialog) {
        AlertDialog(
            onDismissRequest = { showTwoStepDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = if (twoStepEnabled) "Disable Two-Step PIN?" else "Enable Two-Step Verification",
                    fontWeight = FontWeight.Bold,
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
                        twoStepEnabled = !twoStepEnabled
                        showTwoStepDialog = false
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

    // Change Email Dialog
    if (showChangeEmailDialog) {
        var newEmail by remember { mutableStateOf(userProfile.email) }
        AlertDialog(
            onDismissRequest = { showChangeEmailDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Change Email Address", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    Text(
                        "Enter the new email address for your Trigger App account.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScreenGreenHeader,
                            cursorColor = ScreenGreenHeader
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newEmail.isNotBlank()) UserRepository.updateEmail(newEmail.trim())
                        showChangeEmailDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ScreenGreenHeader)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeEmailDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Delete Account Dialog
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Delete Account?", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
            },
            text = {
                Text(
                    "Deleting your account will permanently delete your account info, profile photo, and remove you from all Trigger groups. This action cannot be undone.",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        onDeleteAccountConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Delete Account", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
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
