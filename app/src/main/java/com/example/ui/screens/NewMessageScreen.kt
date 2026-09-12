package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.TriggerAlertDialog
import com.example.ui.components.TriggerTopHeader
import com.example.ui.theme.*
import com.example.util.optStringOrNull
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
    val avatarUrl: String?
)

data class MessageRequestItem(
    val id: String, val senderId: String, val senderName: String,
    val senderUsername: String?, val senderAvatarUrl: String?,
    val initialMessage: String, val conversationId: String?,
    val messageCount: Int, val createdAt: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onChatOpened: (conversationId: String, contactId: String, contactName: String) -> Unit,
    onNavigateToUserProfile: (UserSearchResult) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UserSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<UserSearchResult>>(emptyList()) }
    var followersList by remember { mutableStateOf<List<UserSearchResult>>(emptyList()) }
    var messageRequests by remember { mutableStateOf<List<MessageRequestItem>>(emptyList()) }
    var followingUsers by remember { mutableStateOf<List<UserSearchResult>>(emptyList()) }
    var showSendDialog by remember { mutableStateOf<UserSearchResult?>(null) }
    // Header caption counts — null until get-follow-info responds.
    var followCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Instagram-model state
    var followState by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) } // userId -> I follow them
    var followBusy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var requestSentTo by remember { mutableStateOf<Set<String>>(emptySet()) } // this session

    val contactIds = remember(contacts) { contacts.map { it.id }.toSet() }

    suspend fun refreshFollowStates(ids: List<String>) {
        if (ids.isEmpty()) return
        val me = AppServiceContainer.supabaseClient.currentSession?.user?.id ?: return
        val inList = ids.joinToString(",")
        val result = AppServiceContainer.supabaseClient.getTable(
            "follows",
            "follower_id=eq.$me&following_id=in.($inList)&select=following_id"
        )
        if (result is SupabaseResult.Success) {
            val followed = mutableSetOf<String>()
            for (i in 0 until result.data.length()) {
                followed.add(result.data.getJSONObject(i).optString("following_id"))
            }
            followState = followState + ids.associateWith { it in followed }
        }
    }

    // Fetch contacts + message requests + followers + following on load
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            // Get contacts — NOT rendered anymore, but the accepted-contact id
            // set still drives openOrRequest gating (chat vs message request).
            val contactsResult = AppServiceContainer.supabaseClient.invokeFunction("get-contacts", JSONObject())
            if (contactsResult is SupabaseResult.Success) {
                val arr = contactsResult.data.optJSONArray("contacts") ?: JSONArray()
                val list = mutableListOf<UserSearchResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(UserSearchResult(
                        id = obj.getString("id"),
                        name = obj.getString("full_name"),
                        username = obj.optString("username", null),
                        avatarUrl = obj.optStringOrNull("avatar_url")
                    ))
                }
                contacts = list
            }
            // Get message requests (now carries the canonical conversation id
            // + pre-accept message count)
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
                        senderAvatarUrl = obj.optStringOrNull("sender_avatar_url"),
                        initialMessage = obj.getString("initial_message"),
                        conversationId = obj.optString("conversation_id", null),
                        messageCount = obj.optInt("message_count", 0),
                        createdAt = obj.optString("created_at", "")
                    ))
                }
                messageRequests = list
            }
            // Get the users who follow me (paged — cap 3 pages of 100) for the
            // Followers section.
            var offset = 0
            var pages = 0
            val followerAcc = mutableListOf<UserSearchResult>()
            while (pages < 3) {
                val followResult = AppServiceContainer.supabaseClient.invokeFunction(
                    "get-follow-list",
                    JSONObject().put("type", "followers").put("limit", 100).put("offset", offset)
                )
                if (followResult !is SupabaseResult.Success) break
                val arr = followResult.data.optJSONArray("users") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    followerAcc.add(UserSearchResult(
                        id = obj.getString("id"),
                        name = obj.getString("full_name"),
                        username = obj.optString("username", null),
                        avatarUrl = obj.optStringOrNull("avatar_url")
                    ))
                }
                pages++
                val total = followResult.data.optInt("count", followerAcc.size)
                if (followerAcc.size >= total || arr.length() == 0) break
                offset = followerAcc.size
            }
            followersList = followerAcc
            refreshFollowStates(followerAcc.map { it.id })
            // Get the users I follow (Instagram model)
            val followResult = AppServiceContainer.supabaseClient.invokeFunction(
                "get-follow-list", JSONObject().put("type", "following").put("limit", 50)
            )
            if (followResult is SupabaseResult.Success) {
                val arr = followResult.data.optJSONArray("users") ?: JSONArray()
                val list = mutableListOf<UserSearchResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(UserSearchResult(
                        id = obj.getString("id"),
                        name = obj.getString("full_name"),
                        username = obj.optString("username", null),
                        avatarUrl = obj.optStringOrNull("avatar_url")
                    ))
                }
                followingUsers = list
                refreshFollowStates(list.map { it.id })
            }
            // Follow counts for the header caption ("Followers N · Following M").
            val myId = AppServiceContainer.supabaseClient.currentSession?.user?.id
            if (myId != null) {
                val infoResult = AppServiceContainer.supabaseClient.invokeFunction(
                    "get-follow-info", JSONObject().put("targetUserId", myId)
                )
                if (infoResult is SupabaseResult.Success) {
                    followCounts = Pair(
                        infoResult.data.optInt("followersCount", 0),
                        infoResult.data.optInt("followingCount", 0)
                    )
                }
            }
        }
    }

    // Debounced search — server matches username OR full name OR phone
    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length < 2) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(350)
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
                    avatarUrl = obj.optStringOrNull("avatar_url")
                ))
            }
            searchResults = list
            refreshFollowStates(list.map { it.id })
        } else {
            searchResults = emptyList()
        }
    }

    fun toggleFollow(user: UserSearchResult) {
        val currentlyFollowing = followState[user.id] ?: false
        val action = if (currentlyFollowing) "unfollow" else "follow"
        followBusy = followBusy + user.id
        coroutineScope.launch {
            val payload = JSONObject()
                .put("targetUserId", user.id)
                .put("action", action)
            val result = AppServiceContainer.supabaseClient.invokeFunction("toggle-follow-user", payload)
            followBusy = followBusy - user.id
            when (result) {
                is SupabaseResult.Success -> {
                    followState = followState + (user.id to !currentlyFollowing)
                    followingUsers =
                        if (!currentlyFollowing) followingUsers + user
                        else followingUsers.filter { it.id != user.id }
                    followCounts?.let { (n, m) ->
                        followCounts = if (!currentlyFollowing) {
                            // I started following someone → my following count grew.
                            Pair(n, m + 1)
                        } else {
                            Pair(n, (m - 1).coerceAtLeast(0))
                        }
                    }
                }
                is SupabaseResult.Error ->
                    Toast.makeText(context, "Action failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // A searched/known user is chat-ready when an accepted relationship exists
    // (i.e. they are in my contacts); otherwise their first message is a request.
    fun openOrRequest(user: UserSearchResult) {
        if (user.id in contactIds) {
            onChatOpened("", user.id, user.name)
        } else {
            showSendDialog = user
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
            // Header caption — follow counts (13sp secondary)
            followCounts?.let { counts ->
                item {
                    Text(
                        "Followers ${counts.first} · Following ${counts.second}",
                        fontSize = 13.sp,
                        color = TriggerTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Search bar - standard, normal size capsule
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFF0F2F5)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = TriggerTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search by username, name, or phone",
                                    color = TriggerTextSecondary,
                                    fontSize = 14.5.sp
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    color = TriggerTextPrimary,
                                    fontSize = 14.5.sp
                                ),
                                cursorBrush = SolidColor(TriggerGreenAccent),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear",
                                    tint = TriggerTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
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
                    items(searchResults, key = { it.id }) { user ->
                        UserSearchRow(
                            user = user,
                            isFollowing = followState[user.id] ?: false,
                            followBusy = user.id in followBusy,
                            isContact = user.id in contactIds,
                            requestSent = user.id in requestSentTo,
                            onFollowToggle = { toggleFollow(user) },
                            onClick = { onNavigateToUserProfile(user) }
                        )
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
                    items(messageRequests, key = { "req_${it.id}" }) { req ->
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
                                        // Canonical conversation id — the SAME thread
                                        // the sender writes into.
                                        val convId = result.data.optString("conversationId", "")
                                        Toast.makeText(context, "Request accepted", Toast.LENGTH_SHORT).show()
                                        messageRequests = messageRequests.filter { it.id != req.id }
                                        onChatOpened(convId, req.senderId, req.senderName)
                                    } else {
                                        Toast.makeText(context, "Failed to accept", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onDecline = {
                                coroutineScope.launch {
                                    val payload = JSONObject().apply {
                                        put("requestId", req.id)
                                        put("action", "decline")
                                    }
                                    val result = AppServiceContainer.supabaseClient.invokeFunction("respond-message-request", payload)
                                    if (result is SupabaseResult.Success) {
                                        Toast.makeText(context, "Request declined", Toast.LENGTH_SHORT).show()
                                        messageRequests = messageRequests.filter { it.id != req.id }
                                    } else {
                                        Toast.makeText(context, "Failed to decline", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                    item { HorizontalDivider(thickness = 8.dp, color = TriggerDivider) }
                }

                // Followers section (replaces the old Contacts section — the
                // social graph is the primary people list now; accepted
                // contacts still gate openOrRequest via contactIds)
                item {
                    Text(
                        "Followers (${followersList.size})",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = TriggerTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (followersList.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null,
                                tint = TriggerTextSecondary, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No followers yet", color = TriggerTextSecondary, fontSize = 14.sp)
                            Text("Search for users by username to start chatting",
                                color = TriggerTextSecondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(followersList, key = { "follower_${it.id}" }) { user ->
                        UserSearchRow(
                            user = user,
                            isFollowing = followState[user.id] ?: false,
                            followBusy = user.id in followBusy,
                            isContact = user.id in contactIds,
                            requestSent = user.id in requestSentTo,
                            onFollowToggle = { toggleFollow(user) },
                            onClick = { openOrRequest(user) },
                            followBackLabel = "Follow back"
                        )
                    }
                }

                // Following section (Instagram model — following ≠ chatting yet)
                if (followingUsers.isNotEmpty()) {
                    item {
                        HorizontalDivider(thickness = 8.dp, color = TriggerDivider)
                        Text(
                            "Following (${followingUsers.size})",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = TriggerTextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(followingUsers.filter { it.id !in contactIds }, key = { "follow_${it.id}" }) { user ->
                        UserSearchRow(
                            user = user,
                            isFollowing = followState[user.id] ?: true,
                            followBusy = user.id in followBusy,
                            isContact = false,
                            requestSent = user.id in requestSentTo,
                            onFollowToggle = { toggleFollow(user) },
                            onClick = { onNavigateToUserProfile(user) }
                        )
                    }
                }
            }
        }
    }

    // Send message-request dialog (first message to a non-contact)
    showSendDialog?.let { user ->
        var messageText by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }
        TriggerAlertDialog(
            onDismissRequest = { if (!isSending) showSendDialog = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Message request to ${user.name}", fontWeight = FontWeight.Bold,
                    color = TriggerTextPrimary, fontSize = 17.sp)
            },
            text = {
                Column {
                    if (user.username != null) {
                        Text("@${user.username}", color = TriggerTextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
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
                            when (result) {
                                is SupabaseResult.Success -> {
                                    val convId = result.data.optString("conversationId", "")
                                    val remaining = result.data.optInt("messagesRemaining", 2)
                                    Toast.makeText(
                                        context,
                                        "Request sent • $remaining of 3 messages left",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showSendDialog = null
                                    requestSentTo = requestSentTo + user.id
                                    // Open the pending chat — the requester sees the
                                    // "waiting for acceptance" banner there.
                                    if (convId.isNotBlank()) {
                                        onChatOpened(convId, user.id, user.name)
                                    }
                                }
                                is SupabaseResult.Error -> {
                                    val msg = result.message
                                    if (msg.contains("already chat", ignoreCase = true)) {
                                        // ALREADY_CONNECTED — open the existing chat
                                        showSendDialog = null
                                        onChatOpened("", user.id, user.name)
                                    } else {
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
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
private fun UserSearchRow(
    user: UserSearchResult,
    isFollowing: Boolean,
    followBusy: Boolean,
    isContact: Boolean,
    requestSent: Boolean,
    onFollowToggle: () -> Unit,
    onClick: () -> Unit,
    followBackLabel: String = "Follow"
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
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
            } else if (isContact) {
                Text("Contact", fontSize = 13.sp, color = TriggerTextSecondary)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Instagram-model Follow / Following toggle (ONLY button on the right side).
        // 48dp minimum touch target on both variants.
        if (isFollowing) {
            OutlinedButton(
                onClick = onFollowToggle,
                enabled = !followBusy,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TriggerTextSecondary),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                if (followBusy) {
                    CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                } else {
                    Text("Following", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Button(
                onClick = onFollowToggle,
                enabled = !followBusy,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                if (followBusy) {
                    CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp), color = Color.White)
                } else {
                    Text(followBackLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MessageRequestRow(
    request: MessageRequestItem,
    onAccept: () -> Unit,
    onDecline: () -> Unit
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
            Text(
                "${request.messageCount}/3",
                fontSize = 11.sp, color = TriggerTextSecondary, fontWeight = FontWeight.SemiBold
            )
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
                onClick = onDecline,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TriggerDanger)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = TriggerDanger, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Decline", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
