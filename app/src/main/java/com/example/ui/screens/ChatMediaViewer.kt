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
import com.example.ui.components.TriggerAlertDialog
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
import androidx.media3.common.Player
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
    // View-once mode: the viewer becomes a locked, view-only surface — no
    // star, no share (that would literally export the bytes via FileProvider),
    // no delete, no reactions, no reply. Exactly like WhatsApp's spartan
    // view-once screen.
    val viewOnce = message.isViewOnce

    var isStarred by remember { mutableStateOf(message.isStarred) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
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
                .padding(top = 64.dp, bottom = if (message.text.isNotBlank()) 56.dp else 16.dp)
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
                    thumbnailUrl = message.mediaThumbnail,
                    mediaBucket = message.mediaBucket,
                    mediaPath = message.mediaPath,
                    // Phase 3: prefer the durable archive copy for playback.
                    localMediaPath = message.localMediaPath,
                    isViewOnce = viewOnce
                )
            } else {
                // Image viewer with pinch to zoom
                // Phase 3 file-first: the durable on-device archive copy loads
                // straight from disk when present (instant, offline-safe);
                // otherwise the existing signed/public URL pipeline runs.
                // VIEW-ONCE keeps its own branch FIRST and untouched: it only
                // ever loads the URL with both Coil caches disabled — and
                // view-once rows never carry an archive anyway.
                val localArchive = remember(message.localMediaPath) { archivedMediaFile(message) }
                if (localArchive != null || !message.mediaUrl.isNullOrEmpty() || !message.mediaThumbnail.isNullOrEmpty()) {
                    AsyncImage(
                        // Task 24: stable bucket/path cache keys for private media.
                        // View-once: bypass Coil's memory AND disk caches entirely
                        // so the decrypted image never outlives the viewing
                        // session on the recipient's device.
                        model = if (viewOnce) {
                            coil.request.ImageRequest.Builder(LocalContext.current)
                                .data(message.mediaUrl ?: message.mediaThumbnail)
                                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                                .build()
                        } else if (localArchive != null) {
                            localArchive
                        } else {
                            com.example.ui.screens.privateMediaModel(
                                message.mediaBucket,
                                message.mediaUrl ?: message.mediaThumbnail,
                                message.mediaPath
                            )
                        },
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

            if (!viewOnce) {
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

                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color.White
                    )
                }
            }
        }

        // Optional Caption text (if present)
        if (message.text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 14.5.sp
                )
            }
        }

        // Delete confirmation dialog
        if (showDeleteConfirmDialog) {
            TriggerAlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = "Delete media?",
                        color = Color(0xFF111B21),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete this media? This action cannot be undone.",
                        color = Color(0xFF667781),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDelete()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335))
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel", color = Color(0xFFAD1457), fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    }
}

/**
 * Real video playback — media3 ExoPlayer driving a PlayerView with the
 * standard transport controls (play/pause, seek, real duration/position).
 *
 * Accepts ANY source: https(s) server URLs, content:// (the sender's fresh
 * upload — the bubble carries the local URI while SENDING), file:// and plain
 * absolute paths. Previously the player was gated on `startsWith("http")`, so
 * the sender's own video during upload rendered as a blank AsyncImage (Coil
 * cannot decode a video URL).
 */
@Composable
private fun RealVideoPlayer(
    mediaUrl: String?,
    thumbnailUrl: String?,
    mediaBucket: String? = null,
    mediaPath: String? = null,
    // Phase 3 media persistence: durable on-device archive copy — preferred
    // playback source when the file exists. Null for view-once (never archived).
    localMediaPath: String? = null,
    isViewOnce: Boolean = false
) {
    val context = LocalContext.current
    var playbackError by remember(mediaUrl) { mutableStateOf<String?>(null) }
    var retryTick by remember(mediaUrl) { mutableIntStateOf(0) }

    // Phase 3: the durable archive copy wins when it actually exists —
    // instant local playback, no signed-URL round-trip, works offline.
    val localArchive = remember(localMediaPath) {
        localMediaPath?.takeIf { it.isNotBlank() }?.let { p ->
            java.io.File(p).takeIf { it.exists() && it.length() > 0L }
        }
    }

    // Task 24: signed URLs (1 h TTL) can expire between Room read and play.
    // Every Retry press force-mints a fresh signature before the player is
    // rebuilt (remember(effectiveUrl, retryTick)) — but only when there is no
    // local archive to play instead.
    var effectiveUrl by remember(mediaUrl, localMediaPath) {
        mutableStateOf(localArchive?.absolutePath ?: mediaUrl)
    }
    LaunchedEffect(mediaUrl, retryTick) {
        if (retryTick > 0 && localArchive == null && mediaUrl != null && mediaUrl.startsWith("http") &&
            com.example.service.MediaUrlResolver.isPrivateBucket(mediaBucket)
        ) {
            effectiveUrl = com.example.service.MediaUrlResolver.resolveWithRefresh(
                mediaUrl, mediaBucket, mediaPath, forceRefresh = true
            ) ?: mediaUrl
        }
    }

    val player = remember(effectiveUrl, retryTick) {
        effectiveUrl?.let { url ->
            try {
                val uri = when {
                    url.startsWith("content://") || url.startsWith("file://") ||
                        url.startsWith("http://") || url.startsWith("https://") ->
                        android.net.Uri.parse(url)
                    else -> android.net.Uri.fromFile(java.io.File(url))
                }
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    playWhenReady = false
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            playbackError = error.localizedMessage ?: "Video can't be played"
                        }
                    })
                    prepare()
                }
            } catch (e: Exception) {
                playbackError = e.localizedMessage ?: "Video can't be played"
                null
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
        when {
            // Inline error state with retry — previously a player failure was
            // completely silent (blank surface).
            playbackError != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = "Video error",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = playbackError ?: "Video can't be played",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 48dp target — re-creates the player (remember key tick).
                    OutlinedButton(
                        onClick = {
                            playbackError = null
                            retryTick++
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry", fontSize = 13.sp)
                    }
                }
            }

            player != null -> {
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
            }

            else -> {
                if (!thumbnailUrl.isNullOrEmpty() || !mediaUrl.isNullOrEmpty()) {
                    AsyncImage(
                        // Task 24: stable bucket/path cache keys for the poster.
                        // View-once: the poster frame must not land in Coil
                        // memory/disk caches either.
                        model = if (isViewOnce) {
                            coil.request.ImageRequest.Builder(context)
                                .data(thumbnailUrl ?: mediaUrl)
                                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                                .build()
                        } else {
                            com.example.ui.screens.privateMediaModel(
                                mediaBucket, thumbnailUrl ?: mediaUrl, mediaPath
                            )
                        },
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
}
