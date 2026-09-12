package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.ProfilePhotoViewer
import com.example.ui.components.TriggerAlertDialog
import com.example.ui.components.TriggerBottomNavInset
import com.example.ui.components.TriggerTopHeader
import com.example.util.optStringOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val TriggerGreenAccent = Color(0xFF008069)
private val TriggerFabGreen = Color(0xFF00A884)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)
private val TriggerLightBg = Color(0xFFF7F8FA)
private val TriggerCardBg = Color(0xFFFFFFFF)
private val TriggerDivider = Color(0xFFF0F2F5)
private val TriggerDanger = Color(0xFFEA4335)

/**
 * UserProfileScreen
 * ----------------------------------------------------------------------------
 * Displays a target user's public profile when clicked from search results:
 * - Profile image on top
 * - Name and Username below image
 * - Follow and Message buttons
 * - About section
 * - Strictly NO email, phone, or other private details
 * - Block User and Report User actions
 * - Header and Mobile Nav styled as per existing designs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    user: UserSearchResult,
    onBack: () -> Unit,
    onOpenChat: (conversationId: String, peerId: String, contactName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentAvatarUrl by remember { mutableStateOf(user.avatarUrl) }
    var currentName by remember { mutableStateOf(user.name) }
    var currentUsername by remember { mutableStateOf(user.username) }
    var aboutText by remember { mutableStateOf("Hey there! I am using Trigger.") }

    var isFollowing by remember { mutableStateOf(false) }
    var isFollowBusy by remember { mutableStateOf(false) }
    var followersCount by remember { mutableIntStateOf(0) }
    var followingCount by remember { mutableIntStateOf(0) }

    var isContact by remember { mutableStateOf(false) }
    var isBlocked by remember { mutableStateOf(false) }

    // v1.0.13 cached-first: render the peer's last-known snapshot instantly
    // (previously every open waited on get-peer-profile + block + contact
    // round-trips before the header fields settled).
    (com.example.util.RefDataCache.getCachedPeer(user.id))?.let { cached ->
        cached.fullName?.let { currentName = it }
        cached.username?.let { currentUsername = it }
        cached.avatarUrl?.let { currentAvatarUrl = it }
        cached.about?.let { aboutText = it }
        isFollowing = cached.isFollowing
        followersCount = cached.followersCount
        followingCount = cached.followingCount
    }

    var showBlockDialog by remember { mutableStateOf(false) }
    var showUnblockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showMessageRequestDialog by remember { mutableStateOf(false) }
    var showPhotoViewer by remember { mutableStateOf(false) }

    // Fetch live profile information (about bio, freshest avatar, follow & block status).
    // The avatar/about/counts come from the get-peer-profile edge function —
    // the SERVER enforces the peer's profile_photo_visibility/about_visibility
    // there. Reading the profiles table directly would bypass that privacy.
    LaunchedEffect(user.id) {
        coroutineScope.launch {
            // 1. Privacy-filtered profile + follow state in one call
            val peerRes = AppServiceContainer.supabaseClient.invokeFunction(
                "get-peer-profile",
                JSONObject().put("peerId", user.id)
            )
            if (peerRes is SupabaseResult.Success) {
                val p = peerRes.data.optJSONObject("profile")
                if (p != null) {
                    val bio = p.optString("about", "")
                    if (bio.isNotBlank() && bio != "null") aboutText = bio
                    val av = p.optStringOrNull("avatar_url")
                    if (!av.isNullOrBlank()) currentAvatarUrl = av
                    val un = p.optStringOrNull("username")
                    if (!un.isNullOrBlank()) currentUsername = un
                    val fn = p.optStringOrNull("full_name")
                    if (!fn.isNullOrBlank()) currentName = fn
                }
                isFollowing = peerRes.data.optBoolean("isFollowing", false)
                followersCount = peerRes.data.optInt("followersCount", 0)
                followingCount = peerRes.data.optInt("followingCount", 0)
                // cache the fresh snapshot for instant next-open rendering
                com.example.util.RefDataCache.putPeer(
                    user.id,
                    com.example.util.RefDataCache.PeerSnapshot(
                        about = aboutText.takeIf { it.isNotBlank() && it != "Hey there! I am using Trigger." },
                        avatarUrl = currentAvatarUrl,
                        username = currentUsername,
                        fullName = currentName,
                        isFollowing = isFollowing,
                        followersCount = followersCount,
                        followingCount = followingCount,
                    )
                )
            }

            // 3. Check blocked contacts (keyed on the peer's auth UUID, not a
            // display name that can collide or change)
            val blockRes = AppServiceContainer.supabaseClient.invokeFunction(
                "manage-blocked-contacts",
                JSONObject().put("action", "check").put("blockedUserId", user.id)
            )
            if (blockRes is SupabaseResult.Success) {
                isBlocked = blockRes.data.optBoolean("blocked", false)
            }

            // 4. Check if existing contact
            val contactsRes = AppServiceContainer.supabaseClient.invokeFunction("get-contacts", JSONObject())
            if (contactsRes is SupabaseResult.Success) {
                val arr = contactsRes.data.optJSONArray("contacts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    if (arr.getJSONObject(i).optString("id") == user.id) {
                        isContact = true
                        break
                    }
                }
            }
        }
    }

    fun toggleFollow() {
        if (isFollowBusy) return
        isFollowBusy = true
        val action = if (isFollowing) "unfollow" else "follow"
        coroutineScope.launch {
            val payload = JSONObject()
                .put("targetUserId", user.id)
                .put("action", action)
            val res = AppServiceContainer.supabaseClient.invokeFunction("toggle-follow-user", payload)
            isFollowBusy = false
            if (res is SupabaseResult.Success) {
                isFollowing = !isFollowing
                if (isFollowing) {
                    followersCount++
                    Toast.makeText(context, "Following $currentName", Toast.LENGTH_SHORT).show()
                } else {
                    if (followersCount > 0) followersCount--
                    Toast.makeText(context, "Unfollowed $currentName", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Action failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun blockUser() {
        coroutineScope.launch {
            val payload = JSONObject()
                .put("action", "block")
                .put("blockedIdentifier", currentName)
                .put("blockedUserId", user.id)
            val res = AppServiceContainer.supabaseClient.invokeFunction("manage-blocked-contacts", payload)
            if (res is SupabaseResult.Success) {
                isBlocked = true
                Toast.makeText(context, "$currentName has been blocked", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to block user", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun unblockUser() {
        coroutineScope.launch {
            val payload = JSONObject()
                .put("action", "unblock")
                .put("blockedIdentifier", currentName)
            val res = AppServiceContainer.supabaseClient.invokeFunction("manage-blocked-contacts", payload)
            if (res is SupabaseResult.Success) {
                isBlocked = false
                Toast.makeText(context, "$currentName unblocked", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to unblock user", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun reportUser(reason: String) {
        coroutineScope.launch {
            val payload = JSONObject()
                .put("reported_user_id", user.id)
                .put("reason", reason.trim().ifEmpty { "Inappropriate profile or behavior" })
            val res = AppServiceContainer.supabaseClient.invokeFunction("report-user", payload)
            if (res is SupabaseResult.Success) {
                Toast.makeText(context, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to submit report", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("user_profile_screen"),
        containerColor = TriggerLightBg,
        topBar = {
            TriggerTopHeader(
                title = "Profile",
                onBack = onBack
            )
        },
        bottomBar = {
            TriggerBottomNavInset()
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // 1. Profile Image on the Top (tap to open the fullscreen viewer)
                    Surface(
                        modifier = Modifier
                            .size(116.dp)
                            .clickable { showPhotoViewer = true }
                            .testTag("user_profile_avatar"),
                        shape = CircleShape,
                        color = Color(0xFFE2E8F0),
                        border = BorderStroke(3.dp, TriggerCardBg),
                        shadowElevation = 3.dp
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!currentAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = currentAvatarUrl,
                                    contentDescription = "Profile Image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                Text(
                                    text = currentName.take(1).uppercase(),
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TriggerTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Name and Username
                    Text(
                        text = currentName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TriggerTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("user_profile_name")
                    )

                    if (!currentUsername.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "@$currentUsername",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TriggerTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("user_profile_username")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Follower counts
                    Text(
                        text = "$followersCount followers  •  $followingCount following",
                        fontSize = 13.sp,
                        color = TriggerTextSecondary,
                        fontWeight = FontWeight.Normal
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 3. Follow and Message Buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Follow Button
                        if (isFollowing) {
                            OutlinedButton(
                                onClick = { toggleFollow() },
                                enabled = !isFollowBusy,
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.5.dp, TriggerGreenAccent),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TriggerGreenAccent),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("user_profile_follow_button")
                            ) {
                                if (isFollowBusy) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                        color = TriggerGreenAccent
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Following", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        } else {
                            Button(
                                onClick = { toggleFollow() },
                                enabled = !isFollowBusy,
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("user_profile_follow_button")
                            ) {
                                if (isFollowBusy) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Follow", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        }

                        // Message Button
                        Button(
                            onClick = {
                                if (isContact) {
                                    onOpenChat("", user.id, currentName)
                                } else {
                                    showMessageRequestDialog = true
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen),
                            modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("user_profile_message_button")
                        ) {
                            Icon(
                                Icons.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Message", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 4. About Section (NO email, phone, or other private details displayed)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("user_profile_about_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = TriggerCardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = TriggerGreenAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "About",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TriggerGreenAccent
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = aboutText.ifBlank { "Hey there! I am using Trigger." },
                                fontSize = 15.sp,
                                color = TriggerTextPrimary,
                                lineHeight = 21.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 5. Block User and Report User Actions
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("user_profile_safety_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = TriggerCardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Block / Unblock Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isBlocked) showUnblockDialog = true
                                        else showBlockDialog = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                                    .testTag("user_profile_block_action"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Block,
                                    contentDescription = null,
                                    tint = if (isBlocked) TriggerGreenAccent else TriggerDanger,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = if (isBlocked) "Unblock $currentName" else "Block $currentName",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isBlocked) TriggerGreenAccent else TriggerDanger
                                )
                            }

                            HorizontalDivider(color = TriggerDivider)

                            // Report User Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showReportDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                                    .testTag("user_profile_report_action"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Report,
                                    contentDescription = null,
                                    tint = TriggerDanger,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "Report $currentName",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TriggerDanger
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Dialog: Message Request (when messaging a user not in contacts yet)
    if (showMessageRequestDialog) {
        var messageText by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }

        TriggerAlertDialog(
            onDismissRequest = { if (!isSending) showMessageRequestDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Message request to $currentName",
                    fontWeight = FontWeight.Bold,
                    color = TriggerTextPrimary,
                    fontSize = 17.sp
                )
            },
            text = {
                Column {
                    if (!currentUsername.isNullOrBlank()) {
                        Text("@$currentUsername", color = TriggerTextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        "They can read your messages and accept or decline. You can send up to 3 messages until they accept.",
                        color = TriggerTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { if (it.length <= 500) messageText = it },
                        placeholder = { Text("Type your first message...") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
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
                    Text(
                        "${messageText.length}/500",
                        fontSize = 11.sp,
                        color = TriggerTextSecondary,
                        modifier = Modifier.align(Alignment.End)
                    )
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
                                put("receiverName", currentName)
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
                                    showMessageRequestDialog = false
                                    if (convId.isNotBlank()) {
                                        onOpenChat(convId, user.id, currentName)
                                    }
                                }
                                is SupabaseResult.Error -> {
                                    val msg = result.message
                                    if (msg.contains("already chat", ignoreCase = true)) {
                                        showMessageRequestDialog = false
                                        onOpenChat("", user.id, currentName)
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
                TextButton(
                    onClick = { showMessageRequestDialog = false },
                    enabled = !isSending
                ) {
                    Text("Cancel", color = TriggerTextSecondary)
                }
            }
        )
    }

    // Dialog: Block Confirmation (compact)
    if (showBlockDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showBlockDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "Block $currentName?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TriggerTextPrimary
                )
            },
            text = {
                Text(
                    "They won't be able to message or call you anymore.",
                    color = TriggerTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockDialog = false
                        blockUser()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerDanger),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Block", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel", color = TriggerGreenAccent)
                }
            }
        )
    }

    // Dialog: Unblock Confirmation (compact)
    if (showUnblockDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showUnblockDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "Unblock $currentName?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TriggerTextPrimary
                )
            },
            text = {
                Text(
                    "They will be able to message and call you again.",
                    color = TriggerTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnblockDialog = false
                        unblockUser()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Unblock", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnblockDialog = false }) {
                    Text("Cancel", color = TriggerGreenAccent)
                }
            }
        )
    }

    // Dialog: Report User (compact WhatsApp-style card — no category radios)
    if (showReportDialog) {
        var reportAndBlock by remember { mutableStateOf(false) }

        TriggerAlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "Report $currentName",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TriggerTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        "The last 5 messages in this chat will be sent to Trigger. " +
                            "$currentName won't know you reported them.",
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = TriggerTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reportAndBlock = !reportAndBlock },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = reportAndBlock,
                            onCheckedChange = { reportAndBlock = it }
                        )
                        Text(
                            "Block $currentName",
                            fontSize = 15.sp,
                            color = TriggerTextPrimary
                        )
                    }
                    Text(
                        "They won't be able to message or call you.",
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        color = TriggerTextSecondary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReportDialog = false
                    reportUser("Inappropriate profile or behavior")
                    if (reportAndBlock && !isBlocked) blockUser()
                }) {
                    Text("Report", color = TriggerGreenAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Cancel", color = TriggerGreenAccent)
                }
            }
        )
    }

    // Fullscreen profile photo viewer
    if (showPhotoViewer) {
        ProfilePhotoViewer(
            imageUrl = currentAvatarUrl,
            initialLetter = currentName.take(1).uppercase(),
            onClose = { showPhotoViewer = false },
            isOwnProfile = false
        )
    }
}
