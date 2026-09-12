package com.example.ui.screens

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
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import com.example.service.supabase.SupabaseResult
import com.example.util.optStringOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val TriggerLightBg = Color(0xFFFFFFFF)
private val TriggerGreenHeader = Color(0xFFAD1457)
private val TriggerGreenAccent = Color(0xFFD81B60)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)
private val TriggerDivider = Color(0xFFF0F2F5)

data class SelectContactItem(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    val avatarRes: Int? = null,
    val avatarUrl: String? = null,
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
    val coroutineScope = rememberCoroutineScope()
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    // Real contacts state fetched from the `get-contacts` edge function.
    var contacts by remember { mutableStateOf<List<SelectContactItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }

    // Build the "Message yourself" row that always shows as the first item.
    // H4: pass the REAL own auth uuid (previously a fake "me_chat" id that
    // failed every uuid-validated server call).
    val selfId = com.example.di.AppServiceContainer.supabaseClient.currentSession?.user?.id ?: ""
    val selfItem = remember(currentUserProfile, selfId) {
        SelectContactItem(
            id = selfId,
            name = currentUserProfile.name.ifEmpty { "You" },
            subtitle = "Message yourself",
            initialColor = 0xFFD81B60,
            isSelf = true
        )
    }

    // Fetch real contacts from the backend. Called on first launch + on retry.
    fun loadContacts() {
        isLoading = true
        loadError = false
        coroutineScope.launch {
            val result = AppServiceContainer.supabaseClient.invokeFunction("get-contacts", JSONObject())
            isLoading = false
            if (result is SupabaseResult.Success) {
                val arr = result.data.optJSONArray("contacts") ?: JSONArray()
                val list = mutableListOf<SelectContactItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        SelectContactItem(
                            id = obj.getString("id"),
                            name = obj.getString("full_name"),
                            subtitle = obj.optString("username", null)?.let { "@$it" },
                            avatarUrl = obj.optStringOrNull("avatar_url"),
                            initialColor = 0xFF1B5E20
                        )
                    )
                }
                contacts = list
                loadError = false
            } else {
                loadError = true
            }
        }
    }

    // Initial fetch on screen entry.
    LaunchedEffect(Unit) {
        loadContacts()
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
                                    text = "${contacts.size} contacts",
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
                                        text = { Text("Refresh", color = TriggerTextPrimary) },
                                        onClick = {
                                            showMenu = false
                                            loadContacts()
                                        }
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

                // "Message yourself" is always the first row.
                item(key = "me_chat") {
                    SelectContactListItem(
                        contact = selfItem,
                        onClick = {
                            onSelectContact(selfItem.id, selfItem.name, selfItem.avatarRes)
                        }
                    )
                }
            }

            // Loading state
            if (isLoading && contacts.isEmpty() && !isSearchActive) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = TriggerGreenAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Error state — tappable to retry
            if (loadError && contacts.isEmpty() && !isSearchActive) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { loadContacts() }
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Retry",
                            tint = TriggerTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Failed to load contacts — tap to retry",
                            color = TriggerTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Empty state — only when not loading, no error, and list is empty
            if (!isLoading && !loadError && contacts.isEmpty() && !isSearchActive) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = TriggerTextSecondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No contacts yet",
                            color = TriggerTextSecondary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Use New Message to search for users by username.",
                            color = TriggerTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Contact Items (real contacts from `get-contacts`)
            items(filteredContacts, key = { it.id }) { contact ->
                SelectContactListItem(
                    contact = contact,
                    onClick = {
                        onSelectContact(contact.id, contact.name, contact.avatarRes)
                    }
                )
            }

            // Empty search results
            if (isSearchActive && searchQuery.isNotBlank() && filteredContacts.isEmpty()) {
                item {
                    Text(
                        text = "No contacts match \"$searchQuery\"",
                        color = TriggerTextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
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
            when {
                contact.avatarRes != null -> {
                    Image(
                        painter = painterResource(id = contact.avatarRes),
                        contentDescription = contact.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                contact.avatarUrl != null -> {
                    AsyncImage(
                        model = contact.avatarUrl,
                        contentDescription = contact.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                contact.isSelf -> {
                    Text(
                        text = contact.name.take(1),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = contact.name,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
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
