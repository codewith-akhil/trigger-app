package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.UserRepository

private val TriggerLightBg = Color(0xFFF7F8FA)
private val TriggerCardBg = Color(0xFFFFFFFF)
private val TriggerGreenAccent = Color(0xFF008069)
private val TriggerFabGreen = Color(0xFF00A884)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)
private val TriggerDivider = Color(0xFFF0F2F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val profile by UserRepository.profile.collectAsState()

    // Dialog edit states
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditAboutDialog by remember { mutableStateOf(false) }
    var showEditUsernameDialog by remember { mutableStateOf(false) }
    var showEditEmailDialog by remember { mutableStateOf(false) }
    var showEditLinksDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // Media picker for custom avatar photo
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            UserRepository.updateAvatarUri(uri.toString())
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("profile_screen"),
        containerColor = TriggerLightBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Profile",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TriggerGreenAccent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large Centered Circular Profile Avatar with Green Camera Badge
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                // Main Avatar Circle
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E8F0)),
                    contentAlignment = Alignment.Center
                ) {
                    if (profile.avatarUri != null) {
                        AsyncImage(
                            model = profile.avatarUri,
                            contentDescription = "Profile Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Default Silhouette
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Profile Photo",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(90.dp)
                        )
                    }
                }

                // Green Camera Icon Badge at bottom right
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(TriggerFabGreen)
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Change Profile Photo",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Card container for profile details
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TriggerCardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Section 1: Name
                    ProfileDetailItem(
                        icon = Icons.Outlined.Person,
                        label = "Name",
                        value = profile.name.ifEmpty { "Set Name" },
                        isValueGreen = profile.name.isEmpty(),
                        onClick = { showEditNameDialog = true },
                        testTag = "profile_name_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // Section 2: About
                    ProfileDetailItem(
                        icon = Icons.Outlined.Info,
                        label = "About",
                        value = profile.about.ifEmpty { "Set About" },
                        isValueGreen = profile.about.isEmpty() || profile.about == "Set About",
                        onClick = { showEditAboutDialog = true },
                        testTag = "profile_about_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // Section 3: Username
                    ProfileDetailItem(
                        icon = Icons.Outlined.AlternateEmail,
                        label = "Username",
                        value = if (profile.username.isNotEmpty()) "@${profile.username}" else "Reserve username",
                        isValueGreen = profile.username.isEmpty(),
                        onClick = { showEditUsernameDialog = true },
                        testTag = "profile_username_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // Section 4: Email
                    ProfileDetailItem(
                        icon = Icons.Outlined.Email,
                        label = "Email",
                        value = profile.email.ifEmpty { "Add email address" },
                        isValueGreen = profile.email.isEmpty(),
                        onClick = { showEditEmailDialog = true },
                        testTag = "profile_email_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // Section 5: Links
                    ProfileDetailItem(
                        icon = Icons.Outlined.Link,
                        label = "Links",
                        value = profile.links.ifEmpty { "Add links" },
                        isValueGreen = profile.links.isEmpty() || profile.links == "Add links",
                        onClick = { showEditLinksDialog = true },
                        testTag = "profile_links_item"
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Logout Button
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("logout_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFD32F2F)
                ),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFFFFCDD2))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Log out",
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Log out",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Logout Confirmation Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Log out of Trigger App?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = TriggerTextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out? You will need to verify your account to log back in.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = TriggerTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Log out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = TriggerTextSecondary, fontWeight = FontWeight.Medium)
                }
            }
        )
    }

    // Dialogs for editing profile attributes
    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(profile.name) }
        ProfileEditDialog(
            title = "Enter your name",
            initialValue = tempName,
            onDismiss = { showEditNameDialog = false },
            onConfirm = {
                if (it.isNotBlank()) UserRepository.updateName(it.trim())
                showEditNameDialog = false
            }
        )
    }

    if (showEditAboutDialog) {
        ProfileEditDialog(
            title = "About",
            initialValue = if (profile.about == "Set About") "" else profile.about,
            onDismiss = { showEditAboutDialog = false },
            onConfirm = {
                UserRepository.updateAbout(if (it.isBlank()) "Set About" else it.trim())
                showEditAboutDialog = false
            }
        )
    }

    if (showEditUsernameDialog) {
        ProfileEditDialog(
            title = "Create username",
            initialValue = profile.username,
            prefix = "@",
            onDismiss = { showEditUsernameDialog = false },
            onConfirm = {
                val clean = it.replace("@", "").trim()
                UserRepository.updateUsername(clean)
                showEditUsernameDialog = false
            }
        )
    }

    if (showEditEmailDialog) {
        ProfileEditDialog(
            title = "Edit email address",
            initialValue = profile.email,
            onDismiss = { showEditEmailDialog = false },
            onConfirm = {
                if (it.isNotBlank() && it.contains("@")) UserRepository.updateEmail(it.trim())
                showEditEmailDialog = false
            }
        )
    }

    if (showEditLinksDialog) {
        ProfileEditDialog(
            title = "Add links",
            initialValue = if (profile.links == "Add links") "" else profile.links,
            onDismiss = { showEditLinksDialog = false },
            onConfirm = {
                UserRepository.updateLinks(if (it.isBlank()) "Add links" else it.trim())
                showEditLinksDialog = false
            }
        )
    }
}

@Composable
fun ProfileDetailItem(
    icon: ImageVector,
    label: String,
    value: String,
    isValueGreen: Boolean,
    onClick: () -> Unit,
    testTag: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TriggerGreenAccent,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TriggerTextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = if (isValueGreen) TriggerFabGreen else TriggerTextPrimary
            )
        }

        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Edit $label",
            tint = TriggerTextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun ProfileEditDialog(
    title: String,
    initialValue: String,
    prefix: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = title, color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    prefix = if (prefix != null) {
                        { Text(prefix, color = TriggerFabGreen, fontWeight = FontWeight.Bold) }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TriggerTextPrimary,
                        unfocusedTextColor = TriggerTextPrimary,
                        focusedBorderColor = TriggerGreenAccent,
                        unfocusedBorderColor = Color(0xFFCFD8DC),
                        cursorColor = TriggerGreenAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(textValue) },
                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TriggerTextSecondary)
            }
        }
    )
}

