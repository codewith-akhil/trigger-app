package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.service.MediaUrlResolver
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.TriggerTopHeader
import com.example.util.ChatTimeFormatter
import com.example.util.optStringOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject

// Screen-local palette (same values as the settings screens).
private val NotifGreenAccent = Color(0xFF008069)
private val NotifTextPrimary = Color(0xFF111B21)
private val NotifTextSecondary = Color(0xFF667781)
private val NotifDanger = Color(0xFFEA4335)
private val NotifAvatarBg = Color(0xFFF0F2F5)

/** One row of the notifications feed — a `user_notifications` row joined to
 *  the actor's public profile. */
data class NotificationEntry(
    val id: String,
    val actorId: String,
    /** "follow" | "message_request_accepted" */
    val type: String,
    val isRead: Boolean,
    val createdAtIso: String,
    val actorName: String,
    val actorUsername: String?,
    val actorAvatarUrl: String?
)

/**
 * Notifications feed (Instagram-referenced layout) backed by the real
 * `user_notifications` table:
 *  - fetch: GET user_notifications?user_id=eq.me&order=created_at.desc&limit=100
 *  - actor display data: batched `profiles` lookup (CallsTabContent pattern)
 *  - tap a row    → opens the actor's profile page (NavHost USER_PROFILE)
 *  - accept rows  → "Message" → opens the chat with the actor (NavHost)
 *    (follow rows carry no action button — the row itself opens the profile)
 *  - mark-read: ONE atomic PATCH (`UPDATE … is_read=true WHERE user_id=me AND
 *    is_read=false`). The previous implementation upserted each row back —
 *    an INSERT under the hood — and this table deliberately has NO insert
 *    policy (rows are only created by service-role edge functions), so every
 *    write silently failed under RLS and the bell badge never reset. PATCH
 *    maps 1:1 to the existing UPDATE policy. Then the dashboard bell badge
 *    is cleared via [onNotificationsRead].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onMessageActor: (conversationId: String, actorId: String, actorName: String) -> Unit = { _, _, _ -> },
    onOpenUserProfile: (UserSearchResult) -> Unit = {},
    onNotificationsRead: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<NotificationEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val client = AppServiceContainer.supabaseClient

    fun load() {
        isLoading = true
        loadError = null
        coroutineScope.launch {
            val me = client.currentSession?.user?.id
            if (me.isNullOrBlank()) {
                loadError = "You need to be signed in to see notifications."
                isLoading = false
                return@launch
            }
            try {
                val rows: List<JSONObject>
                when (val res = client.getTable(
                    "user_notifications",
                    "user_id=eq.$me&order=created_at.desc&limit=100&select=id,actor_id,type,is_read,created_at"
                )) {
                    is SupabaseResult.Success ->
                        rows = (0 until res.data.length()).map { res.data.getJSONObject(it) }
                    is SupabaseResult.Error -> {
                        loadError = "Couldn't load notifications"
                        isLoading = false
                        return@launch
                    }
                }

                // Batch actor profiles (avatar/username/name) — one query.
                val actorIds = rows.map { it.optString("actor_id") }
                    .filter { MediaUrlResolver.isUuid(it) }
                    .distinct()
                val profilesById = HashMap<String, Triple<String, String?, String?>>()
                if (actorIds.isNotEmpty()) {
                    when (val pRes = client.getTable(
                        "profiles",
                        "id=in.(${actorIds.joinToString(",")})&select=id,full_name,username,avatar_url"
                    )) {
                        is SupabaseResult.Success -> {
                            for (i in 0 until pRes.data.length()) {
                                val p = pRes.data.getJSONObject(i)
                                val fullName = p.optStringOrNull("full_name")
                                profilesById[p.optString("id")] = Triple(
                                    fullName ?: "",
                                    p.optStringOrNull("username"),
                                    p.optStringOrNull("avatar_url")
                                )
                            }
                        }
                        is SupabaseResult.Error -> { /* rows fall back to "Someone" */ }
                    }
                }

                // Which of the actors do I already follow? — NOT probed
                // anymore: follow rows carry no action button (row tap opens
                // the profile), so the extra `follows` query is dead weight.

                entries = rows.mapNotNull { row ->
                    val actorId = row.optString("actor_id")
                    if (actorId.isBlank()) return@mapNotNull null
                    val profile = profilesById[actorId]
                    val fullName = profile?.first.orEmpty()
                    NotificationEntry(
                        id = row.optString("id"),
                        actorId = actorId,
                        type = row.optString("type"),
                        isRead = row.optBoolean("is_read", false),
                        createdAtIso = row.optString("created_at"),
                        actorName = fullName.ifBlank { "Someone" },
                        actorUsername = profile?.second,
                        actorAvatarUrl = profile?.third
                    )
                }
                isLoading = false

                // Mark-read: ONE atomic PATCH — `UPDATE user_notifications SET
                // is_read=true WHERE user_id=me AND is_read=false`. RLS only
                // grants UPDATE on own rows here (no INSERT policy by design),
                // which is exactly why the old per-row upsert silently failed
                // and the bell badge never reset. Errors are non-fatal — the
                // badge refresh re-derives the truth.
                try {
                    client.patchRecord(
                        "user_notifications",
                        "user_id=eq.$me&is_read=eq.false",
                        JSONObject().put("is_read", true)
                    )
                } catch (e: Exception) {
                    // Non-fatal — the badge refresh re-derives the truth.
                }
                onNotificationsRead()
            } catch (e: Exception) {
                loadError = "Couldn't load notifications"
                isLoading = false
            }
        }
    }

    fun openActorProfile(entry: NotificationEntry) {
        onOpenUserProfile(
            UserSearchResult(
                id = entry.actorId,
                name = entry.actorName,
                username = entry.actorUsername,
                avatarUrl = entry.actorAvatarUrl
            )
        )
    }

    fun messageActor(entry: NotificationEntry) {
        coroutineScope.launch {
            // Non-UUID actor (shouldn't happen) → profile fallback.
            if (!MediaUrlResolver.isUuid(entry.actorId)) {
                onOpenUserProfile(
                    UserSearchResult(
                        id = entry.actorId,
                        name = entry.actorName,
                        username = entry.actorUsername,
                        avatarUrl = entry.actorAvatarUrl
                    )
                )
                return@launch
            }
            // ChatScreen resolves/creates the conversation from the peer uuid
            // when the local cache has none yet.
            val convId = try {
                AppServiceContainer.chatRepository.getConversationByPeer(entry.actorId)?.id
            } catch (e: Exception) {
                null
            }
            onMessageActor(convId ?: "", entry.actorId, entry.actorName)
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = { TriggerTopHeader(title = "Notifications", onBack = onBack) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        color = NotifGreenAccent,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                loadError != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = loadError ?: "Something went wrong",
                            color = NotifDanger,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { load() },
                            colors = ButtonDefaults.buttonColors(containerColor = NotifGreenAccent),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                entries.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = NotifTextSecondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No notifications yet",
                            color = NotifTextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.id }) { entry ->
                            NotificationRow(
                                entry = entry,
                                onClick = { openActorProfile(entry) },
                                onMessage = { messageActor(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    entry: NotificationEntry,
    onClick: () -> Unit,
    onMessage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Whole row opens the actor's profile — the trailing "Message"
            // button consumes its own clicks, so it still opens the chat.
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(NotifAvatarBg),
            contentAlignment = Alignment.Center
        ) {
            if (entry.actorAvatarUrl != null) {
                AsyncImage(
                    model = entry.actorAvatarUrl,
                    contentDescription = entry.actorName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(
                    text = entry.actorName.take(1).uppercase(),
                    color = NotifGreenAccent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val boldName = entry.actorUsername ?: entry.actorName
            val body = if (entry.type == "follow") " started following you." else " accepted your message request."
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldName) }
                    append(body)
                },
                color = NotifTextPrimary,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatNotificationRelativeTime(entry.createdAtIso),
                color = NotifTextSecondary,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        when (entry.type) {
            "follow" -> {
                // No follow/follow-back button on notification rows (user
                // request): tapping the row opens the actor's profile, where
                // the real follow action lives.
            }
            "message_request_accepted" -> {
                OutlinedButton(
                    onClick = onMessage,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NotifGreenAccent),
                    border = BorderStroke(1.dp, NotifGreenAccent),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Message", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            else -> {
                // Unknown future types render no action — the row is informational.
            }
        }
    }
}

/** Relative time for notification rows: "5m" / "2h" / "3d" / "1w". */
private fun formatNotificationRelativeTime(
    iso: String,
    now: Long = System.currentTimeMillis()
): String {
    if (iso.isBlank()) return ""
    val millis = ChatTimeFormatter.parseIsoToMillis(iso)
    if (millis <= 0L) return ""
    val diffMinutes = (now - millis) / 60_000L
    return when {
        diffMinutes < 1L -> "now"
        diffMinutes < 60L -> "${diffMinutes}m"
        diffMinutes < 60L * 24L -> "${diffMinutes / 60L}h"
        diffMinutes < 60L * 24L * 7L -> "${diffMinutes / (60L * 24L)}d"
        else -> "${diffMinutes / (60L * 24L * 7L)}w"
    }
}
