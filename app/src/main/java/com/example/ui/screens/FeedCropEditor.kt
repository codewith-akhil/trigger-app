package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TriggerFabGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Result of the crop step.
 */
sealed class CropOutput {
    data class CroppedImage(val file: File, val width: Int, val height: Int) : CropOutput()
    data class CroppedVideo(val file: File, val durationMs: Long?) : CropOutput()
    data object Cancelled : CropOutput()
}

/**
 * Instagram-style crop editor with a FIXED output frame:
 *   • photos → 4:5 (feed card aspect)
 *   • videos → 9:16 (story aspect)
 * The user pans (drag) and zooms (pinch) the media inside the frame; the
 * visible region becomes the crop.
 *
 * Video output is rendered with media3 `Transformer` + `CropEffect`.
 */
@Composable
fun FeedCropEditor(
    mediaUri: Uri,
    isVideo: Boolean,
    targetAspect: Float, // width / height (4f/5f image, 9f/16f video)
    frameTitle: String,
    onConfirmed: (CropOutput) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val cropScope = rememberCoroutineScope()

    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var videoDurationMs by remember { mutableStateOf<Long?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var baseScale by remember { mutableStateOf(1f) }

    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var processError by remember { mutableStateOf<String?>(null) }

    // Load preview frame
    LaunchedEffect(mediaUri, isVideo) {
        withContext(Dispatchers.IO) {
            try {
                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, mediaUri)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                    retriever.release()
                    bitmap = frame
                } else {
                    bitmap = decodeOrientedBitmap(context, mediaUri)
                }
            } catch (e: Exception) {
                loadError = e.message ?: "Could not load media"
            }
        }
    }

    fun contentScaleFor(bm: Bitmap, frame: IntSize): Float {
        if (frame.width == 0 || frame.height == 0) return 1f
        // "cover" — fill the frame, overflow becomes the crop
        return max(frame.width.toFloat() / bm.width, frame.height.toFloat() / bm.height)
    }

    fun clampOffset(bm: Bitmap, frame: IntSize, s: Float, off: Offset): Offset {
        if (frame.width == 0 || frame.height == 0) return Offset.Zero
        val contentW = bm.width * s
        val contentH = bm.height * s
        val maxX = max(0f, (contentW - frame.width) / 2f)
        val maxY = max(0f, (contentH - frame.height) / 2f)
        return Offset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(-maxY, maxY))
    }

    fun confirm() {
        val bm = bitmap ?: return
        val frame = frameSize
        if (frame.width == 0 || frame.height == 0) return
        processing = true
        processError = null
        cropScope.launch {
            try {
                val s = baseScale * scale
                val contentW = bm.width * s
                val contentH = bm.height * s
                val srcLeft = ((contentW - frame.width) / 2f - offset.x) / s
                val srcTop = ((contentH - frame.height) / 2f - offset.y) / s
                val srcW = frame.width / s
                val srcH = frame.height / s

                val clampedLeft = srcLeft.coerceIn(0f, bm.width.toFloat())
                val clampedTop = srcTop.coerceIn(0f, bm.height.toFloat())
                val clampedW = min(srcW, bm.width - clampedLeft).coerceAtLeast(1f)
                val clampedH = min(srcH, bm.height - clampedTop).coerceAtLeast(1f)

                if (isVideo) {
                    val out = withContext(Dispatchers.IO) {
                        transformVideoCrop(
                            context, mediaUri,
                            clampedLeft / bm.width, clampedTop / bm.height,
                            (clampedLeft + clampedW) / bm.width, (clampedTop + clampedH) / bm.height
                        ) { fraction -> progress = fraction }
                    }
                    onConfirmed(CropOutput.CroppedVideo(out, videoDurationMs))
                } else {
                    val out = withContext(Dispatchers.IO) {
                        cropImage(context, bm, clampedLeft, clampedTop, clampedW, clampedH)
                    }
                    onConfirmed(CropOutput.CroppedImage(out.first, out.second, out.third))
                }
            } catch (e: Exception) {
                processError = e.message ?: "Cropping failed"
                processing = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B141A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (!processing) onCancel() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Crop Media", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        frameTitle,
                        color = Color(0xFF8696A0),
                        fontSize = 12.sp
                    )
                }
            }

            // Frame
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxW = maxWidth
                val maxH = maxHeight
                val frameW = if (maxW / maxH > targetAspect) maxH * targetAspect else maxW
                val frameH = frameW / targetAspect

                Box(
                    modifier = Modifier
                        .width(frameW)
                        .height(frameH)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(1.5.dp, Color(0xFF2A3942), RoundedCornerShape(10.dp))
                        .onSizeChanged { frameSize = it }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val bm = bitmap ?: return@detectTransformGestures
                                scale = (scale * zoom).coerceIn(0.6f, 6f)
                                val s = baseScale * scale
                                offset = clampOffset(bm, frameSize, s, offset + pan)
                            }
                        }
                ) {
                    val bm = bitmap
                    if (bm != null) {
                        // Initialize base scale once the frame is measured
                        LaunchedEffect(bm, frameSize) {
                            baseScale = contentScaleFor(bm, frameSize)
                            scale = 1f
                            offset = clampOffset(bm, frameSize, baseScale, offset)
                        }
                        Image(
                            bitmap = bm.asImageBitmap(),
                            contentDescription = "Crop preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = baseScale * scale
                                    scaleY = baseScale * scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )
                    } else if (loadError != null) {
                        Text(
                            "Could not load media: $loadError",
                            color = Color(0xFFF15C6D),
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            color = TriggerFabGreen,
                            modifier = Modifier.align(Alignment.Center).size(34.dp)
                        )
                    }

                    // rule-of-thirds guides
                    if (bitmap != null) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(1f / 3f)
                                .fillMaxHeight()
                                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        )
                    }
                }
            }

            // Bottom actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { if (!processing) onCancel() },
                    enabled = !processing,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Back") }

                Button(
                    onClick = { confirm() },
                    enabled = bitmap != null && !processing,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen)
                ) {
                    Icon(Icons.Filled.Crop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isVideo) "Crop Video" else "Crop Photo", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Processing overlay (video transform can take a while)
        if (processing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator(
                        progress = { if (isVideo) progress else 1f },
                        color = TriggerFabGreen,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (isVideo) "Processing video crop… ${(progress * 100).toInt()}%" else "Preparing photo…",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Keep the app open — this renders your ${if (isVideo) "9:16" else "4:5"} crop.",
                        color = Color(0xFF8696A0), fontSize = 12.sp
                    )
                    processError?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("Error: $it", color = Color(0xFFF15C6D), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Image decode + crop
// ---------------------------------------------------------------------------
private fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

    var sample = 1
    var previewMax = max(bounds.outWidth, bounds.outHeight)
    while (previewMax / 2 >= 1440) {
        sample *= 2
        previewMax /= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null

    // EXIF orientation
    val rotation = try {
        resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> -90f
                ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                ExifInterface.ORIENTATION_TRANSVERSE -> -90f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        0f
    }

    return if (rotation != 0f) {
        val m = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    } else decoded
}

/** Crops the source bitmap, scales the output to ≤1600px tall, writes JPEG. */
private fun cropImage(
    context: Context,
    source: Bitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float
): Triple<File, Int, Int> {
    val l = left.toInt().coerceIn(0, source.width - 1)
    val t = top.toInt().coerceIn(0, source.height - 1)
    val w = width.toInt().coerceIn(1, source.width - l)
    val h = height.toInt().coerceIn(1, source.height - t)

    var cropped = Bitmap.createBitmap(source, l, t, w, h)

    val maxDim = 1600
    if (cropped.height > maxDim) {
        val scale = maxDim.toFloat() / cropped.height
        cropped = Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt().coerceAtLeast(1),
            maxDim, true
        )
    }

    val out = File(context.cacheDir, "feed_crop_${System.currentTimeMillis()}.jpg")
    FileOutputStream(out).use { fos ->
        cropped.compress(Bitmap.CompressFormat.JPEG, 92, fos)
    }
    return Triple(out, cropped.width, cropped.height)
}

// ---------------------------------------------------------------------------
// Video crop via media3 Transformer + CropEffect
// Completion is detected by polling getProgress until it reports that no
// transformation is active (PROGRESS_STATE_NO_TRANSFORMATION) — the exact
// listener API differs across media3 versions, polling does not.
// ---------------------------------------------------------------------------
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun transformVideoCrop(
    context: Context,
    inputUri: Uri,
    fracLeft: Float,
    fracTop: Float,
    fracRight: Float,
    fracBottom: Float,
    onProgress: (Float) -> Unit
): File = withContext(Dispatchers.IO) {
    val outFile = File(context.cacheDir, "feed_video_crop_${System.currentTimeMillis()}.mp4")
    if (outFile.exists()) outFile.delete()

    val cropEffect = androidx.media3.effect.Crop(fracLeft, fracTop, fracRight, fracBottom)

    val transformer = androidx.media3.transformer.Transformer.Builder(context)
        .setVideoEffects(listOf(cropEffect))
        .build()

    val mediaItem = androidx.media3.common.MediaItem.fromUri(inputUri)
    transformer.startTransformation(mediaItem, outFile.absolutePath)

    val holder = androidx.media3.transformer.ProgressHolder()
    val noTransformation = androidx.media3.transformer.Transformer.PROGRESS_STATE_NO_TRANSFORMATION

    while (transformer.getProgress(holder) != noTransformation) {
        onProgress(holder.progress / 100f)
        Thread.sleep(150)
    }
    onProgress(1f)

    if (!outFile.exists() || outFile.length() == 0L) {
        throw IllegalStateException("Video processing produced no output — try a shorter clip")
    }
    outFile
}
