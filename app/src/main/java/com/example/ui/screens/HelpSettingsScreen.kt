package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import com.example.ui.components.TriggerAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
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
private val ErrorRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = AppServiceContainer.supabaseClient

    var showContactDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showFaqDialog by remember { mutableStateOf(false) }
    var showSupportSentSnackbar by remember { mutableStateOf(false) }

    val appVersionName = BuildConfig.VERSION_NAME
    val appVersionCode = BuildConfig.VERSION_CODE

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("help_settings_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = ScreenBg,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Help",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        },
        snackbarHost = {
            if (showSupportSentSnackbar) {
                Snackbar(modifier = Modifier.padding(16.dp)) {
                    Text("Thank you! Your feedback has been dispatched to info@triggerapp.com.")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Official Trigger App Team Contact Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SupportAgent,
                                contentDescription = null,
                                tint = ScreenGreenHeader,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Trigger App Team Support",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "24/7 dedicated support & engineering",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF1F5F9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Email,
                                    contentDescription = null,
                                    tint = ScreenGreenHeader,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Official Contact Email", fontSize = 11.sp, color = TextSecondary)
                                    Text("info@triggerapp.com", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                            }
                            Button(
                                onClick = {
                                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:info@triggerapp.com")
                                        putExtra(Intent.EXTRA_SUBJECT, "Trigger App Support Inquiry")
                                        putExtra(Intent.EXTRA_TEXT, "Hello Trigger App Team,\n\nI need help with:\n")
                                    }
                                    context.startActivity(Intent.createChooser(emailIntent, "Send Email"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ScreenGreenHeader),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Email Us", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // General Help Options
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HelpActionRow(
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        title = stringResource(R.string.help_faq),
                        subtitle = "Read quick guides and troubleshoot issues",
                        onClick = { showFaqDialog = true }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 56.dp))

                    HelpActionRow(
                        icon = Icons.Outlined.SupportAgent,
                        title = stringResource(R.string.help_contact_support),
                        subtitle = "Reach our 24/7 dedicated support team",
                        onClick = { showContactDialog = true }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 56.dp))

                    HelpActionRow(
                        icon = Icons.Outlined.Policy,
                        title = stringResource(R.string.help_terms),
                        subtitle = "Review policies and security measures",
                        onClick = { showTermsDialog = true }
                    )
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 56.dp))

                    HelpActionRow(
                        icon = Icons.Outlined.Info,
                        title = "App info",
                        subtitle = "Trigger App v$appVersionName • Secure & Private",
                        onClick = { showAppInfoDialog = true }
                    )
                }
            }
        }
    }

    // FAQ Dialog
    if (showFaqDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showFaqDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Frequently Asked Questions", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextPrimary)
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column {
                        Text("How do I verify my account?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Enter your email address to receive an instant 6-digit one-time code. Enter the code on the verification screen to complete sign in.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                    }
                    Column {
                        Text("How does voice & video calling work?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Trigger App leverages Agora WebRTC technology to deliver low-latency, crystal-clear audio and video streams between connected users.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                    }
                    Column {
                        Text("Are messages and calls secure?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Yes. All messaging sessions use tokenized real-time sockets and local encrypted storage so your personal communications stay private.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                    }
                    Column {
                        Text("How do I contact support directly?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("You can use the 'Contact Support' option below to send an inquiry ticket or email our engineering team directly at info@triggerapp.com.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFaqDialog = false }) {
                    Text("Close", color = ScreenGreenHeader)
                }
            }
        )
    }

    // Contact Us Dialog
    if (showContactDialog) {
        var messageText by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        TriggerAlertDialog(
            onDismissRequest = {
                if (!isSending) showContactDialog = false
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Contact Support", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextPrimary)
            },
            text = {
                Column {
                    Text(
                        "Describe your problem or share feedback. We will get back to your registered email.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Write your message here...") },
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isSending,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScreenGreenHeader,
                            cursorColor = ScreenGreenHeader
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(err, color = ErrorRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isSending) return@Button
                        isSending = true
                        errorMessage = null
                        coroutineScope.launch {
                            val payload = JSONObject()
                                .put("message", messageText.trim())
                                .put("category", "general")
                            when (val res = client.invokeFunction("create-support-ticket", payload)) {
                                is SupabaseResult.Success -> {
                                    isSending = false
                                    messageText = ""
                                    showContactDialog = false
                                    showSupportSentSnackbar = true
                                }
                                is SupabaseResult.Error -> {
                                    isSending = false
                                    errorMessage = "Failed to send — please try again"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ScreenGreenHeader),
                    enabled = messageText.isNotBlank() && !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sending…", color = Color.White)
                    } else {
                        Text("Send", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isSending) showContactDialog = false },
                    enabled = !isSending
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Terms Dialog
    if (showTermsDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showTermsDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Terms & Privacy Policy", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextPrimary)
            },
            text = {
                Text(
                    "Trigger App provides secure real-time messaging, Agora WebRTC live audio/video calling, and media sharing.\n\n" +
                            "• End-to-end security ensures your private communications stay strictly between you and the person you're communicating with.\n\n" +
                            "• No personal chats or voice logs are shared with third-party advertising networks.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text("Close", color = ScreenGreenHeader)
                }
            }
        )
    }

    // App Info Dialog
    if (showAppInfoDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Trigger App", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ScreenGreenHeader)
            },
            text = {
                Column {
                    Text("Version $appVersionName (Build $appVersionCode)", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Agora WebRTC Calling Engine v4.4.1", color = TextSecondary, fontSize = 13.sp)
                    Text("Realtime Local Message Repository", color = TextSecondary, fontSize = 13.sp)
                    Text("Jetpack Compose & Material Design 3", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("© 2026 Trigger App Open Project.", color = TextSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppInfoDialog = false }) {
                    Text("OK", color = ScreenGreenHeader)
                }
            }
        )
    }
}

@Composable
private fun HelpActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
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
            tint = IconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
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
