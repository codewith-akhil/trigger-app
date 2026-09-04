package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.UserRepository

private val SettingsGreenHeader = Color(0xFF008069)
private val SettingsBg = Color(0xFFF7F8FA)
private val SettingsCardBg = Color(0xFFFFFFFF)
private val SettingsTextPrimary = Color(0xFF111B21)
private val SettingsTextSecondary = Color(0xFF667781)
private val SettingsIconTint = Color(0xFF54656F)
private val SettingsDivider = Color(0xFFF0F2F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToChats: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToWallet: () -> Unit = {},
    onNavigateToSecretVault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val userProfile by UserRepository.profile.collectAsState()
    var showInviteSnackbar by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = SettingsBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
                actions = {
                    IconButton(onClick = { /* Search settings */ }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SettingsGreenHeader
                )
            )
        },
        snackbarHost = {
            if (showInviteSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showInviteSnackbar = false }) {
                            Text("OK", color = Color(0xFF00A884))
                        }
                    }
                ) {
                    Text("Trigger App invite link copied to clipboard!")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Profile Card at Top
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .clickable(onClick = onNavigateToProfile)
                        .testTag("settings_profile_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SettingsCardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // User Avatar
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (userProfile.avatarUri != null) {
                                AsyncImage(
                                    model = userProfile.avatarUri,
                                    contentDescription = "Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userProfile.name.ifEmpty { "Your profile" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = SettingsTextPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = userProfile.about.ifEmpty { "Hey there! I am using Trigger App." },
                                fontSize = 13.sp,
                                color = SettingsTextSecondary,
                                maxLines = 1
                            )
                        }

                        // QR Code Button
                        IconButton(onClick = onNavigateToProfile) {
                            Icon(
                                imageVector = Icons.Filled.QrCode,
                                contentDescription = "QR Code",
                                tint = SettingsGreenHeader,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }

            // Settings Options Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SettingsCardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 1. Account
                        SettingsRowItem(
                            icon = Icons.Outlined.Key,
                            title = "Account",
                            subtitle = "Security notifications, change email, delete account",
                            onClick = onNavigateToAccount,
                            testTag = "settings_account_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 2. Privacy
                        SettingsRowItem(
                            icon = Icons.Outlined.Lock,
                            title = "Privacy",
                            subtitle = "Block contacts, disappearing messages, read receipts",
                            onClick = onNavigateToPrivacy,
                            testTag = "settings_privacy_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 3. Wallet & Payouts (NEW)
                        SettingsRowItem(
                            icon = Icons.Outlined.AccountBalanceWallet,
                            title = "Wallet & Payouts",
                            subtitle = "Update bank details, payout methods & withdraw money",
                            onClick = onNavigateToWallet,
                            testTag = "settings_wallet_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 4. Secret Vault (NEW)
                        SettingsRowItem(
                            icon = Icons.Outlined.Shield,
                            title = "Secret Vault",
                            subtitle = "Store private images & videos with 6-digit PIN lock",
                            onClick = onNavigateToSecretVault,
                            testTag = "settings_vault_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 5. Chats
                        SettingsRowItem(
                            icon = Icons.Outlined.Chat,
                            title = "Chats",
                            subtitle = "Theme, wallpapers, chat history & backup",
                            onClick = onNavigateToChats,
                            testTag = "settings_chats_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 6. Notifications
                        SettingsRowItem(
                            icon = Icons.Outlined.Notifications,
                            title = "Notifications",
                            subtitle = "Message, group & call tones",
                            onClick = onNavigateToNotifications,
                            testTag = "settings_notifications_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 7. Storage and data
                        SettingsRowItem(
                            icon = Icons.Outlined.DataUsage,
                            title = "Storage and data",
                            subtitle = "Network usage, auto-download",
                            onClick = onNavigateToStorage,
                            testTag = "settings_storage_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 8. App language
                        SettingsRowItem(
                            icon = Icons.Outlined.Language,
                            title = "App language",
                            subtitle = "English (device's language)",
                            onClick = onNavigateToLanguage,
                            testTag = "settings_language_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 9. Help
                        SettingsRowItem(
                            icon = Icons.Outlined.HelpOutline,
                            title = "Help & Support",
                            subtitle = "Contact Trigger App team: info@triggerapp.com",
                            onClick = onNavigateToHelp,
                            testTag = "settings_help_item"
                        )
                        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 68.dp))

                        // 10. Invite a friend
                        SettingsRowItem(
                            icon = Icons.Outlined.People,
                            title = "Invite a friend",
                            subtitle = null,
                            onClick = { showInviteSnackbar = true },
                            testTag = "settings_invite_item"
                        )
                    }
                }
            }

            // Bottom Brand Footer
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "from",
                        fontSize = 12.sp,
                        color = SettingsTextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "TRIGGER APP",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = SettingsTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    testTag: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = SettingsIconTint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = SettingsTextPrimary
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = SettingsTextSecondary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
