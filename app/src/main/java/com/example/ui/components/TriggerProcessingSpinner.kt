package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Two-tone circular processing spinner matching the attached design specification.
 * Features a full 360-degree light grey track and a smooth, rotating royal blue active sweep arc.
 */
@Composable
fun TriggerTwoToneSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    strokeWidth: Dp = 6.dp,
    trackColor: Color = Color(0xFFE5E7EB),
    primaryColor: Color = Color(0xFF0052CC) // Royal Blue as in model design
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner_rotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_angle"
    )

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = angle }
    ) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val diameter = size.toPx()
        val radius = (diameter - strokeWidth.toPx()) / 2f
        val centerOffset = Offset(diameter / 2f, diameter / 2f)

        // Draw light grey track circle (360 degrees)
        drawCircle(
            color = trackColor,
            radius = radius,
            center = centerOffset,
            style = stroke
        )

        // Draw active royal blue sweep arc (~115 degrees)
        drawArc(
            color = primaryColor,
            startAngle = 0f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = Offset(strokeWidth.toPx() / 2f, strokeWidth.toPx() / 2f),
            size = Size(radius * 2f, radius * 2f),
            style = stroke
        )
    }
}

/**
 * Elegant full-canvas loading view using the two-tone spinner for smooth tab transitions.
 */
@Composable
fun TriggerTabLoadingView(
    tabName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            TriggerTwoToneSpinner(
                size = 72.dp,
                strokeWidth = 5.5.dp,
                primaryColor = Color(0xFF0052CC),
                trackColor = Color(0xFFE5E7EB)
            )

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Loading $tabName...",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Please hold on while we refresh your content.",
                fontSize = 13.sp,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center
            )
        }
    }
}
