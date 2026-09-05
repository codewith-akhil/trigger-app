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

private val TriggerLightBg = Color(0xFFFFFFFF)
private val TriggerGreenHeader = Color(0xFF008069)
private val TriggerGreenAccent = Color(0xFF00A884)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)
private val TriggerDivider = Color(0xFFF0F2F5)

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

    val contacts = remember(currentUserProfile) {
        listOf(
            SelectContactItem(
                id = "me_chat",
                name = currentUserProfile.name.ifEmpty { "You" },
                subtitle = "Message yourself",
                initialColor = 0xFF1FA855,
                isSelf = true
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = TriggerLightBg,
        topBar = {
            Surface(
                color = TriggerGreenHeader,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(TriggerGreenHeader)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        if (isSearchActive) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 16.sp
                                ),
                                cursorBrush = SolidColor(Color.White),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search contacts...",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 16.sp
                                        )
                                    }
                                    innerTextField()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("select_contact_search_field")
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color.White)
                                }
                            }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Select contact",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "4042 contacts",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(Color.White)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Invite a friend", color = TriggerTextPrimary) },
                                        onClick = { showMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Contacts", color = TriggerTextPrimary) },
                                        onClick = { showMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Refresh", color = TriggerTextPrimary) },
                                        onClick = { showMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Help", color = TriggerTextPrimary) },
                                        onClick = { showMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
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
                        Spacer(modifier = Modifier.height(8.dp))

                        // Section header: "Contacts on Trigger App"
                        Text(
                            text = "Contacts on Trigger App",
                            color = TriggerTextSecondary,
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
                .background(TriggerGreenAccent),
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
            color = TriggerTextPrimary,
            modifier = Modifier.weight(1f)
        )

        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = TriggerTextSecondary,
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
                color = TriggerTextPrimary
            )
            if (contact.subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.subtitle,
                    fontSize = 13.sp,
                    color = TriggerTextSecondary
                )
            }
        }
    }
}
