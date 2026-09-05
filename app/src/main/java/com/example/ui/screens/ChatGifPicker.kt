package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * ChatGifPicker
 *
 * A lightweight GIF picker that ships a curated, hardcoded set of "animated
 * stickers" (we use large emoji at 2× size, since the app already ships the
 * full emoji set — no external API needed). When tapped, the picker calls
 * `onGifSelected(url)` with the chosen sticker code so the parent can send it
 * as a TEXT message (or a GIF type).
 *
 * This keeps the app offline-friendly and matches the implementation rule in
 * the task description ("use the existing emoji set but mark them as GIFs").
 */
@Composable
fun ChatGifPicker(
    onGifSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Curated "sticker" set — large emoji that read as animated stickers.
    // Categories: happy, love, sad, angry, party, animals, food, gestures
    val gifStickers = remember {
        listOf(
            "😀", "😂", "🤣", "😍", "🥰", "😎", "🤩", "🥳",
            "😭", "😱", "🤯", "🤔", "😴", "🤤", "🤗", "🤫",
            "👋", "👍", "👎", "👏", "🙌", "🤝", "🙏", "💪",
            "❤️", "🔥", "✨", "🎉", "🎁", "🏆", "💯", "⭐",
            "🐶", "🐱", "🦄", "🐸", "🐵", "🐼", "🦁", "🐯",
            "🍕", "🍔", "🍟", "🍩", "🍦", "🍺", "☕", "🍓",
            "⚽", "🏀", "🎮", "🎸", "🚀", "✈️", "🏖️", "🌸"
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .testTag("gif_picker"),
        color = Color(0xFFF0F2F5)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Gif,
                    contentDescription = null,
                    tint = Color(0xFF54656F),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "GIFs & Stickers",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF667781),
                    modifier = Modifier.weight(1f)
                )
            }

            // Sticker grid (2× size = larger cells)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 76.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(gifStickers, key = { sticker -> sticker }) { sticker ->
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .clickable { onGifSelected(sticker) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sticker,
                            fontSize = 44.sp  // 2× the emoji picker size (24sp → 48sp)
                        )
                    }
                }
            }
        }
    }
}
