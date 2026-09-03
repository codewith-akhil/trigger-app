package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CallState
import com.example.model.CallType
import com.example.service.CallSession

@Composable
fun ChatCallingOverlay(
    session: CallSession,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    val isVideo = session.type == CallType.VIDEO && session.isVideoEnabled

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (isVideo) listOf(Color(0xFF0F1418), Color(0xFF0F1418))
                    else listOf(Color(0xFF0B141B), Color(0xFF1F2C34), Color(0xFF0B141B))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("calling_overlay")
    ) {
        // Video background if video enabled
        if (isVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E2830)),
                contentAlignment = Alignment.Center
            ) {
                if (session.avatarRes != null) {
                    Image(
                        painter = painterResource(id = session.avatarRes),
                        contentDescription = session.contactName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                )

                // Self view mini picture-in-picture in top corner
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 70.dp, end = 16.dp)
                        .size(width = 100.dp, height = 140.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black,
                    shadowElevation = 6.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF2A3942)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Self Preview",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (session.isFrontCamera) "Front" else "Rear",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }

        // Top Status Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color(0xFF8696A0),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "End-to-end encrypted",
                    color = Color(0xFF8696A0),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = session.contactName,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            val statusText = when (session.state) {
                CallState.CALLING -> "Calling..."
                CallState.RINGING -> "Ringing..."
                CallState.CONNECTING -> "Connecting..."
                CallState.CONNECTED -> {
                    val mins = session.durationSeconds / 60
                    val secs = session.durationSeconds % 60
                    String.format("%02d:%02d", mins, secs)
                }
                CallState.RECONNECTING -> "Reconnecting..."
                CallState.ENDED -> "Call ended"
                CallState.DECLINED -> "Call declined"
                CallState.MISSED -> "Call missed"
                CallState.FAILED -> "Call failed"
                else -> ""
            }

            Text(
                text = statusText,
                color = if (session.state == CallState.CONNECTED) Color(0xFF25D366) else Color(0xFF8696A0),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            // Large Avatar when in Audio Call mode
            if (!isVideo) {
                Spacer(modifier = Modifier.height(48.dp))
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color(0xFF25D366).copy(alpha = 0.5f), CircleShape)
                        .background(Color(0xFF2A3942)),
                    contentAlignment = Alignment.Center
                ) {
                    if (session.avatarRes != null) {
                        Image(
                            painter = painterResource(id = session.avatarRes),
                            contentDescription = session.contactName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }
        }

        // Bottom Call Controls Pill Panel
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF1F2C34).copy(alpha = 0.95f),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speaker Button
                IconButton(
                    onClick = onToggleSpeaker,
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (session.isSpeakerOn) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (session.isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                        contentDescription = "Speaker",
                        tint = if (session.isSpeakerOn) Color(0xFF25D366) else Color.White
                    )
                }

                // Video toggle button
                IconButton(
                    onClick = onToggleVideo,
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (session.isVideoEnabled) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (session.isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = "Video",
                        tint = if (session.isVideoEnabled) Color(0xFF25D366) else Color(0xFFEA4335)
                    )
                }

                // Switch camera button
                if (session.isVideoEnabled) {
                    IconButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FlipCameraAndroid,
                            contentDescription = "Switch camera",
                            tint = Color.White
                        )
                    }
                }

                // Mute Mic Button
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (session.isMuted) Color(0xFFEA4335).copy(alpha = 0.3f) else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (session.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = "Mute",
                        tint = if (session.isMuted) Color(0xFFEA4335) else Color.White
                    )
                }

                // Red End Call FAB
                FloatingActionButton(
                    onClick = onEndCall,
                    shape = CircleShape,
                    containerColor = Color(0xFFEA4335),
                    contentColor = Color.White,
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("end_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
