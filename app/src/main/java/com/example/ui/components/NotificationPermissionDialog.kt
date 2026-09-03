package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.GeometricBlueAccent
import com.example.ui.theme.GeometricBorder
import com.example.ui.theme.GeometricDialogBg
import com.example.ui.theme.GeometricTextPrimary

@Composable
fun NotificationPermissionDialog(
    onAllow: () -> Unit,
    onDontAllow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDontAllow) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .padding(horizontal = 12.dp)
                .testTag("notification_permission_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = GeometricDialogBg,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Bell icon
                Box(
                    modifier = Modifier
                        .size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Notification",
                        tint = GeometricBlueAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.notification_dialog_title),
                    color = GeometricTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Allow button (white rounded-full pill with subtle border)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable(onClick = onAllow)
                        .testTag("allow_notification_button"),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.dp, GeometricBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.allow).uppercase(),
                            color = GeometricBlueAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.2.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Don't allow button (transparent rounded-full pill)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable(onClick = onDontAllow)
                        .padding(vertical = 12.dp)
                        .testTag("dont_allow_notification_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dont_allow).uppercase(),
                        color = GeometricBlueAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        }
    }
}

