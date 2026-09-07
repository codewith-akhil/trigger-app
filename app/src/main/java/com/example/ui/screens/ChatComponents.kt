package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.config.ChatConfig
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun DateSeparator(dateText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFE1F5FE).copy(alpha = 0.9f),
            shadowElevation = 0.5.dp
        ) {
            Text(
                text = dateText,
                color = Color(0xFF54656F),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun SystemMessageBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFFFF9C4).copy(alpha = 0.92f),
            shadowElevation = 0.5.dp
        ) {
            Text(
                text = text,
                color = Color(0xFF5D4037),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
fun ChatTicksIcon(
    status: MessageStatus,
    onRetry: () -> Unit = {}
) {
    when (status) {
        MessageStatus.SENDING -> {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = Color(0xFF8696A0),
                strokeWidth = 1.2.dp
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Sent",
                tint = Color(0xFF8696A0),
                modifier = Modifier.size(14.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                tint = Color(0xFF8696A0),
                modifier = Modifier.size(15.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Read",
                tint = WhatsAppCheckmarkBlue,
                modifier = Modifier.size(15.dp)
            )
        }
        MessageStatus.FAILED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onRetry)
            ) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Failed. Tap to retry",
                    tint = Color(0xFFEA4335),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun ChatTicksOverlayIcon(
    status: MessageStatus,
    onRetry: () -> Unit = {}
) {
    when (status) {
        MessageStatus.SENDING -> {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                color = Color.White,
                strokeWidth = 1.2.dp
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Sent",
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Read",
                tint = Color(0xFF53BDEB),
                modifier = Modifier.size(14.dp)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Failed. Tap to retry",
                tint = Color(0xFFFF5252),
                modifier = Modifier
                    .size(13.dp)
                    .clickable(onClick = onRetry)
            )
        }
    }
}

@Composable
fun MessageReactionBar(
    onReactionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val emojis = listOf("❤️", "😂", "👍", "😮", "😢", "🙏")

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            emojis.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onReactionSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "More emojis",
                tint = Color(0xFF8696A0),
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onReactionSelected("🔥") }
            )
        }
    }
}

