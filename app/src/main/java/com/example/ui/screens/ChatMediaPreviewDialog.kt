package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import com.example.R
import com.example.config.ChatConfig
import com.example.ui.theme.WhatsAppFabGreen
import com.example.ui.viewmodel.PendingAttachment

@Composable
fun ChatMediaPreviewDialog(
    pending: PendingAttachment,
    errorMessage: String?,
    onCaptionChanged: (String) -> Unit,
    onToggleViewOnce: () -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("media_preview_dialog")
    ) {
        // Top action bar with Close & File info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = pending.fileName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = ChatConfig.formatFileSize(pending.fileSize),
                    color = Color(0xFF8696A0),
                    fontSize = 12.sp
                )
            }
        }

        // Center Media Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 70.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!pending.previewUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = pending.previewUrl,
                    contentDescription = "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (pending.previewRes != null) {
                Image(
                    painter = painterResource(id = pending.previewRes),
                    contentDescription = "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Error message banner if size exceeded
        if (errorMessage != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFEA4335)
            ) {
                Text(
                    text = errorMessage,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Bottom Controls: Caption text field, View Once [1] toggle, and Send FAB
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (pending.isViewOnce) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Photo set to view once",
                        color = WhatsAppFabGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Caption input
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF1F2C34),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = pending.caption,
                            onValueChange = onCaptionChanged,
                            placeholder = { Text("Add a caption...", color = Color(0xFF8696A0)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        // View Once [1] circular button toggle!
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pending.isViewOnce) WhatsAppFabGreen else Color.White.copy(alpha = 0.15f)
                                )
                                .clickable(onClick = onToggleViewOnce),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "1",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Send FAB
                FloatingActionButton(
                    onClick = onSend,
                    shape = CircleShape,
                    containerColor = WhatsAppFabGreen,
                    contentColor = Color.White,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_media_fab")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
