package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.components.TriggerAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.TriggerTopHeader
import com.example.util.optStringOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject

// Screen-local palette (compact dialog conventions: dark pink #AD1457 confirm).
private val BlockedGreen = Color(0xFFAD1457)
private val BlockedTextPrimary = Color(0xFF111B21)
private val BlockedTextSecondary = Color(0xFF667781)
private val BlockedDanger = Color(0xFFEA4335)
private val BlockedAvatarBg = Color(0xFFF0F2F5)

/** One row of the blocked-contacts list — a `blocked_contacts` row joined to
 *  the profile of the blocked user (when the identifier maps to a real user). */
data class BlockedUserEntry(
    val rowId: String,
    /** UUID of the blocked user when known; null for phone/legacy rows. */
    val blockedUserId: String?,
    val blockedIdentifier: String,
    /** Resolved profile display name; blank → the UI shows blocked_identifier. */
    val displayName: String,
    val avatarUrl: String?
)

/**
 * Blocked users page (UI only — behavior owned by the privacy task):
 *  - data: manage-blocked-contacts {action:"list"} → { blocked: [...] }
 *  - profile enrichment: batched profiles lookup on blocked_user_id
 *  - unblock: compact confirm dialog → manage-blocked-contacts
 *    {action:"unblock", blockedIdentifier} (exact payload of
 *    PrivacySettingsScreen.unblockContact) → list refresh
 *  - rows without a resolvable display name fall back to blocked_identifier.
 */
@Composable
fun BlockedUsersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = AppServiceContainer.supabaseClient

    var entries by remember { mutableStateOf<List<BlockedUserEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingUnblock by remember { mutableStateOf<BlockedUserEntry?>(null) }
    var isUnblocking by remember { mutableStateOf(false) }

    fun load() {
        isLoading = true
        loadError = null
        coroutineScope.launch {
            try {
                when (val res = client.invokeFunction(
                    "manage-blocked-contacts",
                    JSONObject().put("action", "list")
                )) {
                    is SupabaseResult.Success -> {
                        val arr = res.data.optJSONArray("blocked")
                        val rows = ArrayList<Triple<String, String?, String>>() // id, userId, identifier
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val row = arr.getJSONObject(i)
                                rows.add(
                                    Triple(
                                        row.optString("id", ""),
                                        row.optStringOrNull("blocked_user_id"),
                                        row.optString("blocked_identifier", "Unknown")
                                    )
                                )
                            }
                        }
                        // Batch profiles for rows that map to a real user.
                        val userIds = rows.mapNotNull { it.second }.distinct()
                        val profilesById = HashMap<String, Pair<String, String?>>()
                        if (userIds.isNotEmpty()) {
                            when (val pRes = client.getTable(
                                "profiles",
                                "id=in.(${userIds.joinToString(",")})&select=id,full_name,username,avatar_url"
                            )) {
                                is SupabaseResult.Success -> {
                                    for (i in 0 until pRes.data.length()) {
                                        val p = pRes.data.getJSONObject(i)
                                        val name = p.optStringOrNull("full_name")
                                            ?: p.optStringOrNull("username")
                                            ?: ""
                                        profilesById[p.optString("id")] = Pair(
                                            name,
                                            p.optStringOrNull("avatar_url")
                                        )
                                    }
                                }
                                is SupabaseResult.Error -> { /* identifier fallback */ }
                            }
                        }
                        entries = rows.map { (rowId, userId, identifier) ->
                            val profile = userId?.let { profilesById[it] }
                            BlockedUserEntry(
                                rowId = rowId.ifBlank { identifier },
                                blockedUserId = userId,
                                blockedIdentifier = identifier,
                                displayName = profile?.first?.takeIf { it.isNotBlank() } ?: "",
                                avatarUrl = profile?.second
                            )
                        }
                        isLoading = false
                    }
                    is SupabaseResult.Error -> {
                        loadError = "Couldn't load blocked users"
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                loadError = "Couldn't load blocked users"
                isLoading = false
            }
        }
    }

    fun unblock(entry: BlockedUserEntry) {
        if (isUnblocking) return
        isUnblocking = true
        coroutineScope.launch {
            try {
                when (client.invokeFunction(
                    "manage-blocked-contacts",
                    JSONObject()
                        .put("action", "unblock")
                        .put("blockedIdentifier", entry.blockedIdentifier)
                )) {
                    is SupabaseResult.Success -> {
                        pendingUnblock = null
                        isUnblocking = false
                        load()
                    }
                    is SupabaseResult.Error -> {
                        isUnblocking = false
                        pendingUnblock = null
                        Toast.makeText(context, "Couldn't unblock contact", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                isUnblocking = false
                pendingUnblock = null
                Toast.makeText(context, "Couldn't unblock contact", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val filtered = remember(entries, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) entries
        else entries.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                    it.blockedIdentifier.contains(q, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = { TriggerTopHeader(title = "Blocked contacts", onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search blocked users", color = BlockedTextSecondary) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = BlockedTextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BlockedGreen)
                    }
                }
                loadError != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = loadError ?: "Something went wrong",
                            color = BlockedDanger,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { load() },
                            colors = ButtonDefaults.buttonColors(containerColor = BlockedGreen),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                filtered.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            tint = BlockedTextSecondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.trim().isEmpty()) "No blocked users"
                            else "No matches for \"${searchQuery.trim()}\"",
                            color = BlockedTextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.rowId }) { entry ->
                            BlockedUserRow(
                                entry = entry,
                                onUnblock = { pendingUnblock = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUnblock?.let { target ->
        val displayName = target.displayName.ifBlank { target.blockedIdentifier }
        TriggerAlertDialog(
            onDismissRequest = { if (!isUnblocking) pendingUnblock = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Unblock $displayName?",
                    color = BlockedTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "They will be able to message and call you again.",
                    color = BlockedTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { unblock(target) },
                    enabled = !isUnblocking,
                    colors = ButtonDefaults.buttonColors(containerColor = BlockedGreen)
                ) {
                    Text("Unblock", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingUnblock = null },
                    enabled = !isUnblocking
                ) {
                    Text("Cancel", color = BlockedGreen, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun BlockedUserRow(
    entry: BlockedUserEntry,
    onUnblock: () -> Unit
) {
    val displayName = entry.displayName.ifBlank { entry.blockedIdentifier }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(BlockedAvatarBg),
            contentAlignment = Alignment.Center
        ) {
            if (entry.avatarUrl != null) {
                AsyncImage(
                    model = entry.avatarUrl,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(
                    text = displayName.take(1).uppercase(),
                    color = BlockedGreen,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = BlockedTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        TextButton(
            onClick = onUnblock,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Unblock", color = BlockedDanger, fontWeight = FontWeight.SemiBold)
        }
    }
}
