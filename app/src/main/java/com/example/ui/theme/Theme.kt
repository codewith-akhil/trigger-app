package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GeometricColorScheme = lightColorScheme(
    primary = GeometricGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4EC),
    onPrimaryContainer = Color(0xFF880E4F),
    secondary = GeometricGreenDark,
    onSecondary = Color.White,
    background = GeometricCanvasBg,
    onBackground = GeometricTextPrimary,
    surface = GeometricSurface,
    onSurface = GeometricTextPrimary,
    surfaceVariant = GeometricSurfaceVariant,
    onSurfaceVariant = GeometricTextSecondary,
    outline = GeometricBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GeometricColorScheme,
        typography = Typography,
        content = content
    )
}

