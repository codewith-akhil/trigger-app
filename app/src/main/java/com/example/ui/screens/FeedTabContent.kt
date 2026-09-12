package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.di.AppServiceContainer
import com.example.model.FeedPost
import com.example.model.PostMediaType
import com.example.model.PostType
import com.example.model.UserRepository
import com.example.service.FeedResult
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.launch
import java.io.File

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen

/**
 * Feed tab — REAL data only. Posts come from the Supabase `get-feed` edge
 * function (newest always first). Sections:
 *   1. Stories tray (existing status stories)
 *   2. Drafts strip (Instagram-style drafts, backend-backed)
 *   3. Latest posts — unique 4:5 media cards
 *   4. Honest empty / backend-missing states (zero mock data)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTabContent(
    onNavigateToUpload: () -> Unit,
    onNavigateToPostView: (postId: String) -> Unit = {},
    onNavigateToPaymentOverview: (postId: String) -> Unit = {},
    onNavigateToEditDraft: (postId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val feedRepository = AppServiceContainer.feedRepository
    val posts by feedRepository.posts.collectAsState()
    val drafts by feedRepository.drafts.collectAsState()
    val isLoading by feedRepository.isLoading.collectAsState()
    val isLoadingMore by feedRepository.isLoadingMore.collectAsState()
    val backendMissing by feedRepository.backendMissing.collectAsState()

    // First load (newest first comes straight from the backend ordering)
    LaunchedEffect(Unit) {
        feedRepository.refresh()
        feedRepository.refreshDrafts()
    }

    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = {
            coroutineScope.launch {
                val result = feedRepository.refresh()
                feedRepository.refreshDrafts()
                when (result) {
                    is FeedResult.Error -> Toast.makeText(context, "Refresh failed: ${result.message}", Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        },
        state = pullState,
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // ------------------------------------------------------------
            // Section 1 — Stories tray
            // ------------------------------------------------------------
            item {
                FeedTopStoriesBar(onMyStatusClick = onNavigateToUpload)
            }

            // ------------------------------------------------------------
            // Section 2 — Drafts strip (real backend drafts)
            // ------------------------------------------------------------
            if (drafts.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.SaveAlt,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Your drafts", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF111B21))
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = CircleShape, color = AccentGreen.copy(alpha = 0.14f)) {
                                Text(
                                    "${drafts.size}",
                                    color = AccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(drafts, key = { it.id }) { draft ->
                                DraftCard(
                                    draft = draft,
                                    onClick = { onNavigateToEditDraft(draft.id) },
                                    onDelete = {
                                        coroutineScope.launch {
                                            when (feedRepository.deletePost(draft.id)) {
                                                is FeedResult.Error -> Toast.makeText(context, "Could not delete draft", Toast.LENGTH_SHORT).show()
                                                else -> Toast.makeText(context, "Draft deleted", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------------
            // Section 3 — Latest posts (unique 4:5 cards, newest on top)
            // ------------------------------------------------------------
            if (posts.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Latest posts", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF111B21))
                    }
                }

                items(posts, key = { it.id }) { post ->
                    FeedPostCard(
                        post = post,
                        onOpen = { onNavigateToPostView(post.id) },
                        onPayAndWatch = { onNavigateToPaymentOverview(post.id) },
                        onLike = { feedRepository.toggleLike(post.id) },
                        onComment = { onNavigateToPostView(post.id) },
                        onShare = {
                            Toast.makeText(context, "Sharing coming soon", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                // load more sentinel
                item {
                    if (isLoadingMore) {
                        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentGreen, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            } else if (!isLoading) {
                // ------------------------------------------------------------
                // Section 4 — Real empty states (NO fake content anywhere)
                // ------------------------------------------------------------
                item {
                    if (backendMissing != null) {
                        FeedBackendMissingState(detail = backendMissing ?: "")
                    } else {
                        FeedEmptyState(onCreatePost = onNavigateToUpload)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Draft card
// ----------------------------------------------------------------------------
@Composable
private fun DraftCard(
    draft: FeedPost,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E9EC)),
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .background(Color(0xFFECEFF1))
            ) {
                val model = draft.mediaUrls.firstOrNull() ?: draft.lockedPreviewUrls.firstOrNull()
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = "Draft media",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        tint = Color(0xFFB0BEC5),
                        modifier = Modifier.align(Alignment.Center).size(28.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFF3E0),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text(
                        "DRAFT",
                        color = Color(0xFFE65100),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete draft", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Text(
                draft.description.ifBlank { "Untitled draft" },
                fontSize = 11.sp,
                color = Color(0xFF37474F),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Post card — unique design, FIXED 4:5 media frame
// ----------------------------------------------------------------------------
@Composable
private fun FeedPostCard(
    post: FeedPost,
    onOpen: () -> Unit,
    onPayAndWatch: () -> Unit,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit
) {
    val isLocked = post.postType == PostType.PAID && !post.isUnlocked
    val userProfile by UserRepository.profile.collectAsState()

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column {
            // ---- Header ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clickable { onOpen() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5E9EC)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!post.authorAvatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = post.authorAvatarUrl,
                            contentDescription = post.authorName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            post.authorName.take(1).uppercase(),
                            color = BrandGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            post.authorName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF111B21)
                        )
                        if (post.authorId == userProfile.id) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(5.dp), color = AccentGreen.copy(alpha = 0.12f)) {
                                Text(
                                    "You",
                                    color = AccentGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        "@${post.authorUsername.ifBlank { "creator" }} · ${formatRelativeTime(post.timestamp)}",
                        fontSize = 11.sp,
                        color = Color(0xFF8696A0)
                    )
                }
                // Type pill
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (post.isUnlocked || post.postType == PostType.FREE) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (post.postType == PostType.PAID && !post.isUnlocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = null,
                            tint = if (post.postType == PostType.PAID && !post.isUnlocked) Color(0xFFE65100) else AccentGreen,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (post.postType == PostType.PAID) "${post.currency} ${trimAmount(post.priceAmount)}" else "FREE",
                            color = if (post.postType == PostType.PAID && !post.isUnlocked) Color(0xFFE65100) else AccentGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ---- Media: FIXED 4:5 unique frame ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .background(Color(0xFF0B141A))
                    .clickable { onOpen() }
            ) {
                if (isLocked) {
                    LockedMediaContent(post)
                } else {
                    UnlockedMediaContent(post)
                }
            }

            // ---- Action bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLike, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (post.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (post.isLikedByMe) Color(0xFFE0245E) else Color(0xFF54656F),
                        modifier = Modifier.size(21.dp)
                    )
                }
                if (post.likesCount > 0) {
                    Text(
                        "${post.likesCount}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF54656F)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onComment, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "Comments",
                        tint = Color(0xFF54656F),
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (post.commentsCount > 0) {
                    Text(
                        "${post.commentsCount}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF54656F)
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = "Share",
                        tint = Color(0xFF54656F),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // ---- Caption ----
            if (post.description.isNotBlank()) {
                Text(
                    text = "${post.authorName}  ${post.description}",
                    fontSize = 12.5.sp,
                    color = Color(0xFF111B21),
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 4.dp)
                        .clickable { onOpen() }
                )
            }

            // ---- Pay CTA (locked only) ----
            if (isLocked) {
                Button(
                    onClick = onPayAndWatch,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Filled.PlayCircleFilled, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Pay and Watch (${post.currency} ${trimAmount(post.priceAmount)})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Media content helpers
// ----------------------------------------------------------------------------
@Composable
private fun UnlockedMediaContent(post: FeedPost) {
    if (post.mediaUrls.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFF54656F))
        }
        return
    }

    if (post.mediaType == PostMediaType.VIDEO) {
        FeedVideoPlayer(videoUrl = post.mediaUrls.first())
    } else {
        val pagerState = rememberPagerState(pageCount = { post.mediaUrls.size })
        Box {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = post.mediaUrls[page],
                    contentDescription = "Post photo ${page + 1} of ${post.mediaUrls.size}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (post.mediaUrls.size > 1) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${post.mediaUrls.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                ) {
                    repeat(post.mediaUrls.size) { idx ->
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == pagerState.currentPage) Color.White
                                    else Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }
        }
    }
}

/** 85%-style lock: tiny blurred preview (images) or gradient+lock (videos). */
@Composable
private fun LockedMediaContent(post: FeedPost) {
    Box(Modifier.fillMaxSize()) {
        val preview = post.lockedPreviewUrls.firstOrNull()
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = "Locked media preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(26.dp)
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF111B21), Color(0xFF1F2C34), Color(0xFF111B21))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Locked",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Exclusive ${if (post.mediaType == PostMediaType.VIDEO) "video" else "content"}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                "Unlock to watch in full quality",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Empty / backend-missing states
// ----------------------------------------------------------------------------
@Composable
private fun FeedEmptyState(onCreatePost: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = AccentGreen.copy(alpha = 0.6f),
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text("No posts yet", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF111B21))
        Spacer(Modifier.height(6.dp))
        Text(
            "Share photos or videos with your followers.",
            fontSize = 13.sp,
            color = Color(0xFF667781),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onCreatePost,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create a post", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FeedBackendMissingState(detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = Color(0xFFE65100),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text("Feed backend is not ready", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF111B21))
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            fontSize = 12.5.sp,
            color = Color(0xFF667781),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun trimAmount(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp <= 0) return "just now"
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> {
            val fmt = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
            fmt.format(java.util.Date(timestamp))
        }
    }
}

// ----------------------------------------------------------------------------
// Stories tray (kept from the existing design)
// ----------------------------------------------------------------------------
@Composable
fun FeedTopStoriesBar(
    onMyStatusClick: () -> Unit,
    hasPosts: Boolean = true,
    onToggleEmptyStateDemo: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // My Post / Story item
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onMyStatusClick() }
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .border(2.dp, AccentGreen, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFECEFF1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Create post",
                            tint = AccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(AccentGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Your post", fontSize = 11.sp, color = Color(0xFF37474F), fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Video player (feed)
// ----------------------------------------------------------------------------
@Composable
fun FeedVideoPlayer(videoUrl: String) {
    val context = LocalContext.current
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