@Composable
fun QuotedReplyPreview(
    replySender: String,
    replyText: String,
    onClick: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.05f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .background(WhatsAppFabGreen)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = replySender,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = WhatsAppChatTeal,
                    maxLines = 1
                )
                Text(
                    text = replyText,
                    fontSize = 12.sp,
                    color = Color(0xFF54656F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun VoiceMessageBubbleContent(
    durationSec: Int,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Play / Pause Circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(WhatsAppFabGreen)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Audio waveform representation
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val barHeights = listOf(6, 12, 18, 10, 14, 8, 16, 20, 12, 6, 14, 18, 10, 8, 16, 12, 6, 14)
                barHeights.forEachIndexed { i, h ->
                    val isPast = (i.toFloat() / barHeights.size) <= progress
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(h.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (isPast) WhatsAppFabGreen else Color(0xFF8696A0).copy(alpha = 0.5f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            val currentMins = (durationSec * progress).toInt() / 60
            val currentSecs = (durationSec * progress).toInt() % 60
            Text(
                text = if (isPlaying) String.format("%d:%02d", currentMins, currentSecs)
                else String.format("%d:%02d", durationSec / 60, durationSec % 60),
                fontSize = 11.sp,
                color = Color(0xFF667781)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Mic avatar badge
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFFE1F5FE)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = WhatsAppFabGreen,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun UploadProgressBanner(
    uploadTask: UploadTask?,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    if (uploadTask == null) return

    val progressFraction = (uploadTask.uploadedBytes.toFloat() / uploadTask.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val percentage = (progressFraction * 100).toInt()
    val speedMb = uploadTask.speedBytesPerSec / (1024.0 * 1024.0)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = WhatsAppFabGreen,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = if (uploadTask.isFailed) onRetry else onCancel,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = if (uploadTask.isFailed) Icons.Filled.Refresh else Icons.Filled.Close,
                        contentDescription = "Action",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (uploadTask.isFailed) "Upload failed • Tap retry"
                else "Uploading $percentage% • ${ChatConfig.formatFileSize(uploadTask.uploadedBytes)} / ${ChatConfig.formatFileSize(uploadTask.totalBytes)} • ${"%.1f".format(speedMb)} MB/s • ${uploadTask.remainingSeconds}s remaining",
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MessageTimestampAndTicksRow(
    message: DomainMessage,
    isOutgoing: Boolean,
    onRetryUpload: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFF667781)
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (message.isPinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = textColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        if (message.isStarred) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Starred",
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = message.timestamp,
            color = textColor,
            fontSize = 11.sp,
            letterSpacing = 0.2.sp
        )
        if (message.editedAt != null) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "edited",
                color = textColor,
                fontSize = 10.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }

        if (isOutgoing) {
            Spacer(modifier = Modifier.width(4.dp))
            ChatTicksIcon(
                status = message.status,
                onRetry = onRetryUpload
            )
        }
    }
}

private fun formatMediaDuration(durationSec: Int): String {
    val mins = durationSec / 60
    val secs = durationSec % 60
    return String.format("%d:%02d", mins, secs)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DomainChatBubble(
    message: DomainMessage,
    isSelected: Boolean,
    uploadTask: UploadTask?,
    isPlayingAudio: Boolean,
    audioProgress: Float,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    onTogglePlayAudio: () -> Unit,
    onOpenMediaViewer: () -> Unit,
    onOpenViewOnce: () -> Unit,
    onCancelUpload: () -> Unit,
    onRetryUpload: () -> Unit,
    onReactionClick: (String) -> Unit
) {
    val isOutgoing = message.isOutgoing
    val isPureMedia = (message.type == MessageType.IMAGE || message.type == MessageType.VIDEO) &&
        !message.isViewOnce && !message.isDeletedForEveryone && message.text.isBlank()
    val isCaptionedMedia = (message.type == MessageType.IMAGE || message.type == MessageType.VIDEO) &&
        !message.isViewOnce && !message.isDeletedForEveryone && message.text.isNotBlank()
    val isMediaOrDoc = isPureMedia || isCaptionedMedia || message.type == MessageType.DOCUMENT

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 2.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(topStart = 2.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFF00A884).copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
        ) {
            // WhatsApp Forward/Share button for outgoing media (placed to the LEFT of bubble)
            if (isOutgoing && isMediaOrDoc) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.08f))
                        .clickable(onClick = onOpenMediaViewer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward,
                        contentDescription = "Forward",
                        tint = Color(0xFF54656F),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // Chat Bubble Surface
            Surface(
                shape = bubbleShape,
                color = if (isOutgoing) TriggerBubbleOutgoing else TriggerBubbleIncoming,
                shadowElevation = 0.8.dp,
                modifier = Modifier
                    .widthIn(min = if (isPureMedia) 200.dp else 70.dp, max = if (isPureMedia) 270.dp else 305.dp)
                    .combinedClickable(
                        onClick = {
                            if (message.status == MessageStatus.FAILED) {
                                onRetryUpload()
                            } else {
                                onClick()
                            }
                        },
                        onLongClick = onLongPress
                    )
                    .testTag("chat_bubble_${message.id}")
            ) {
                when {
                    // Deleted message
                    message.isDeletedForEveryone -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Block,
                                contentDescription = null,
                                tint = Color(0xFF667781),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "This message was deleted",
                                color = Color(0xFF667781),
                                fontSize = 13.5.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message.timestamp,
                                color = Color(0xFF8696A0),
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    // View Once media pill
                    message.isViewOnce -> {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !message.isViewed, onClick = onOpenViewOnce)
                                    .background(if (message.isViewed) Color.Black.copy(alpha = 0.05f) else Color(0xFFE8F5E9))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(if (message.isViewed) Color(0xFF8696A0) else TriggerFabGreen),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "1",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (message.isViewed) "Opened" else "Photo • View once",
                                    color = if (message.isViewed) Color(0xFF8696A0) else Color(0xFF111B21),
                                    fontSize = 14.sp,
                                    fontWeight = if (message.isViewed) FontWeight.Normal else FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            MessageTimestampAndTicksRow(
                                message = message,
                                isOutgoing = isOutgoing,
                                onRetryUpload = onRetryUpload,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    // Pure Media: WhatsApp tight image/video card (no oversized padding/borders)
                    isPureMedia -> {
                        val mediaInnerShape = if (isOutgoing) {
                            RoundedCornerShape(topStart = 11.dp, topEnd = 2.dp, bottomStart = 11.dp, bottomEnd = 11.dp)
                        } else {
                            RoundedCornerShape(topStart = 2.dp, topEnd = 11.dp, bottomStart = 11.dp, bottomEnd = 11.dp)
                        }

                        Box(
                            modifier = Modifier
                                .padding(3.dp)
                                .widthIn(min = 220.dp, max = 265.dp)
                                .heightIn(min = 190.dp, max = 340.dp)
                                .clip(mediaInnerShape)
                                .clickable(onClick = onOpenMediaViewer)
                        ) {
                            // Media image or fallback
                            if (!message.mediaUrl.isNullOrEmpty() || !message.mediaThumbnail.isNullOrEmpty()) {
                                AsyncImage(
                                    model = message.mediaUrl ?: message.mediaThumbnail,
                                    contentDescription = "Media",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF2A2A2A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = if (message.type == MessageType.VIDEO)
                                                Icons.Filled.Videocam else Icons.Filled.Image,
                                            contentDescription = "Media unavailable",
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = if (message.status == MessageStatus.FAILED)
                                                "Upload failed" else "Loading...",
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            // Centered Play button for video
                            if (message.type == MessageType.VIDEO) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Play Video",
                                        tint = Color.White,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }

                            // Bottom-left video duration pill
                            if (message.type == MessageType.VIDEO) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 6.dp, bottom = 6.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Videocam,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = formatMediaDuration(message.mediaDurationSec.coerceAtLeast(7)),
                                            color = Color.White,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            // Bottom-right overlay pill: timestamp & ticks directly inside image
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 6.dp, bottom = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (message.isStarred) {
                                        Icon(
                                            imageVector = Icons.Filled.Star,
                                            contentDescription = "Starred",
                                            tint = Color(0xFFFFC107),
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                    }
                                    Text(
                                        text = message.timestamp,
                                        color = Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (isOutgoing) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        ChatTicksOverlayIcon(
                                            status = message.status,
                                            onRetry = onRetryUpload
                                        )
                                    }
                                }
                            }

                            // Upload banner
                            if (uploadTask != null && !uploadTask.isCompleted) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                ) {
                                    UploadProgressBanner(
                                        uploadTask = uploadTask,
                                        onCancel = onCancelUpload,
                                        onRetry = onRetryUpload
                                    )
                                }
                            }
                        }
                    }

                    // Media WITH Caption
                    isCaptionedMedia -> {
                        Column(modifier = Modifier.padding(3.dp)) {
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 220.dp, max = 265.dp)
                                    .heightIn(min = 160.dp, max = 280.dp)
                                    .clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                    .clickable(onClick = onOpenMediaViewer)
                            ) {
                                if (!message.mediaUrl.isNullOrEmpty() || !message.mediaThumbnail.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = message.mediaUrl ?: message.mediaThumbnail,
                                        contentDescription = "Media",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF2A2A2A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (message.type == MessageType.VIDEO)
                                                Icons.Filled.Videocam else Icons.Filled.Image,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                if (message.type == MessageType.VIDEO) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "Play Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = message.text,
                                color = Color(0xFF111B21),
                                fontSize = 14.5.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )

                            MessageTimestampAndTicksRow(
                                message = message,
                                isOutgoing = isOutgoing,
                                onRetryUpload = onRetryUpload,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(end = 4.dp, bottom = 2.dp)
                            )
                        }
                    }

                    // Voice / Audio message
                    message.type == MessageType.AUDIO -> {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            if (message.replyToText != null) {
                                QuotedReplyPreview(
                                    replySender = message.replyToSender ?: "Sender",
                                    replyText = message.replyToText
                                )
                            }
                            VoiceMessageBubbleContent(
                                durationSec = message.mediaDurationSec.coerceAtLeast(1),
                                isPlaying = isPlayingAudio,
                                progress = audioProgress,
                                onTogglePlay = onTogglePlayAudio
                            )
                            MessageTimestampAndTicksRow(
                                message = message,
                                isOutgoing = isOutgoing,
                                onRetryUpload = onRetryUpload,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    // Document message
                    message.type == MessageType.DOCUMENT -> {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            if (message.replyToText != null) {
                                QuotedReplyPreview(
                                    replySender = message.replyToSender ?: "Sender",
                                    replyText = message.replyToText
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.05f))
                                    .padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF7F66FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.fileName ?: "Document.pdf",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF111B21),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ChatConfig.formatFileSize(message.fileSize.coerceAtLeast(1024 * 240)),
                                        fontSize = 11.sp,
                                        color = Color(0xFF667781)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            MessageTimestampAndTicksRow(
                                message = message,
                                isOutgoing = isOutgoing,
                                onRetryUpload = onRetryUpload,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    // Location message
                    message.type == MessageType.LOCATION -> {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Column(
                            modifier = Modifier
                                .padding(3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = message.locationLatitude != null && message.locationLongitude != null) {
                                    try {
                                        val uri = android.net.Uri.parse(
                                            "geo:${message.locationLatitude},${message.locationLongitude}" +
                                                "?q=${message.locationLatitude},${message.locationLongitude}" +
                                                "(${android.net.Uri.encode(message.locationAddress ?: "Shared location")})"
                                        )
                                        context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                        )
                                    } catch (_: Exception) {}
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .background(Color(0xFFC8E6C9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Filled.LocationOn,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    if (message.locationLiveMinutes != null) {
                                        Text(
                                            text = "Live location • ${message.locationLiveMinutes}m",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = message.locationAddress ?: "Current Location",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF111B21),
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            MessageTimestampAndTicksRow(
                                message = message,
                                isOutgoing = isOutgoing,
                                onRetryUpload = onRetryUpload,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(end = 6.dp, bottom = 4.dp)
                            )
                        }
                    }

                    // Contact message
                    message.type == MessageType.CONTACT -> {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF009DE2)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.contactName ?: "Contact",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF111B21)
                                    )
                                    Text(
                                        text = message.contactPhone ?: "",
                                        fontSize = 11.5.sp,
                                        color = Color(0xFF667781)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            MessageTimestampAndTicksRow(
                                message = message,
                                isOutgoing = isOutgoing,
                                onRetryUpload = onRetryUpload,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    // Call Log message (as seen in Screenshot 2)
                    message.type == MessageType.CALL_LOG -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE8F5E9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (message.callType?.contains("video", ignoreCase = true) == true)
                                        Icons.Filled.Videocam else Icons.Filled.Call,
                                    contentDescription = null,
                                    tint = TriggerFabGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = message.text.ifBlank { "Call" },
                                    color = Color(0xFF111B21),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = message.timestamp,
                                    color = Color(0xFF8696A0),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Standard Text message: tight, WhatsApp-style padding and inline/stacked timestamp
                    else -> {
                        val isShortSingle = message.text.length < 22 && !message.text.contains("\n") && message.replyToText == null
                        Column(
                            modifier = Modifier.padding(
                                start = 9.dp,
                                end = 9.dp,
                                top = 5.dp,
                                bottom = 5.dp
                            )
                        ) {
                            if (message.replyToText != null) {
                                QuotedReplyPreview(
                                    replySender = message.replyToSender ?: "Sender",
                                    replyText = message.replyToText
                                )
                            }

                            if (isShortSingle) {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = message.text,
                                        color = Color(0xFF111B21),
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp
                                    )
                                    MessageTimestampAndTicksRow(
                                        message = message,
                                        isOutgoing = isOutgoing,
                                        onRetryUpload = onRetryUpload
                                    )
                                }
                            } else {
                                Text(
                                    text = message.text,
                                    color = Color(0xFF111B21),
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                                MessageTimestampAndTicksRow(
                                    message = message,
                                    isOutgoing = isOutgoing,
                                    onRetryUpload = onRetryUpload,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }

                            // Tap to retry hint for FAILED messages
                            if (message.status == MessageStatus.FAILED) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Tap to retry",
                                    color = Color(0xFFEA4335),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // WhatsApp Forward/Share button for incoming media (placed to the RIGHT of bubble)
            if (!isOutgoing && isMediaOrDoc) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.08f))
                        .clickable(onClick = onOpenMediaViewer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward,
                        contentDescription = "Forward",
                        tint = Color(0xFF54656F),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Reactions badge pill attached to bubble corner
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(if (isOutgoing) Alignment.BottomEnd else Alignment.BottomStart)
                    .offset(y = 10.dp, x = if (isOutgoing) (-8).dp else 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(0.5.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                message.reactions.forEach { reaction ->
                    Text(
                        text = "${reaction.emoji} ${if (reaction.count > 1) reaction.count else ""}",
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onReactionClick(reaction.emoji) }
                    )
                }
            }
        }
    }
}
