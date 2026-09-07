package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.model.DomainMessage
import com.example.model.MessageType
import com.example.ui.theme.TriggerFabGreen

@Composable
fun ChatMediaViewer(
    message: DomainMessage,
    onClose: () -> Unit,
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {},
    // Wired to the VM — the quick-emoji row and reply bar were silent
    // no-ops (they only closed the viewer) while the UI docs claim they work.
    onReact: (String) -> Unit = {},
    onSendReply: (String) -> Unit = {}
) {
    var isStarred by remember { mutableStateOf(message.isStarred) }
    var replyText by remember { mutableStateOf("") }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("media_viewer")
    ) {
        // Center Media Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = 120.dp)
                .pointerInput(Unit) {
                    if (message.type != MessageType.VIDEO) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (message.type == MessageType.VIDEO) {
                RealVideoPlayer(
                    mediaUrl = message.mediaUrl,
                    thumbnailUrl = message.mediaThumbnail
                )
            } else {
                // Image viewer with pinch to zoom
                if (!message.mediaUrl.isNullOrEmpty() || !message.mediaThumbnail.isNullOrEmpty()) {
                    AsyncImage(
                        model = message.mediaUrl ?: message.mediaThumbnail,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = "Media unavailable",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        // Top app bar (WhatsApp style with black 60% translucent scrim)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(64.dp)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isOutgoing) "You" else message.senderName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${message.timestamp} ${if (message.isViewOnce) "• View once" else ""}",
                    color = Color(0xFF8696A0),
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = { isStarred = !isStarred }) {
                Icon(
                    imageVector = if (isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Star",
                    tint = if (isStarred) Color(0xFFFFC107) else Color.White
                )
            }

            IconButton(onClick = {
                onShare()
            }) {
                Icon(
                    imageVector = Icons.Filled.Forward,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = Color.White
                )
            }
        }

        // Bottom section: Caption + Quick Reactions + Reply bar (Screenshots 6 & 7)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Lift the reply bar above the IME — previously the keyboard
                // covered it while typing.
                .imePadding()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Optional Caption text
            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
            }

            // Quick emoji reactions row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val quickEmojis = listOf("❤️", "😂", "😮", "😢", "🙏", "👏")
                quickEmojis.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                onReact(emoji)
                                onClose()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }
            }

            // Reply input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1F2C34))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.SentimentSatisfied,
                    contentDescription = "Emoji",
                    tint = Color(0xFF8696A0),
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (replyText.isEmpty()) {
                        Text(
                            text = "Reply...",
                            color = Color(0xFF8696A0),
                            fontSize = 15.sp
                        )
                    }
                    BasicTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(TriggerFabGreen),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (replyText.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(TriggerFabGreen)
                            .clickable {
                                if (replyText.isNotBlank()) {
                                    onSendReply(replyText)
                                    onClose()
                                }
                            },
                        contentAlignment = Alignment.Center
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
}

/**
 * Real video playback — media3 ExoPlayer driving a PlayerView with the
 * standard transport controls (play/pause, seek, real duration/position).
 */
@Composable
private fun RealVideoPlayer(
    mediaUrl: String?,
    thumbnailUrl: String?
) {
    val context = LocalContext.current
    val player = remember(mediaUrl) {
        mediaUrl?.takeIf { it.startsWith("http") }?.let { url ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = false
                prepare()
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF1F2C34)),
        contentAlignment = Alignment.Center
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        setShowSubtitleButton(false)
                        controllerShowTimeoutMs = 0 // keep controls visible
                        this.player = player
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            if (!thumbnailUrl.isNullOrEmpty() || !mediaUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = thumbnailUrl ?: mediaUrl,
                    contentDescription = "Video Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = "Video unavailable",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}
