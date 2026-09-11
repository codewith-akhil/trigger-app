package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.model.FeedPost
import com.example.model.PostComment
import com.example.model.PostMediaType
import com.example.model.PostType
import com.example.model.UserRepository
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen

/**
 * Full-screen Post View Page with a pure black background, back navigation icon,
 * Instagram model commenting section/sheet, like & comment counter, and Pay & Watch trigger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostViewScreen(
    postId: String,
    onBack: () -> Unit,
    onNavigateToPaymentOverview: (postId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val feedRepository = AppServiceContainer.feedRepository
    val posts by feedRepository.posts.collectAsState()
    val post = posts.find { it.id == postId }
    val userProfile by UserRepository.profile.collectAsState()
    val reportedCommentIds by feedRepository.reportedCommentIds.collectAsState()

    // Re-fetch the post on open — signed media URLs expire (1h TTL) and the
    // unlock state may have changed on another device.
    LaunchedEffect(postId) {
        feedRepository.getPostLive(postId)
    }

    var showCommentsSheet by remember { mutableStateOf(false) }
    var doubleTapHeartVisible by remember { mutableStateOf(false) }
    var replyingToComment by remember { mutableStateOf<PostComment?>(null) }
    var reportingComment by remember { mutableStateOf<PostComment?>(null) }
    var commentInputText by remember { mutableStateOf("") }
    var isDescriptionExpanded by remember { mutableStateOf(false) }

    // Hardware/System back gesture handling: Dismiss dialogs/sheets first, then route onBack
    BackHandler {
        if (reportingComment != null) {
            reportingComment = null
        } else if (showCommentsSheet) {
            showCommentsSheet = false
        } else {
            onBack()
        }
    }

    if (post == null) {
        // Fallback if post was removed or id not found
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Post not found or unavailable",
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Go Back")
                }
            }
        }
        return
    }

    val isPaidLocked = post.postType == PostType.PAID && !post.isUnlocked

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("post_view_screen"),
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            // ----------------------------------------------------------------
            // Main Scrollable Media Canvas & Instagram Details
            // ----------------------------------------------------------------
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("post_view_scrollable_container"),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                // Top spacing under translucent app bar
                item {
                    Spacer(modifier = Modifier.height(60.dp))
                }

                // Author Row Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Author Avatar
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF262626))
                                .border(1.5.dp, if (post.postType == PostType.PAID) Color(0xFFFFB300) else AccentGreen, CircleShape),
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
                                    text = post.authorName.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = post.authorName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                if (post.postType == PostType.PAID) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (post.isUnlocked) AccentGreen.copy(alpha = 0.25f) else Color(0xFFFF9800).copy(alpha = 0.25f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (post.isUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                                contentDescription = null,
                                                tint = if (post.isUnlocked) AccentGreen else Color(0xFFFFB300),
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = if (post.isUnlocked) "UNLOCKED" else "${post.currency.take(3)} ${post.priceAmount.toInt()}",
                                                color = if (post.isUnlocked) AccentGreen else Color(0xFFFFB300),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "@${post.authorUsername.ifBlank { "creator" }} • ${formatPostViewRelativeTime(post.timestamp)}",
                                color = Color(0xFFA8A8A8),
                                fontSize = 12.5.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Check out this post by ${post.authorName}")
                                    putExtra(Intent.EXTRA_TEXT, "${post.description}\n\nShared via Trigger App")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share post"))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More Options",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // ------------------------------------------------------------
                // Full Media View (Photos 4:5 / Video 9:16) with 85% Blur if Paid
                // ------------------------------------------------------------
                item {
                    val aspectRatio = if (post.mediaType == PostMediaType.VIDEO) 9f / 16f else 4f / 5f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                            .background(Color(0xFF0D0D0D))
                            .pointerInput(post.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (!post.isLikedByMe) {
                                            feedRepository.toggleLike(post.id)
                                        }
                                        coroutineScope.launch {
                                            doubleTapHeartVisible = true
                                            delay(750)
                                            doubleTapHeartVisible = false
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Media Content
                        if (post.mediaType == PostMediaType.IMAGE) {
                            if (post.mediaUrls.size > 1) {
                                val pagerState = rememberPagerState(pageCount = { post.mediaUrls.size })
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (isPaidLocked) Modifier.blur(30.dp) else Modifier)
                                ) { page ->
                                    AsyncImage(
                                        model = post.mediaUrls[page],
                                        contentDescription = "Photo slide ${page + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                if (!isPaidLocked) {
                                    // Slide indicator pills
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color.Black.copy(alpha = 0.70f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(14.dp)
                                    ) {
                                        Text(
                                            text = "${pagerState.currentPage + 1}/${post.mediaUrls.size}",
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            } else {
                                // Locked paid posts carry a tiny preview URL for the
                                // blur treatment (full urls are withheld by the server).
                                val url = post.mediaUrls.firstOrNull()
                                    ?: post.lockedPreviewUrls.firstOrNull()
                                    ?: ""
                                AsyncImage(
                                    model = url,
                                    contentDescription = "Post Image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (isPaidLocked) Modifier.blur(30.dp) else Modifier)
                                )
                            }
                        } else {
                            // Video Media
                            if (isPaidLocked) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .blur(30.dp)
                                        .background(Color(0xFF1E262C)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(72.dp)
                                    )
                                }
                            } else {
                                val videoUrl = post.mediaUrls.firstOrNull() ?: ""
                                PostViewVideoPlayer(videoUrl = videoUrl)
                            }
                        }

                        // ----------------------------------------------------
                        // 85% Blur Security Lock Overlay & Pay and Watch Trigger
                        // ----------------------------------------------------
                        if (isPaidLocked) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = "Locked Content",
                                            tint = Color(0xFFFFCA28),
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = "Exclusive Locked Content",
                                        color = Color.White,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = "Full high-resolution media & comments are protected by an 85% security blur. Unlock to view immediately.",
                                        color = Color(0xFFD0D0D0),
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )

                                    Spacer(modifier = Modifier.height(20.dp))

                                    // "Pay and Watch" Button -> Navigates to dedicated Payment Overview Page
                                    Button(
                                        onClick = { onNavigateToPaymentOverview(post.id) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                        shape = RoundedCornerShape(26.dp),
                                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                                        modifier = Modifier.testTag("post_view_pay_and_watch_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Payment,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(19.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Pay and Watch (${post.currency.take(3)} ${post.priceAmount})",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Bursting heart animation on double tap
                        AnimatedVisibility(
                            visible = doubleTapHeartVisible,
                            enter = scaleIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) + fadeIn(),
                            exit = scaleOut(animationSpec = tween(220, easing = LinearOutSlowInEasing)) + fadeOut()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFE91E63),
                                modifier = Modifier
                                    .size(90.dp)
                                    .scale(1.2f)
                            )
                        }
                    }
                }

                // ------------------------------------------------------------
                // Instagram Action Bar: Like, Comment, Share, Bookmark
                // ------------------------------------------------------------
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Like Button
                        IconButton(
                            onClick = { feedRepository.toggleLike(post.id) },
                            modifier = Modifier.testTag("post_view_like_button")
                        ) {
                            Icon(
                                imageVector = if (post.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like Post",
                                tint = if (post.isLikedByMe) Color(0xFFE91E63) else Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Comment Button
                        IconButton(
                            onClick = { showCommentsSheet = true },
                            modifier = Modifier.testTag("post_view_comment_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "Open Comments",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Share Button
                        IconButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Check out this post by ${post.authorName}")
                                    putExtra(Intent.EXTRA_TEXT, "${post.description}\n\nShared via Trigger App")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share post"))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Bookmark Button
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Filled.BookmarkBorder,
                                contentDescription = "Bookmark",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // ------------------------------------------------------------
                // Likes & Comments Count (prominently displayed)
                // ------------------------------------------------------------
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${post.likesCount} ${if (post.likesCount == 1) "like" else "likes"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = Color.White,
                            modifier = Modifier.testTag("post_view_likes_count")
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "•",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "${post.commentsCount} ${if (post.commentsCount == 1) "comment" else "comments"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = Color(0xFFA0A0A0),
                            modifier = Modifier
                                .testTag("post_view_comments_count")
                                .clickable { showCommentsSheet = true }
                        )
                    }
                }

                // ------------------------------------------------------------
                // Caption / Description
                // ------------------------------------------------------------
                item {
                    if (post.description.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "${post.authorName} ",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = post.description,
                                    color = Color(0xFFE5E5E5),
                                    fontSize = 14.sp,
                                    maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 20.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { isDescriptionExpanded = !isDescriptionExpanded }
                                )
                            }
                            if (post.description.length > 90 && !isDescriptionExpanded) {
                                Text(
                                    text = "...more",
                                    color = Color(0xFF8696A0),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clickable { isDescriptionExpanded = true }
                                )
                            }
                        }
                    }
                }

                // ------------------------------------------------------------
                // Instagram Model Comments Section (In-line Preview)
                // ------------------------------------------------------------
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Comments (${post.comments.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (post.comments.isNotEmpty()) "View all" else "Add comment",
                                color = AccentGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { showCommentsSheet = true }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (post.comments.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF141414),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "No comments yet. Be the first to share your thoughts!",
                                        color = Color(0xFFA0A0A0),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            // Show top 3 comments
                            post.comments.take(3).forEach { comment ->
                                InstagramCommentItem(
                                    comment = comment,
                                    isCreator = comment.authorId == post.authorId,
                                    onLikeComment = { feedRepository.toggleCommentLike(post.id, comment.id) },
                                    onReply = {
                                        replyingToComment = comment
                                        showCommentsSheet = true
                                    },
                                    onReportComment = {
                                        reportingComment = comment
                                    },
                                    isReported = reportedCommentIds.contains(comment.id)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // Sticky Top Navigation Bar with Back Icon
            // ----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Back Navigation Icon
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .testTag("post_view_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = "Post",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Right balance spacer
                    Box(modifier = Modifier.size(44.dp))
                }
            }

            // ----------------------------------------------------------------
            // Sticky Bottom Instagram Comment Input Bar
            // ----------------------------------------------------------------
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .imePadding(),
                color = Color(0xFF121212),
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF262626))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Quick Emoji Reaction Bar (Instagram Style)
                    val quickEmojis = listOf("❤️", "🙌", "🔥", "👏", "😂", "😮", "😍", "😢")
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(quickEmojis) { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        commentInputText += emoji
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }

                    Divider(color = Color(0xFF262626), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // User Avatar
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AccentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = userProfile.name.take(1).ifBlank { "U" }.uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        OutlinedTextField(
                            value = commentInputText,
                            onValueChange = { commentInputText = it },
                            placeholder = {
                                Text(
                                    text = "Add a comment as ${userProfile.name.ifBlank { "You" }}...",
                                    color = Color(0xFF757575),
                                    fontSize = 13.5.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentGreen,
                                unfocusedBorderColor = Color(0xFF333333),
                                focusedContainerColor = Color(0xFF1E1E1E),
                                unfocusedContainerColor = Color(0xFF1E1E1E)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("post_view_quick_comment_input"),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                if (commentInputText.isNotBlank()) {
                                    val newComment = PostComment(
                                        authorId = userProfile.id.ifBlank { "user_me" },
                                        authorName = userProfile.name.ifBlank { "You" },
                                        authorAvatarUrl = userProfile.avatarUri,
                                        text = commentInputText.trim(),
                                        timestamp = System.currentTimeMillis()
                                    )
                                    feedRepository.addComment(post.id, newComment)
                                    commentInputText = ""
                                }
                            },
                            enabled = commentInputText.isNotBlank(),
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (commentInputText.isNotBlank()) AccentGreen else Color(0xFF262626))
                                .testTag("post_view_send_comment_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Post Comment",
                                tint = if (commentInputText.isNotBlank()) Color.White else Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // Full Instagram Model Comments Sheet
            // ----------------------------------------------------------------
            if (showCommentsSheet) {
                InstagramCommentsModalSheet(
                    post = post,
                    userProfile = userProfile,
                    replyingTo = replyingToComment,
                    reportedCommentIds = reportedCommentIds,
                    onReportComment = { comment ->
                        reportingComment = comment
                    },
                    onClearReply = { replyingToComment = null },
                    onDismiss = { showCommentsSheet = false },
                    onAddComment = { text ->
                        val newComment = PostComment(
                            authorId = userProfile.id.ifBlank { "user_me" },
                            authorName = userProfile.name.ifBlank { "You" },
                            authorAvatarUrl = userProfile.avatarUri,
                            text = text.trim(),
                            timestamp = System.currentTimeMillis()
                        )
                        feedRepository.addComment(post.id, newComment)
                    },
                    onToggleCommentLike = { commentId ->
                        feedRepository.toggleCommentLike(post.id, commentId)
                    }
                )
            }

            // ----------------------------------------------------------------
            // Inappropriate Content Report Dialog with Reason Picker
            // ----------------------------------------------------------------
            reportingComment?.let { commentToReport ->
                CommentReportDialog(
                    comment = commentToReport,
                    onDismiss = { reportingComment = null },
                    onSubmitReport = { reason, details ->
                        feedRepository.reportComment(post.id, commentToReport.id, reason, details)
                        reportingComment = null
                        android.widget.Toast.makeText(
                            context,
                            "Report submitted. Thank you for keeping our community safe.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Inappropriate Content Report Dialog with Reason Picker
// ----------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentReportDialog(
    comment: PostComment,
    onDismiss: () -> Unit,
    onSubmitReport: (reason: String, details: String) -> Unit
) {
    val reportReasons = remember {
        listOf(
            "Spam or misleading" to "Commercial spam, fraudulent links, or deceptive interactions",
            "Harassment or bullying" to "Targeted personal attacks, threats, or hate speech",
            "Inappropriate content" to "Explicit, sexually suggestive, or offensive text",
            "Hate speech or discrimination" to "Attacking identity, race, religion, gender, or orientation",
            "Violence or dangerous content" to "Encouraging harm, illegal acts, or violence",
            "False information / Scam" to "Misleading financial claims, impersonation, or rumors",
            "Other" to "Other violations of community guidelines"
        )
    }

    var selectedReason by remember { mutableStateOf(reportReasons.first().first) }
    var additionalDetails by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFDCDCDC),
        shape = RoundedCornerShape(20.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ReportProblem,
                    contentDescription = "Report",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = "Report Comment",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Help us keep Trigger safe. Why are you reporting this comment?",
                    fontSize = 13.sp,
                    color = Color(0xFFA0A0A0),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quoted comment preview card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "@${comment.authorName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = AccentGreen
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "\"${comment.text.take(120)}${if (comment.text.length > 120) "..." else ""}\"",
                            fontSize = 12.5.sp,
                            color = Color(0xFFE0E0E0),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Select a reason:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Reasons picker
                reportReasons.forEach { (reasonTitle, reasonDesc) ->
                    val isSelected = selectedReason == reasonTitle
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) AccentGreen.copy(alpha = 0.18f) else Color(0xFF242424),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) AccentGreen else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { selectedReason = reasonTitle }
                            .testTag("report_reason_$reasonTitle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedReason = reasonTitle },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentGreen,
                                    unselectedColor = Color(0xFF757575)
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = reasonTitle,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else Color(0xFFD6D6D6)
                                )
                                Text(
                                    text = reasonDesc,
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF909090),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Additional details optional input
                OutlinedTextField(
                    value = additionalDetails,
                    onValueChange = { if (it.length <= 300) additionalDetails = it },
                    label = { Text("Additional details (optional)", color = Color(0xFF999999), fontSize = 12.sp) },
                    placeholder = { Text("Provide any extra context...", color = Color(0xFF666666), fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = Color(0xFF444444),
                        cursorColor = AccentGreen
                    ),
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report_details_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSubmitting = true
                    onSubmitReport(selectedReason, additionalDetails.trim())
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("submit_report_button")
            ) {
                Text(
                    text = if (isSubmitting) "Submitting..." else "Submit Report",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFA0A0A0)),
                modifier = Modifier.testTag("cancel_report_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

// ----------------------------------------------------------------------------
// Instagram Comment Item Component
// ----------------------------------------------------------------------------
@Composable
fun InstagramCommentItem(
    comment: PostComment,
    isCreator: Boolean,
    onLikeComment: () -> Unit,
    onReply: () -> Unit,
    onReportComment: () -> Unit = {},
    isReported: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Commenter Avatar
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF262626)),
            contentAlignment = Alignment.Center
        ) {
            if (!comment.authorAvatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = comment.authorAvatarUrl,
                    contentDescription = comment.authorName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = comment.authorName.take(1).uppercase(),
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.authorName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                if (isCreator) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AccentGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Author",
                            color = AccentGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatPostViewRelativeTime(comment.timestamp),
                    color = Color(0xFF757575),
                    fontSize = 11.5.sp
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = comment.text,
                color = Color(0xFFEEEEEE),
                fontSize = 13.5.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Reply",
                    color = Color(0xFFA0A0A0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onReply() }
                        .testTag("comment_reply_${comment.id}")
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Flag / Report Action Button with Reason Picker Trigger
                if (isReported) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("comment_reported_badge_${comment.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Flag,
                            contentDescription = "Reported",
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Reported",
                            color = Color(0xFFFF9800),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onReportComment() }
                            .testTag("comment_report_button_${comment.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Flag,
                            contentDescription = "Report Comment",
                            tint = Color(0xFFA0A0A0),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Report",
                            color = Color(0xFFA0A0A0),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (comment.likesCount > 0) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "${comment.likesCount} ${if (comment.likesCount == 1) "like" else "likes"}",
                        color = Color(0xFFA0A0A0),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Like Comment Heart Button
        IconButton(
            onClick = onLikeComment,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Like Comment",
                tint = if (comment.isLiked) Color(0xFFE91E63) else Color(0xFF757575),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Dedicated Full Instagram Model Comments Modal Sheet
// ----------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstagramCommentsModalSheet(
    post: FeedPost,
    userProfile: com.example.model.UserProfile,
    replyingTo: PostComment?,
    reportedCommentIds: Set<String> = emptySet(),
    onReportComment: (PostComment) -> Unit = {},
    onClearReply: () -> Unit,
    onDismiss: () -> Unit,
    onAddComment: (String) -> Unit,
    onToggleCommentLike: (String) -> Unit
) {
    var sheetCommentText by remember {
        mutableStateOf(replyingTo?.let { "@${it.authorName} " } ?: "")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 40.dp, height = 4.dp),
                shape = CircleShape,
                color = Color(0xFF3E3E3E)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .imePadding()
        ) {
            // Sheet Header
            Text(
                text = "Comments (${post.comments.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
            )
            Divider(color = Color(0xFF262626), thickness = 0.5.dp)

            // Comments List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (post.comments.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                tint = Color(0xFF666666),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No comments yet",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Start the conversation with ${post.authorName}.",
                                color = Color(0xFF888888),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(post.comments, key = { it.id }) { comment ->
                        InstagramCommentItem(
                            comment = comment,
                            isCreator = comment.authorId == post.authorId,
                            onLikeComment = { onToggleCommentLike(comment.id) },
                            onReply = {
                                sheetCommentText = "@${comment.authorName} "
                            },
                            onReportComment = { onReportComment(comment) },
                            isReported = reportedCommentIds.contains(comment.id)
                        )
                        Divider(color = Color(0xFF202020), thickness = 0.5.dp, modifier = Modifier.padding(start = 44.dp))
                    }
                }
            }

            // Quick Emoji Reaction Bar (Instagram Style)
            val quickEmojis = listOf("❤️", "🙌", "🔥", "👏", "😂", "😮", "😍", "😢")
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(quickEmojis) { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { sheetCommentText += emoji }
                            .padding(4.dp)
                    )
                }
            }

            // Reply Banner indicator
            if (replyingTo != null) {
                Surface(
                    color = Color(0xFF222222),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Replying to ${replyingTo.authorName}",
                            color = Color(0xFFA0A0A0),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel reply",
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onClearReply() }
                        )
                    }
                }
            }

            Divider(color = Color(0xFF262626), thickness = 0.5.dp)

            // Bottom Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userProfile.name.take(1).ifBlank { "U" }.uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedTextField(
                    value = sheetCommentText,
                    onValueChange = { sheetCommentText = it },
                    placeholder = {
                        Text(
                            text = "Add a comment as ${userProfile.name.ifBlank { "You" }}...",
                            color = Color(0xFF757575),
                            fontSize = 13.5.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedContainerColor = Color(0xFF222222),
                        unfocusedContainerColor = Color(0xFF222222)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sheet_comment_input"),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (sheetCommentText.isNotBlank()) {
                            onAddComment(sheetCommentText)
                            sheetCommentText = ""
                            onClearReply()
                        }
                    },
                    enabled = sheetCommentText.isNotBlank(),
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (sheetCommentText.isNotBlank()) AccentGreen else Color(0xFF282828))
                        .testTag("sheet_send_comment_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Post Comment",
                        tint = if (sheetCommentText.isNotBlank()) Color.White else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Post View Video Player
// ----------------------------------------------------------------------------
@Composable
fun PostViewVideoPlayer(videoUrl: String) {
    val context = LocalContext.current
    val player = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            val uri = Uri.parse(videoUrl)
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun formatPostViewRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d ago"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "just now"
    }
}
