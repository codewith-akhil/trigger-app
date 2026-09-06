package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DomainMessage
import com.example.model.MessageType
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale

@Composable
fun ChatMediaViewer(
    message: DomainMessage,
    onClose: () -> Unit,
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {}
) {
    // H7: REAL video playback via media3/ExoPlayer. Previously a static
    // thumbnail with a fake progress slider and hardcoded "0:14"/"0:42".

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("media_viewer")
    ) {
        // Top app bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.senderName,
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

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = Color.White
                )
            }

            IconButton(onClick = {
                onShare()
                onClose()
            }) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }
        }

        // Center Media Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 70.dp),
            contentAlignment = Alignment.Center
        ) {
            if (message.type == MessageType.VIDEO) {
                RealVideoPlayer(
                    mediaUrl = message.mediaUrl,
                    thumbnailUrl = message.mediaThumbnail
                )
            } else {
                // Image viewer
                if (!message.mediaUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = message.mediaUrl,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
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

        // Bottom caption bar if present
        if (message.text.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }
    }
}

/**
 * H7: real video playback — media3 ExoPlayer driving a PlayerView with the
 * standard transport controls (play/pause, seek, real duration/position).
 * The player is created once per message and released when the viewer closes
 * (DisposableEffect), so no decoder/audio session ever leaks.
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
            // No loadable URL — show the thumbnail (if any) or a broken icon.
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
