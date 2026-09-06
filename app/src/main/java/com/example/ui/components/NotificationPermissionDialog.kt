package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.GeometricBorder
import com.example.ui.theme.GeometricGreenPrimary
import com.example.ui.theme.GeometricTextPrimary
import com.example.ui.theme.GeometricTextSecondary

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
                .widthIn(max = 280.dp)
                .padding(horizontal = 8.dp)
                .testTag("notification_permission_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, GeometricBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.notification_dialog_title),
                    color = GeometricTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDontAllow,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .testTag("dont_allow_notification_button"),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, GeometricBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = GeometricTextSecondary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dont_allow),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = onAllow,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .testTag("allow_notification_button"),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GeometricGreenPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.allow),
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

