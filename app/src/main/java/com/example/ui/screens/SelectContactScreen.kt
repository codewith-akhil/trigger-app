package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.UserRepository

private val WhatsAppDarkBg = Color(0xFF0B141B)
private val WhatsAppDarkSurface = Color(0xFF111B21)
private val WhatsAppGreenAccent = Color(0xFF25D366)
private val WhatsAppTextPrimary = Color(0xFFE9EDEF)
private val WhatsAppTextSecondary = Color(0xFF8696A0)

data class SelectContactItem(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    val avatarRes: Int? = null,
    val initialColor: Long = 0xFF1B5E20,
    val isSelf: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectContactScreen(
    onBack: () -> Unit,
    onSelectContact: (contactId: String, contactName: String, avatarRes: Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUserProfile by UserRepository.profile.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    // Authentic contacts list matching user's uploaded screenshot & active chat list
    val contacts = remember(currentUserProfile) {
        listOf(
            SelectContactItem(
                id = "me_chat",
                name = "${currentUserProfile.name.ifEmpty { "Akhil" }} Canara Bank (You)",
                subtitle = "Message yourself",
                initialColor = 0xFF1FA855,
                isSelf = true
            ),
            SelectContactItem(
                id = "akash",
                name = "___akash",
                subtitle = "Available",
                avatarRes = R.drawable.img_media_sample
            ),
            SelectContactItem(
                id = "question_marks",
                name = "?????",
                subtitle = "Busy",
                avatarRes = R.drawable.img_darling_avatar
            ),
            SelectContactItem(
                id = "shahul",
                name = "+971 56 217 8394",
                subtitle = "~ shahul",
                avatarRes = R.drawable.img_media_sample
            ),
            SelectContactItem(
                id = "shahazuramuvasir",
                name = "~Shahazuramuvasir",
                subtitle = "At work",
                avatarRes = R.drawable.img_darling_avatar
            ),
            SelectContactItem(
                id = "stars_snowflake",
                name = "***❄️",
                subtitle = "Sleeping",
                initialColor = 0xFF00796B
            ),
            SelectContactItem(
                id = "darling",
                name = "darling",
                subtitle = "Hey there! I am using Trigger App",
                avatarRes = R.drawable.img_darling_avatar
            ),
            SelectContactItem(
                id = "jonathan",
                name = "Jonathan Doe",
                subtitle = "Living in the moment 🌟",
                avatarRes = R.drawable.img_media_sample
            ),
            SelectContactItem(
                id = "maya",
                name = "Maya Lin",
                subtitle = "In meetings today",
                avatarRes = R.drawable.img_darling_avatar
            ),
            SelectContactItem(
                id = "robert",
                name = "Dr. Robert Vance",
                subtitle = "Urgent calls only",
                avatarRes = R.drawable.img_media_sample
            )
        )
    }

    val filteredContacts = remember(searchQuery, contacts) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        (it.subtitle != null && it.subtitle.contains(searchQuery, ignoreCase = true))
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("select_contact_screen"),
        containerColor = WhatsAppDarkBg,
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(WhatsAppGreenAccent),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search contacts...",
                                        color = WhatsAppTextSecondary,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("select_contact_search_field")
                        )
                    } else {
                        Column {
                            Text(
                                text = "Select contact",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "4042 contacts",
                                color = WhatsAppTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                searchQuery = ""
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (isSearchActive) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color.White)
                            }
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(WhatsAppDarkSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Invite a friend", color = Color.White) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Contacts", color = Color.White) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh", color = Color.White) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Help", color = Color.White) },
                                onClick = { showMenu = false }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WhatsAppDarkBg
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top Action Items (New group, New contact, New community)
            if (!isSearchActive || searchQuery.isEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SelectContactActionItem(
                            icon = Icons.Filled.GroupAdd,
                            title = "New group",
                            onClick = {
                                // Default or quick group chat
                                onSelectContact("group_friends", "New Group", R.drawable.img_media_sample)
                            }
                        )

                        SelectContactActionItem(
                            icon = Icons.Filled.PersonAdd,
                            title = "New contact",
                            trailingIcon = Icons.Filled.QrCode,
                            onClick = {
                                onSelectContact("new_contact", "New Contact", null)
                            }
                        )

                        SelectContactActionItem(
                            icon = Icons.Filled.Groups,
                            title = "New community",
                            onClick = {
                                onSelectContact("community_general", "Trigger Community", null)
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Section header: "Contacts on Trigger App"
                        Text(
                            text = "Contacts on Trigger App",
                            color = WhatsAppTextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Contact Items
            items(filteredContacts, key = { it.id }) { contact ->
                SelectContactListItem(
                    contact = contact,
                    onClick = {
                        onSelectContact(contact.id, contact.name, contact.avatarRes)
                    }
                )
            }
        }
    }
}

@Composable
fun SelectContactActionItem(
    icon: ImageVector,
    title: String,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Green Circular Icon
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(WhatsAppGreenAccent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )

        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = WhatsAppTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun SelectContactListItem(
    contact: SelectContactItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("contact_item_${contact.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(contact.initialColor)),
            contentAlignment = Alignment.Center
        ) {
            if (contact.avatarRes != null) {
                Image(
                    painter = painterResource(id = contact.avatarRes),
                    contentDescription = contact.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (contact.isSelf) {
                Text(
                    text = contact.name.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = contact.name,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (contact.subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.subtitle,
                    fontSize = 13.sp,
                    color = WhatsAppTextSecondary
                )
            }
        }
    }
}
