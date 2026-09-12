package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
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

    var currentUri by remember(mediaUri) { mutableStateOf(mediaUri) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var videoDurationMs by remember { mutableStateOf<Long?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var baseScale by remember { mutableFloatStateOf(1f) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }

    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var processError by remember { mutableStateOf<String?>(null) }

    // Media picker to easily switch photo or pick if initial load failed
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { picked ->
        if (picked != null) {
            currentUri = picked
            loadError = null
            scale = 1f
            offset = Offset.Zero
            rotationDegrees = 0f
        }
    }

    // Load preview frame
    LaunchedEffect(currentUri, isVideo) {
        isLoading = true
        loadError = null
        withContext(Dispatchers.IO) {
            try {
                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, currentUri)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                    retriever.release()
                    bitmap = frame
                } else {
                    var decoded = decodeOrientedBitmap(context, currentUri)
                    if (decoded == null) {
                        decoded = loadMediaBitmapFallback(context, currentUri)
                    }
                    bitmap = decoded
                }
                if (bitmap == null) {
                    loadError = "Unable to load preview. Please select another media."
                }
            } catch (e: Exception) {
                // Try fallback once more
                val fallback = if (!isVideo) loadMediaBitmapFallback(context, currentUri) else null
                if (fallback != null) {
                    bitmap = fallback
                } else {
                    loadError = e.message ?: "Could not load media"
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun contentScaleFor(bm: Bitmap, frame: IntSize): Float {
        if (frame.width == 0 || frame.height == 0) return 1f
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
                            context, currentUri,
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
            .background(Color(0xFF131C21))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Standard Green Header
            Surface(
                color = TriggerHeaderGreen,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (!processing) onCancel() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Crop Media",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp
                            )
                            Text(
                                text = frameTitle,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                        }
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(
                                        if (isVideo) ActivityResultContracts.PickVisualMedia.VideoOnly
                                        else ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            enabled = !processing,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = "Choose Media",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Crop Frame Area
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0C1317))
                        .border(2.dp, TriggerFabGreen.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .onSizeChanged { frameSize = it }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val bm = bitmap ?: return@detectTransformGestures
                                scale = (scale * zoom).coerceIn(0.5f, 5f)
                                val s = baseScale * scale
                                offset = clampOffset(bm, frameSize, s, offset + pan)
                            }
                        }
                ) {
                    val bm = bitmap
                    if (bm != null) {
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
                                    rotationZ = rotationDegrees
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )

                        // Rule-of-thirds grid guides
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                            }
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                            }
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(alpha = 0.25f)))
                            }
                        }
                    } else if (isLoading) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = TriggerFabGreen,
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Loading media…", color = Color.White, fontSize = 13.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BrokenImage,
                                contentDescription = null,
                                tint = Color(0xFFF15C6D),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = loadError ?: "Could not preview media",
                                color = Color.White,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            if (isVideo) ActivityResultContracts.PickVisualMedia.VideoOnly
                                            else ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Choose Photo", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Quick adjustment tools (Zoom Out, Reset, Zoom In, Rotate)
            if (bitmap != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { scale = (scale - 0.2f).coerceAtLeast(0.5f) },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(0xFF222E35), CircleShape)
                    ) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom Out", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledTonalButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                            rotationDegrees = 0f
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF222E35), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("${(scale * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(
                        onClick = { scale = (scale + 0.2f).coerceAtMost(4f) },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(0xFF222E35), CircleShape)
                    ) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom In", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(
                        onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(0xFF222E35), CircleShape)
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Bottom Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(
                                if (isVideo) ActivityResultContracts.PickVisualMedia.VideoOnly
                                else ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = !processing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B4A54))
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Change Photo")
                }

                Button(
                    onClick = { confirm() },
                    enabled = bitmap != null && !processing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TriggerFabGreen,
                        disabledContainerColor = TriggerFabGreen.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(Icons.Filled.Crop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isVideo) "Crop Video" else "Crop Photo",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Processing overlay
        if (processing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.80f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator(
                        progress = { if (isVideo) progress else 1f },
                        color = TriggerFabGreen,
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (isVideo) "Processing video crop… ${(progress * 100).toInt()}%" else "Preparing photo…",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Rendering your ${if (isVideo) "9:16" else "4:5"} crop…",
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
private suspend fun loadMediaBitmapFallback(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val loader = Coil.imageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false)
            .build()
        val result = loader.execute(request)
        if (result is SuccessResult) {
            val drawable = result.drawable
            if (drawable is BitmapDrawable) {
                return@withContext drawable.bitmap
            }
        }
    } catch (_: Exception) {}

    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            return@withContext BitmapFactory.decodeStream(stream)
        }
    } catch (_: Exception) {}
    null
}

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
