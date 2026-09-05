package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.theme.TriggerHeaderGreen

/**
 * Standard Trigger App bottom inset spacer that fills the system navigation bar
 * area below the content / mobile nav with TriggerHeaderGreen (#008069).
 */
@Composable
fun TriggerBottomNavInset(
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(TriggerHeaderGreen)
    )
}
