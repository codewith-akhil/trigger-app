package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.example.model.PostComment
import com.example.model.PostMediaType
import com.example.model.PostType
import com.example.model.UserRepository
import com.example.service.RazorpayOrderResult
import com.example.service.RazorpayVerifyResult
import com.example.ui.payment.RazorpayCheckoutParams
import com.example.ui.payment.RazorpayCheckoutResult
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTabContent(
    onNavigateToUpload: () -> Unit,
    onNavigateToPostView: (postId: String) -> Unit = {},
    onNavigateToPaymentOverview: (postId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val feedRepository = AppServiceContainer.feedRepository
    val posts by feedRepository.posts.collectAsState()
    val userProfile by UserRepository.profile.collectAsState()

    var activeCommentPostId by remember { mutableStateOf<String?>(null) }
    var payingPost by remember { mutableStateOf<FeedPost?>(null) }
    var isPaymentProcessing by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------------
    // Razorpay checkout launcher for Paid Post "Pay and Watch"
    // ------------------------------------------------------------------------
    val checkoutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (val checkout = RazorpayCheckoutResult.fromActivityResult(result)) {
            is RazorpayCheckoutResult.Success -> {
                val currentPost = payingPost
                if (currentPost != null) {
                    coroutineScope.launch {
                        val verify = AppServiceContainer.razorpayPaymentService.verifyPayment(
                            orderId = checkout.orderId,
                            paymentId = checkout.paymentId,
                            signature = checkout.signature,
                            purpose = "post_unlock",
                            streamId = currentPost.id
                        )
                        isPaymentProcessing = false
                        when (verify) {
                            is RazorpayVerifyResult.Verified -> {
                                feedRepository.unlockPaidPost(currentPost.id)
                                Toast.makeText(context, "Post Unlocked Successfully!", Toast.LENGTH_SHORT).show()
                                payingPost = null
                            }
                            is RazorpayVerifyResult.Failed -> {
                                Toast.makeText(context, "Verification failed: ${verify.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    isPaymentProcessing = false
                }
            }
            is RazorpayCheckoutResult.Failure -> {
                isPaymentProcessing = false
                Toast.makeText(context, "Payment error: ${checkout.message}", Toast.LENGTH_LONG).show()
            }
            RazorpayCheckoutResult.Cancelled -> {
                isPaymentProcessing = false
            }
        }
    }

    fun startPaidPostCheckout(post: FeedPost) {
        payingPost = post
        isPaymentProcessing = true
        coroutineScope.launch {
            val currencyCode = post.currency.trim().substringBefore(' ').uppercase().ifBlank { "INR" }
            when (val order = AppServiceContainer.razorpayPaymentService.createOrder(
                amountMajor = post.priceAmount,
                currencyCode = currencyCode,
                purpose = "post_unlock",
                streamId = post.id
            )) {
                is RazorpayOrderResult.Ready -> {
                    checkoutLauncher.launch(
                        RazorpayCheckoutParams.intent(
                            context,
                            RazorpayCheckoutParams(
                                orderId = order.orderId,
                                keyId = order.keyId,
                                description = "Unlock Exclusive Post: ${post.authorName}",
                                prefillName = userProfile.name,
                                prefillEmail = userProfile.email
                            )
                        )
                    )
                }
                is RazorpayOrderResult.Failed -> {
                    isPaymentProcessing = false
                    Toast.makeText(context, "Order creation failed: ${order.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshing = true
                    feedRepository.refreshPosts()
                    isRefreshing = false
                    Toast.makeText(context, "Feed refreshed", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("feed_pull_to_refresh_box")
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("feed_posts_lazy_column"),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Stories / Status Header Row at the top of Feed
                item {
                    FeedTopStoriesBar(
                        onMyStatusClick = onNavigateToUpload,
                        hasPosts = posts.isNotEmpty(),
                        onToggleEmptyStateDemo = {
                            if (posts.isNotEmpty()) {
                                feedRepository.clearPostsForEmptyStateDemo()
                                Toast.makeText(context, "Cleared feed to preview Empty State", Toast.LENGTH_SHORT).show()
                            } else {
                                feedRepository.seedOrExplorePosts()
                                Toast.makeText(context, "Loaded feed posts!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // Empty State UI when no posts are available
                if (posts.isEmpty()) {
                    item {
                        FeedEmptyState(
                            onStartExploring = {
                                feedRepository.seedOrExplorePosts()
                                Toast.makeText(context, "Welcome! Loaded fresh posts for you to explore.", Toast.LENGTH_SHORT).show()
                            },
                            onRefresh = {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    feedRepository.refreshPosts()
                                    isRefreshing = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        )
                    }
                } else {
                    // List of Free / Paid Feed Posts
                    items(posts, key = { it.id }) { post ->
                        InstagramFeedCard(
                            post = post,
                            onPostClick = { onNavigateToPostView(post.id) },
                            onLikeClick = { feedRepository.toggleLike(post.id) },
                            onCommentClick = { activeCommentPostId = post.id },
                            onPayAndWatchClick = { onNavigateToPaymentOverview(post.id) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }

        // Bottom sheet for comments if opened
        activeCommentPostId?.let { postId ->
            val targetPost = posts.find { it.id == postId }
            if (targetPost != null) {
                CommentsBottomSheet(
                    post = targetPost,
                    onDismiss = { activeCommentPostId = null },
                    onAddComment = { commentText ->
                        val newComment = PostComment(
                            authorId = userProfile.id.ifBlank { "user_me" },
                            authorName = userProfile.name.ifBlank { "You" },
                            authorAvatarUrl = userProfile.avatarUri,
                            text = commentText.trim(),
                            timestamp = System.currentTimeMillis()
                        )
                        feedRepository.addComment(postId, newComment)
                    }
                )
            }
        }

        // Payment Processing Dialog Indicator
        if (isPaymentProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = AccentGreen,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Securing payment checkout...",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111B21)
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Top Stories & Creator Row
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
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5E9EC)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "My Status",
                            tint = Color.Gray,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(AccentGreen)
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Your Story", fontSize = 12.sp, color = Color(0xFF111B21), fontWeight = FontWeight.Medium)
            }

            // Creator highlights
            val creators = listOf(
                Pair("Akash", 0xFF008069),
                Pair("Sarah", 0xFFE65100),
                Pair("Maya", 0xFF00A884),
                Pair("Dev", 0xFF1976D2)
            )

            creators.forEach { (name, colorLong) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .border(2.5.dp, Brush.sweepGradient(listOf(AccentGreen, Color(0xFF00C6FF), AccentGreen)), CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Color(colorLong)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(name, fontSize = 12.sp, color = Color(0xFF111B21))
                }
            }

            // Quick explore / demo toggle item
            if (onToggleEmptyStateDemo != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onToggleEmptyStateDemo() }
                        .testTag("feed_stories_explore_btn")
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .border(2.dp, Brush.sweepGradient(listOf(AccentGreen, Color(0xFF00C6FF), AccentGreen)), CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE8F5E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = "Explore",
                            tint = AccentGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (hasPosts) "Explore" else "Discover",
                        fontSize = 12.sp,
                        color = Color(0xFF111B21),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Divider(color = Color(0xFFEFEFEF), thickness = 0.8.dp, modifier = Modifier.padding(top = 10.dp))
    }
}

// ----------------------------------------------------------------------------
// Friendly Empty State UI for Feed Page
// ----------------------------------------------------------------------------
@Composable
fun FeedEmptyState(
    onStartExploring: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("feed_empty_state_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Friendly illustration card
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF7FBF8),
            shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0EFE5)),
            modifier = Modifier
                .size(240.dp)
                .testTag("feed_empty_state_illustration")
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_empty_feed),
                    contentDescription = "Friendly explorer illustration for empty feed",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "No Posts Available Yet",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111B21),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your feed is quiet right now. Discover exciting posts, creator updates, and vibrant media waiting in the community!",
            fontSize = 14.sp,
            color = Color(0xFF667781),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 'Start Exploring' Button
        Button(
            onClick = onStartExploring,
            colors = ButtonDefaults.buttonColors(
                containerColor = TriggerFabGreen,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.dp,
                pressedElevation = 1.dp
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 13.dp),
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .height(50.dp)
                .testTag("start_exploring_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Start Exploring",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(
            onClick = onRefresh,
            colors = ButtonDefaults.textButtonColors(contentColor = TriggerFabGreen),
            modifier = Modifier.testTag("pull_to_refresh_hint_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Pull down to refresh feed",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Instagram Model 4:5 Media Card with Like, Comment, Description, Paid Blur
// ----------------------------------------------------------------------------
@Composable
fun InstagramFeedCard(
    post: FeedPost,
    onPostClick: () -> Unit = {},
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onPayAndWatchClick: () -> Unit
) {
    val isPaidLocked = post.postType == PostType.PAID && !post.isUnlocked

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("feed_card_${post.id}"),
        shape = RoundedCornerShape(0.dp), // Instagram flat card style
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Author Avatar, Username, Type Badge, More icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPostClick() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Author Avatar
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE1E5E8)),
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
                            color = BrandGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.authorName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = Color(0xFF111B21)
                        )
                        if (post.postType == PostType.PAID) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (post.isUnlocked) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (post.isUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = if (post.isUnlocked) AccentGreen else Color(0xFFE65100),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (post.isUnlocked) "UNLOCKED" else "PAID ${post.currency.take(3)} ${post.priceAmount.toInt()}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (post.isUnlocked) AccentGreen else Color(0xFFE65100)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "@${post.authorUsername.ifBlank { "creator" }} • ${formatRelativeTime(post.timestamp)}",
                        fontSize = 12.sp,
                        color = Color(0xFF667781)
                    )
                }

                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More",
                        tint = Color(0xFF667781),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ----------------------------------------------------------------
            // Media View: 4:5 Aspect Ratio (Images) or 9:16 (Video)
            // with 85% Blur & "Pay and Watch" Button if Paid & Locked
            // ----------------------------------------------------------------
            val mediaAspectRatio = if (post.mediaType == PostMediaType.VIDEO) 9f / 16f else 4f / 5f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(mediaAspectRatio)
                    .background(Color.Black)
                    .clickable { onPostClick() },
                contentAlignment = Alignment.Center
            ) {
                // Background Media Rendering
                if (post.mediaType == PostMediaType.IMAGE) {
                    if (post.mediaUrls.size > 1) {
                        val pagerState = rememberPagerState(pageCount = { post.mediaUrls.size })
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isPaidLocked) Modifier.blur(26.dp) else Modifier) // 85% visual blur
                        ) { page ->
                            AsyncImage(
                                model = post.mediaUrls[page],
                                contentDescription = "Slide $page",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Multiple photos badge indicator
                        if (!isPaidLocked) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "${pagerState.currentPage + 1}/${post.mediaUrls.size}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    } else {
                        val url = post.mediaUrls.firstOrNull() ?: ""
                        AsyncImage(
                            model = url,
                            contentDescription = "Post Media",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isPaidLocked) Modifier.blur(26.dp) else Modifier)
                        )
                    }
                } else {
                    // Video Media
                    if (isPaidLocked) {
                        // Blurred video poster
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(26.dp)
                                .background(Color(0xFF1B242B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayCircle,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    } else {
                        val videoUrl = post.mediaUrls.firstOrNull() ?: ""
                        FeedVideoPlayer(videoUrl = videoUrl)
                    }
                }

                // ------------------------------------------------------------
                // 85% Blur Security Layer Overlay with "Pay and Watch" CTA
                // ------------------------------------------------------------
                if (isPaidLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.60f))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Locked Content",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Exclusive Locked Content",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "This post has been protected with an 85% security blur. Unlock full high-res media and comments.",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.5.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            // "Pay and Watch" Button
                            Button(
                                onClick = onPayAndWatchClick,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier.testTag("pay_and_watch_button")
                            ) {
                                Icon(Icons.Filled.Payment, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Pay and Watch (${post.currency.take(3)} ${post.priceAmount})",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // Action Buttons Row: Like, Comment, Share
            // ----------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Like Button
                IconButton(onClick = onLikeClick) {
                    Icon(
                        imageVector = if (post.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (post.isLikedByMe) Color(0xFFE91E63) else Color(0xFF111B21),
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Comment Button
                IconButton(onClick = onCommentClick) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "Comment",
                        tint = Color(0xFF111B21),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Share Button
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share",
                        tint = Color(0xFF111B21),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bookmark / Save
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Filled.BookmarkBorder,
                        contentDescription = "Save",
                        tint = Color(0xFF111B21),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Likes count
            if (post.likesCount > 0) {
                Text(
                    text = "${post.likesCount} ${if (post.likesCount == 1) "like" else "likes"}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = Color(0xFF111B21),
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }

            // Description / Caption
            if (post.description.isNotBlank()) {
                var isExpanded by remember { mutableStateOf(false) }
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${post.authorName} ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = Color(0xFF111B21)
                        )
                        Text(
                            text = post.description,
                            fontSize = 13.5.sp,
                            color = Color(0xFF222B32),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isExpanded = !isExpanded }
                        )
                    }
                    if (post.description.length > 80 && !isExpanded) {
                        Text(
                            text = "more",
                            color = Color(0xFF8696A0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { isExpanded = true }
                        )
                    }
                }
            }

            // Comments Preview link
            if (post.commentsCount > 0) {
                Text(
                    text = "View all ${post.commentsCount} ${if (post.commentsCount == 1) "comment" else "comments"}",
                    color = Color(0xFF8696A0),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clickable { onCommentClick() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = Color(0xFFF0F2F5), thickness = 6.dp)
        }
    }
}

@Composable
fun FeedVideoPlayer(videoUrl: String) {
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

// ----------------------------------------------------------------------------
// Comments Bottom Sheet
// ----------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    post: FeedPost,
    onDismiss: () -> Unit,
    onAddComment: (String) -> Unit
) {
    var newCommentText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Comments (${post.comments.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color(0xFF111B21),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
            )
            Divider(color = Color(0xFFEFEFEF))

            // Comments list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (post.comments.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No comments yet", color = Color(0xFF667781), fontSize = 14.sp)
                            Text("Be the first to comment on this post!", color = Color(0xFF8696A0), fontSize = 12.sp)
                        }
                    }
                } else {
                    items(post.comments) { comment ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE5E9EC)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = comment.authorName.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = BrandGreen
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = comment.authorName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF111B21)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatRelativeTime(comment.timestamp),
                                        fontSize = 11.sp,
                                        color = Color(0xFF8696A0)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = comment.text,
                                    fontSize = 13.5.sp,
                                    color = Color(0xFF222B32)
                                )
                            }
                        }
                    }
                }
            }

            // Input box row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newCommentText,
                    onValueChange = { newCommentText = it },
                    placeholder = { Text("Add a comment...", fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_comment_input"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        focusedLabelColor = AccentGreen
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newCommentText.isNotBlank()) {
                            onAddComment(newCommentText)
                            newCommentText = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (newCommentText.isNotBlank()) AccentGreen else Color(0xFFE0E0E0))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
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
