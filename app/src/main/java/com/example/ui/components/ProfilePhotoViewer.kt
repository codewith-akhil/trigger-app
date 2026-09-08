package com.example.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage

/**
 * ProfilePhotoViewer
 * ----------------------------------------------------------------------------
 * Fullscreen profile-photo viewer (WhatsApp-style):
 *  - Solid black background, photo centered with ContentScale.Fit
 *  - Pinch-to-zoom 1x-4x + pan while zoomed (same pattern as ChatMediaViewer)
 *  - Top-left circular back button
 *  - Own profile only: top-right pen (change photo) + trash (delete photo)
 *  - Other users: back button ONLY — no download/forward/share/delete
 *  - No photo yet: renders the profile's initial letter big and centered
 *
 * Rendered inside a fullscreen Dialog (usePlatformDefaultWidth=false); the
 * dialog window's status/navigation bars are forced black so the chrome
 * matches the viewer background.
 */
@Composable
fun ProfilePhotoViewer(
    imageUrl: String?,
    initialLetter: String,
    onClose: () -> Unit,
    isOwnProfile: Boolean,
    onDeleteClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null
) {
    // Zoom / pan state — 1x-4x, panning only while zoomed in (ChatMediaViewer pattern).
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset zoom, then close — the state dies with the composable anyway,
    // but an explicit reset keeps the "reset on close" contract obvious.
    fun handleClose() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        onClose()
    }

    // Force the dialog's own status bar to black so the fullscreen viewer
    // doesn't show the app's green scrim behind the black background.
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.statusBarColor = AndroidColor.BLACK
        window?.navigationBarColor = AndroidColor.BLACK
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ---- Centered photo (or letter fallback) with pinch-zoom ----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                } else {
                    Text(
                        text = initialLetter.uppercase(),
                        fontSize = 84.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // ---- Top bar: back on the left, edit/delete on the right ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularViewerButton(
                    onClick = { handleClose() },
                    contentDescription = "Back"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (isOwnProfile) {
                    if (onEditClick != null) {
                        CircularViewerButton(
                            onClick = onEditClick,
                            contentDescription = "Change profile photo"
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Change profile photo",
                                tint = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    if (onDeleteClick != null) {
                        CircularViewerButton(
                            onClick = onDeleteClick,
                            contentDescription = "Delete profile photo"
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete profile photo",
                                tint = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }
            }
        }
    }
}

/**
 * 48dp circular semi-transparent icon button used for the viewer chrome.
 * The whole 48dp circle is the touch target; [contentDescription] labels it
 * for accessibility via clickable's onClickLabel.
 */
@Composable
private fun CircularViewerButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(start = 10.dp, top = 10.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(onClickLabel = contentDescription) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
