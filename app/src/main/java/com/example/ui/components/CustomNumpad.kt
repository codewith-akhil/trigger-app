package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun CustomGboardNumpad(
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onSubmitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GeometricNumpadBg)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        // Keyboard toolbar with subtle utility icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.GridView,
                contentDescription = "Menu",
                tint = GeometricTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.StickyNote2,
                contentDescription = "Stickers",
                tint = GeometricTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "GIF",
                color = GeometricTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = GeometricTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Icon(
                imageVector = Icons.Filled.Translate,
                contentDescription = "Translate",
                tint = GeometricTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "Voice",
                tint = GeometricTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4 rows of 4 columns
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyItem("1", "", Modifier.weight(1f)) { onNumberClick("1") }
            KeyItem("2", "ABC", Modifier.weight(1f)) { onNumberClick("2") }
            KeyItem("3", "DEF", Modifier.weight(1f)) { onNumberClick("3") }
            SpecialKeyItem("-", Modifier.weight(1f)) { onNumberClick("-") }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyItem("4", "GHI", Modifier.weight(1f)) { onNumberClick("4") }
            KeyItem("5", "JKL", Modifier.weight(1f)) { onNumberClick("5") }
            KeyItem("6", "MNO", Modifier.weight(1f)) { onNumberClick("6") }
            SpecialKeyItem("␣", Modifier.weight(1f)) { onNumberClick(" ") }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyItem("7", "PQRS", Modifier.weight(1f)) { onNumberClick("7") }
            KeyItem("8", "TUV", Modifier.weight(1f)) { onNumberClick("8") }
            KeyItem("9", "WXYZ", Modifier.weight(1f)) { onNumberClick("9") }
            IconKeyItem(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = GeometricTextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                },
                bgColor = GeometricKeySpecialBg,
                modifier = Modifier.weight(1f),
                onClick = onDeleteClick
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyItem("* #", "", Modifier.weight(1f)) { onNumberClick("*") }
            KeyItem("0", "+", Modifier.weight(1f)) { onNumberClick("0") }
            KeyItem(".", "", Modifier.weight(1f)) { onNumberClick(".") }
            IconKeyItem(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Submit",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                },
                bgColor = GeometricGreenPrimary,
                modifier = Modifier.weight(1f),
                onClick = onSubmitClick
            )
        }
    }
}

@Composable
private fun KeyItem(
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(GeometricKeyBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = GeometricKeyRipple),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = primary,
                color = GeometricTextPrimary,
                fontSize = if (primary.length > 2) 16.sp else 22.sp,
                fontWeight = FontWeight.Medium
            )
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    color = GeometricTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun SpecialKeyItem(
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(GeometricKeySpecialBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = GeometricKeyRipple),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = GeometricTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun IconKeyItem(
    icon: @Composable () -> Unit,
    bgColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = GeometricKeyRipple),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

