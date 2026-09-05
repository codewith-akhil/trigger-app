package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.TriggerTopHeader
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val TriggerGreenAccent = Color(0xFF008069)
private val TriggerFabGreen = Color(0xFF00A884)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)
private val TriggerDanger = Color(0xFFD32F2F)
private val TriggerDivider = Color(0xFFF0F2F5)

data class UserSearchResult(
    val id: String, val name: String, val username: String?,
    val avatarUrl: String?, val phone: String?
)

data class MessageRequestItem(
    val id: String, val senderId: String, val senderName: String,
    val senderUsername: String?, val senderAvatarUrl: String?,
    val initialMessage: String, val createdAt: String
)

data class ContactItem(
    val id: String, val name: String, val username: String?,
    val avatarUrl: String?, val isOnline: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onChatOpened: (conversationId: String, contactId: String, contactName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UserSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var messageRequests by remember { mutableStateOf<List<MessageRequestItem>>(emptyList()) }
    var showSendDialog by remember { mutableStateOf<UserSearchResult?>(null) }

    // Fetch contacts + message requests on load
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            // Get contacts
            val contactsResult = AppServiceContainer.supabaseClient.invokeFunction("get-contacts", JSONObject())
            if (contactsResult is SupabaseResult.Success) {
                val arr = contactsResult.data.optJSONArray("contacts") ?: JSONArray()
                val list = mutableListOf<ContactItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(ContactItem(
                        id = obj.getString("id"),
                        name = obj.getString("full_name"),
                        username = obj.optString("username", null),
                        avatarUrl = obj.optString("avatar_url", null),
                        isOnline = obj.optBoolean("is_online", false)
                    ))
                }
                contacts = list
            }
            // Get message requests
            val reqResult = AppServiceContainer.supabaseClient.invokeFunction("get-message-requests", JSONObject())
            if (reqResult is SupabaseResult.Success) {
                val arr = reqResult.data.optJSONArray("requests") ?: JSONArray()
                val list = mutableListOf<MessageRequestItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(MessageRequestItem(
                        id = obj.getString("id"),
                        senderId = obj.getString("sender_id"),
                        senderName = obj.getString("sender_name"),
                        senderUsername = obj.optString("sender_username", null),
                        senderAvatarUrl = obj.optString("sender_avatar_url", null),
                        initialMessage = obj.getString("initial_message"),
                        createdAt = obj.optString("created_at", "")
                    ))
                }
                messageRequests = list
            }
        }
    }

    // Debounced search
    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(400)
        coroutineScope.launch {
            val payload = JSONObject().put("query", searchQuery.trim())
            val result = AppServiceContainer.supabaseClient.invokeFunction("search-users", payload)
            isSearching = false
            if (result is SupabaseResult.Success) {
                val arr = result.data.optJSONArray("users") ?: JSONArray()
                val list = mutableListOf<UserSearchResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(UserSearchResult(
                        id = obj.getString("id"),
                        name = obj.getString("full_name"),
                        username = obj.optString("username", null),
                        avatarUrl = obj.optString("avatar_url", null),
                        phone = obj.optString("phone", null)
                    ))
                }
                searchResults = list
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = { TriggerTopHeader(title = "New Message", onBack = onBack) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Search bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by username, name, or phone") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TriggerTextSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TriggerGreenAccent,
                        unfocusedBorderColor = TriggerDivider,
                        cursorColor = TriggerGreenAccent
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Search results
            if (searchQuery.trim().length >= 2) {
                if (isSearching) {
                    item {
                        Text("Searching...", color = TriggerTextSecondary, fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp))
                    }
                } else if (searchResults.isEmpty()) {
                    item {
                        Text("No users found", color = TriggerTextSecondary, fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp))
                    }
                } else {
                    items(searchResults) { user ->
                        UserSearchRow(user = user, onClick = { showSendDialog = user })
                    }
                }
            } else {
                // Message requests section
                if (messageRequests.isNotEmpty()) {
                    item {
                        Text(
                            "Message Requests (${messageRequests.size})",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = TriggerTextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(messageRequests) { req ->
                        MessageRequestRow(
                            request = req,
                            onAccept = {
                                coroutineScope.launch {
                                    val payload = JSONObject().apply {
                                        put("requestId", req.id)
                                        put("action", "accept")
                                    }
                                    val result = AppServiceContainer.supabaseClient.invokeFunction("respond-message-request", payload)
                                    if (result is SupabaseResult.Success) {
                                        val convId = result.data.optString("conversationId", "")
                                        Toast.makeText(context, "Request accepted", Toast.LENGTH_SHORT).show()
                                        messageRequests = messageRequests.filter { it.id != req.id }
                                        onChatOpened(convId, req.senderId, req.senderName)
                                    } else {
                                        Toast.makeText(context, "Failed to accept", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onBlock = {
                                coroutineScope.launch {
                                    val payload = JSONObject().apply {
                                        put("requestId", req.id)
                                        put("action", "block")
                                    }
                                    val result = AppServiceContainer.supabaseClient.invokeFunction("respond-message-request", payload)
                                    if (result is SupabaseResult.Success) {
                                        Toast.makeText(context, "User blocked", Toast.LENGTH_SHORT).show()
                                        messageRequests = messageRequests.filter { it.id != req.id }
                                    } else {
                                        Toast.makeText(context, "Failed to block", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                    item { HorizontalDivider(thickness = 8.dp, color = TriggerDivider) }
                }

                // Contacts section
                item {
                    Text(
                        "Contacts on Trigger (${contacts.size})",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = TriggerTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (contacts.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null,
                                tint = TriggerTextSecondary, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No contacts yet", color = TriggerTextSecondary, fontSize = 14.sp)
                            Text("Search for users by username to start chatting",
                                color = TriggerTextSecondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(contacts) { contact ->
                        ContactRow(contact = contact, onClick = {
                            // Open chat with this contact — find or create conversation
                            onChatOpened("", contact.id, contact.name)
                        })
                    }
                }
            }
        }
    }

    // Send message dialog (when user taps a search result)
    showSendDialog?.let { user ->
        var messageText by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSendDialog = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Send message to ${user.name}", fontWeight = FontWeight.Bold,
                    color = TriggerTextPrimary, fontSize = 16.sp)
            },
            text = {
                Column {
                    if (user.username != null) {
                        Text("@${user.username}", color = TriggerTextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { if (it.length <= 500) messageText = it },
                        placeholder = { Text("Type your first message...") },
                        singleLine = false, minLines = 2, maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TriggerGreenAccent,
                            unfocusedBorderColor = TriggerDivider,
                            cursorColor = TriggerGreenAccent
                        ),
                        enabled = !isSending,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${messageText.length}/500", fontSize = 11.sp,
                        color = TriggerTextSecondary, modifier = Modifier.align(Alignment.End))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (messageText.isBlank()) return@Button
                        isSending = true
                        coroutineScope.launch {
                            val payload = JSONObject().apply {
                                put("receiverId", user.id)
                                put("receiverName", user.name)
                                put("message", messageText.trim())
                            }
                            val result = AppServiceContainer.supabaseClient.invokeFunction("send-message-request", payload)
                            isSending = false
                            if (result is SupabaseResult.Success) {
                                Toast.makeText(context, "Message request sent", Toast.LENGTH_SHORT).show()
                                showSendDialog = null
                            } else {
                                val err = (result as? SupabaseResult.Error)?.message ?: "Failed to send"
                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSending && messageText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sending...", color = Color.White)
                    } else {
                        Text("Send", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendDialog = null }, enabled = !isSending) {
                    Text("Cancel", color = TriggerTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun UserSearchRow(user: UserSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE2E8F0)),
            contentAlignment = Alignment.Center) {
            if (user.avatarUrl != null) {
                AsyncImage(model = user.avatarUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Text(user.name.take(1).uppercase(), color = TriggerTextSecondary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TriggerTextPrimary)
            if (user.username != null) {
                Text("@${user.username}", fontSize = 13.sp, color = TriggerTextSecondary)
            } else if (user.phone != null) {
                Text(user.phone, fontSize = 13.sp, color = TriggerTextSecondary)
            }
        }
        Icon(Icons.Filled.PersonAdd, contentDescription = "Add",
            tint = TriggerGreenAccent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MessageRequestRow(
    request: MessageRequestItem,
    onAccept: () -> Unit,
    onBlock: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE2E8F0)),
                contentAlignment = Alignment.Center) {
                if (request.senderAvatarUrl != null) {
                    AsyncImage(model = request.senderAvatarUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Text(request.senderName.take(1).uppercase(), color = TriggerTextSecondary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(request.senderName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TriggerTextPrimary)
                if (request.senderUsername != null) {
                    Text("@${request.senderUsername}", fontSize = 12.sp, color = TriggerTextSecondary)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(request.initialMessage, fontSize = 14.sp, color = TriggerTextPrimary,
            maxLines = 2, modifier = Modifier.padding(start = 56.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.padding(start = 56.dp)) {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Accept", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onBlock,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TriggerDanger)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = TriggerDanger, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Block", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE2E8F0)),
            contentAlignment = Alignment.Center) {
            if (contact.avatarUrl != null) {
                AsyncImage(model = contact.avatarUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Text(contact.name.take(1).uppercase(), color = TriggerTextSecondary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TriggerTextPrimary)
            if (contact.username != null) {
                Text("@${contact.username}", fontSize = 13.sp, color = TriggerTextSecondary)
            }
        }
        if (contact.isOnline) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(TriggerFabGreen))
        }
    }
}
