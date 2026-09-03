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

private val WhatsAppDarkBg = Color(0xFF0B141B)
private val WhatsAppDarkCard = Color(0xFF111B21)
private val WhatsAppGreenAccent = Color(0xFF25D366)
private val WhatsAppTextSecondary = Color(0xFF8696A0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile by UserRepository.profile.collectAsState()

    // Dialog edit states
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditAboutDialog by remember { mutableStateOf(false) }
    var showEditUsernameDialog by remember { mutableStateOf(false) }
    var showEditEmailDialog by remember { mutableStateOf(false) }
    var showEditLinksDialog by remember { mutableStateOf(false) }

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
        containerColor = WhatsAppDarkBg,
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
                    containerColor = WhatsAppDarkBg
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large Centered Circular Profile Avatar with Green Camera Badge
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                // Main Avatar Circle
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF687782)),
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
                        // WhatsApp Default Grey Silhouette
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Profile Photo",
                            tint = Color(0xFFCFD8DC),
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
                        .background(WhatsAppGreenAccent)
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

            Spacer(modifier = Modifier.height(24.dp))

            // Section 1: Name
            ProfileDetailItem(
                icon = Icons.Outlined.Person,
                label = "Name",
                value = profile.name.ifEmpty { "Akhil" },
                isValueGreen = false,
                onClick = { showEditNameDialog = true },
                testTag = "profile_name_item"
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Section 2: About
            ProfileDetailItem(
                icon = Icons.Outlined.Info,
                label = "About",
                value = profile.about.ifEmpty { "Set About" },
                isValueGreen = profile.about.isEmpty() || profile.about == "Set About",
                onClick = { showEditAboutDialog = true },
                testTag = "profile_about_item"
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3: Username (User requested: "ADD A SECTION TO CREATE USERNAME ADDITIONALLY")
            ProfileDetailItem(
                icon = Icons.Outlined.AlternateEmail,
                label = "Username",
                value = if (profile.username.isNotEmpty()) "@${profile.username}" else "Reserve username",
                isValueGreen = profile.username.isEmpty(),
                onClick = { showEditUsernameDialog = true },
                testTag = "profile_username_item"
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Section 4: Email (User requested: "CHANGE MOB NO TO EMAIL")
            ProfileDetailItem(
                icon = Icons.Outlined.Email,
                label = "Email",
                value = profile.email.ifEmpty { "akhil@gmail.com" },
                isValueGreen = false,
                onClick = { showEditEmailDialog = true },
                testTag = "profile_email_item"
            )

            Spacer(modifier = Modifier.height(20.dp))

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
            .padding(vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = WhatsAppTextSecondary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(24.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = WhatsAppTextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = if (isValueGreen) WhatsAppGreenAccent else Color.White
            )
        }

        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Edit $label",
            tint = WhatsAppTextSecondary.copy(alpha = 0.5f),
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
        containerColor = Color(0xFF1F2C34),
        title = {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    prefix = if (prefix != null) {
                        { Text(prefix, color = WhatsAppGreenAccent) }
                    } else null,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = WhatsAppGreenAccent,
                        unfocusedBorderColor = WhatsAppTextSecondary,
                        cursorColor = WhatsAppGreenAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(textValue) },
                colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreenAccent)
            ) {
                Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WhatsAppTextSecondary)
            }
        }
    )
}
