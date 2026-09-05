package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
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

data class EmojiCategory(
    val title: String,
    val icon: String,
    val emojis: List<String>,
    val isStickerCategory: Boolean = false
)

@Composable
fun ChatEmojiPicker(
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onStickerSelected: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val categories = remember {
        listOf(
            EmojiCategory(
                title = "Smileys & Emotion",
                icon = "😀",
                emojis = listOf(
                    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "🥹",
                    "☺️", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘",
                    "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐",
                    "🤓", "😎", "🥸", "🤩", "🥳", "😏", "😒", "😞", "😔", "😟",
                    "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭",
                    "😮‍💨", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱",
                    "😨", "😰", "😥", "😓", "🫣", "🤗", "🫡", "🤔", "🫢", "🤫",
                    "🫠", "🤥", "😶", "😶‍🌫️", "😐", "😑", "😬", "🫨", "😯", "😦",
                    "🥱", "😴", "🤤", "😪", "😮", "😵", "😵‍💫", "🤐", "🥴", "🤢"
                )
            ),
            EmojiCategory(
                title = "People & Body",
                icon = "👋",
                emojis = listOf(
                    "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫸", "🫷", "🫳",
                    "🫴", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
                    "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊",
                    "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏",
                    "✍️", "💅", "🤳", "💪", "🦾", "🦵", "🦿", "🦶", "👣", "👂",
                    "🦻", "👃", "🫀", "🫁", "🧠", "🫀", "👀", "👁️", "👅", "👄"
                )
            ),
            EmojiCategory(
                title = "Animals & Nature",
                icon = "🐶",
                emojis = listOf(
                    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨",
                    "🐯", "🦁", "🐮", "🐷", "🐽", "🐸", "🐵", "🙈", "🙉", "🙊",
                    "🐒", "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉",
                    "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🪱", "🐛", "🦋", "🐌",
                    "🐞", "🐜", "🪰", "🪲", "🪳", "🦟", "🦗", "🕷️", "🕸️", "🦂",
                    "🐢", "🐍", "🦎", "🐙", "🦑", "🦞", "🦀", "🐡", "🐠", "🐟"
                )
            ),
            EmojiCategory(
                title = "Food & Drink",
                icon = "🍕",
                emojis = listOf(
                    "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
                    "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑",
                    "🥦", "🥬", "🥒", "🌶️", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠",
                    "🥐", "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞",
                    "🧇", "🥓", "🥩", "🍗", "🍖", "🌭", "🍔", "🍟", "🍕", "🥪",
                    "🥙", "🧆", "🌮", "🌯", "🥗", "🥘", "🍝", "🍜", "🍲", "🍛",
                    "🍣", "🍱", "🥟", "🦪", "🍤", "🍙", "🍚", "🍦", "🍧", "🍨",
                    "🍩", "🍪", "🎂", "🍰", "🧁", "🍫", "🍬", "🍭", "☕", "🍵"
                )
            ),
            EmojiCategory(
                title = "Activities & Travel",
                icon = "⚽",
                emojis = listOf(
                    "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
                    "🏓", "🏸", "🏒", "🥍", "🏏", "⛳", "🏹", "🎣", "🥊", "🥋",
                    "🛹", "🛼", "⛷️", "🏂", "🏋️", "🤼", "🤸", "🧗", "🚴", "🏇",
                    "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🎫", "🎟️", "🎭", "🎨",
                    "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷", "🎺", "🎸", "🎻",
                    "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
                    "🚲", "🛵", "🏍️", "🛺", "🚨", "✈️", "🛫", "🛬", "🚀", "⛵"
                )
            ),
            EmojiCategory(
                title = "Hearts & Symbols",
                icon = "❤️",
                emojis = listOf(
                    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                    "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️",
                    "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
                    "⛎", "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐",
                    "♑", "♒", "♓", "🆔", "⚛️", "🉑", "☢️", "☣️", "📴", "📳",
                    "🈶", "🈚", "🈸", "🈺", "🈷️", "✴️", "❇️", "✳️", "‼️", "⁉️",
                    "💯", "✨", "🔥", "💥", "💫", "⭐️", "🌟", "⚡️", "☄️", "🎉"
                )
            ),
            // Sticker tab — large emoji sent as stickers (sent as TEXT).
            // Marked with isStickerCategory so the picker renders them at 2×.
            EmojiCategory(
                title = "Stickers",
                icon = "🏷️",
                emojis = listOf(
                    "😀", "😂", "🤣", "😍", "🥰", "😎", "🤩", "🥳",
                    "😭", "😱", "🤯", "🤔", "😴", "🤤", "🤗", "🤫",
                    "👋", "👍", "👎", "👏", "🙌", "🤝", "🙏", "💪",
                    "❤️", "🔥", "✨", "🎉", "🎁", "🏆", "💯", "⭐",
                    "🐶", "🐱", "🦄", "🐸", "🐵", "🐼", "🦁", "🐯",
                    "🍕", "🍔", "🍟", "🍩", "🍦", "🍺", "☕", "🍓",
                    "⚽", "🏀", "🎮", "🎸", "🚀", "✈️", "🏖️", "🌸"
                ),
                isStickerCategory = true
            )
        )
    }

    var selectedCategoryIndex by remember { mutableStateOf(0) }
    val currentCategory = categories[selectedCategoryIndex]

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .testTag("emoji_picker"),
        color = Color(0xFFF0F2F5)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Category Title & Backspace row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentCategory.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF667781),
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onBackspace,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = Color(0xFF54656F),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Emoji Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 42.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(currentCategory.emojis, key = { emoji -> emoji }) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(if (currentCategory.isStickerCategory) 64.dp else 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (currentCategory.isStickerCategory && onStickerSelected != null) {
                                    onStickerSelected(emoji)
                                } else {
                                    onEmojiSelected(emoji)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = if (currentCategory.isStickerCategory) 36.sp else 24.sp
                        )
                    }
                }
            }

            // Category Bar along the bottom
            Surface(
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    categories.forEachIndexed { index, cat ->
                        val isSelected = index == selectedCategoryIndex
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFE7FCE3) else Color.Transparent)
                                .clickable { selectedCategoryIndex = index },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = cat.icon,
                                fontSize = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
