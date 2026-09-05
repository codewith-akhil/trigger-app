package com.example.ui.screens

import android.view.SurfaceView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.di.AppServiceContainer
import com.example.service.LiveStreamRole
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveStreamPlayerScreen(
    onDismiss: () -> Unit
) {
    val liveStreamService = AppServiceContainer.liveStreamService
    val currentStreamState by liveStreamService.currentStreamState.collectAsState()
    val engineState by AppServiceContainer.agoraRtcEngineManager.engineState.collectAsState()

    val stream = currentStreamState.stream
    val isHost = currentStreamState.role == LiveStreamRole.HOST

    var commentInput by remember { mutableStateOf("") }
    var showEndConfirmDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val commentsListState = rememberLazyListState()

    // Scroll to latest comment when comments update
    LaunchedEffect(currentStreamState.comments.size) {
        if (currentStreamState.comments.isNotEmpty()) {
            commentsListState.animateScrollToItem(currentStreamState.comments.size - 1)
        }
    }

    if (stream == null) {
        // Loading / unavailable stream placeholder — must still emit a node tree
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("live_stream_player_loading"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF25D366), strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Loading live stream…",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    } else {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("live_stream_player")
    ) {
        // 1. VIDEO LAYER
        Box(modifier = Modifier.fillMaxSize()) {
            if (isHost) {
                // Host local camera feed
                if (currentStreamState.isVideoEnabled) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                AppServiceContainer.agoraRtcEngineManager.setupLocalVideo(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1C2833)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Camera turned off", color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            } else {
                // Audience remote host video feed
                val remoteUid = engineState.remoteUid
                if (remoteUid != null) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                AppServiceContainer.agoraRtcEngineManager.setupRemoteVideo(this, remoteUid)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF25D366), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Connecting to live broadcast…",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Subtle gradient overlay for readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            // Error overlay — surfaces `currentStreamState.errorMessage` when
            // Agora join fails or `sendComment` errors (was previously never
            // displayed to the user).
            currentStreamState.errorMessage?.let { errMsg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Stream Error",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errMsg,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                onClick = {
                                    // Retry: leave + rejoin the same stream.
                                    coroutineScope.launch {
                                        val stream2 = currentStreamState.stream
                                        if (stream2 != null) {
                                            liveStreamService.leaveLiveStream()
                                            liveStreamService.joinLiveStream(stream2)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = Color(0xFF25D366).copy(alpha = 0.18f),
                                    contentColor = Color(0xFF25D366)
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    liveStreamService.leaveLiveStream()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Leave", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. TOP HEADER
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Streamer profile badge
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.45f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF008069)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stream.streamerName.take(1),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = stream.streamerName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stream.category,
                            color = Color(0xFF25D366),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // LIVE Badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFE53935)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Viewer count badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${stream.viewerCount}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Close button
            IconButton(
                onClick = {
                    if (isHost) {
                        showEndConfirmDialog = true
                    } else {
                        liveStreamService.leaveLiveStream()
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Exit", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        // 3. BOTTOM OVERLAY (Comments & Controls)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Stream Title
            Text(
                text = stream.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Live comments feed (fade over background)
            LazyColumn(
                state = commentsListState,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(currentStreamState.comments, key = { it.id }) { comment ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${comment.userName}: ",
                                color = Color(0xFF25D366),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = comment.message,
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action controls bar
            if (isHost) {
                // Host broadcast toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(
                            onClick = { liveStreamService.toggleMute() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (currentStreamState.isMuted) Color(0xFFE53935) else Color.White.copy(alpha = 0.25f))
                        ) {
                            Icon(
                                imageVector = if (currentStreamState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = "Mute",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { liveStreamService.toggleVideo() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (!currentStreamState.isVideoEnabled) Color(0xFFE53935) else Color.White.copy(alpha = 0.25f))
                        ) {
                            Icon(
                                imageVector = if (currentStreamState.isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                contentDescription = "Video",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { liveStreamService.switchCamera() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                        ) {
                            Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Switch", tint = Color.White)
                        }
                    }

                    Button(
                        onClick = { showEndConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("End Stream", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            } else {
                // Audience input & reactions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        placeholder = { Text("Say something…", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.5f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (commentInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        val text = commentInput
                                        commentInput = ""
                                        coroutineScope.launch {
                                            liveStreamService.sendComment(text)
                                        }
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(0xFF25D366))
                                }
                            }
                        }
                    )

                    // Reaction Heart Button
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                liveStreamService.sendReaction()
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935).copy(alpha = 0.85f))
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Heart", tint = Color.White)
                    }
                }
            }
        }
    }

    // Host End Stream Confirmation Dialog
    if (showEndConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEndConfirmDialog = false },
            title = { Text("End Live Broadcast?") },
            text = { Text("Ending the broadcast will close the stream for all viewers.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndConfirmDialog = false
                        liveStreamService.leaveLiveStream()
                        onDismiss()
                    }
                ) {
                    Text("End Broadcast", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    }
}
