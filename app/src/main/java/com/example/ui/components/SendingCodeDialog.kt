package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.GeometricGreenPrimary
import com.example.ui.theme.GeometricTextDark

@Composable
fun SendingCodeDialog(
    message: String = stringResource(R.string.sending_code),
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = { /* Non-dismissible while loading */ }) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .padding(horizontal = 16.dp)
                .testTag("sending_code_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = GeometricGreenPrimary,
                    strokeWidth = 3.5.dp,
                    strokeCap = StrokeCap.Round,
                    trackColor = Color(0xFFE8F5E9)
                )

                Spacer(modifier = Modifier.width(24.dp))

                Text(
                    text = message,
                    color = GeometricTextDark,
                    fontSize = 16.sp
                )
            }
        }
    }
}

