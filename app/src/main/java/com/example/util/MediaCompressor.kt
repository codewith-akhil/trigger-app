package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * MediaCompressor — WhatsApp-style pre-send media processing. All functions are
 * BLOCKING and must be called from Dispatchers.IO.
 *
 *  - Images: decode bounds → downscale (max 1600px longest side) → JPEG q82
 *    into cacheDir. The original file is kept when it is already small
 *    (<= 300 KB) or a GIF (animation would be destroyed by re-encoding).
 *    This also normalizes HEIC/HEIF captures to JPEG — the chat_media bucket
 *    does not accept image/heic, so without this those uploads failed.
 *  - Videos: no re-encode (too expensive on-device); instead extract a poster
 *    frame at ~1s (max 720px) for the bubble thumbnail + the real duration.
 *  - Voice/audio: never touched (re-encoding speech is pointless).
 */
object MediaCompressor {

    private const val TAG = "MediaCompressor"

    private const val MAX_IMAGE_DIM_PX = 1600
    private const val IMAGE_QUALITY = 82
    private const val KEEP_ORIGINAL_UNDER_BYTES = 300L * 1024L

    private const val MAX_THUMB_DIM_PX = 720

    /** True when the source is an animated GIF (re-encoding would kill it). */
    fun isGif(fileName: String?, mimeType: String?): Boolean {
        if (mimeType?.equals("image/gif", ignoreCase = true) == true) return true
        return fileName?.lowercase()?.endsWith(".gif") == true
    }

    /**
     * Compresses an image for upload.
     *
     * @return the compressed JPEG file in cacheDir, or null when the original
     *         should be uploaded as-is (GIF, already small, or decode failure).
     */
    fun compressImageBlocking(
        context: Context,
        sourcePath: String,
        fileName: String?,
        mimeType: String?
    ): File? {
        return try {
            if (isGif(fileName, mimeType)) return null

            val sourceFile = if (sourcePath.startsWith("content://")) null else File(sourcePath)
            if (sourceFile != null) {
                if (!sourceFile.exists()) return null
                // Already small enough — skip the re-encode entirely.
                if (sourceFile.length() in 1 until KEEP_ORIGINAL_UNDER_BYTES) return null
            } else {
                val size = queryContentSize(context, sourcePath)
                if (size in 1 until KEEP_ORIGINAL_UNDER_BYTES) return null
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openSource(context, sourcePath)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIM_PX)
            }
            val decoded = openSource(context, sourcePath)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null

            // Honor EXIF rotation (phone camera photos would otherwise upload
            // sideways — the framework decoder ignores the orientation flag).
            val rotated = applyExifRotation(context, sourcePath, decoded)

            val finalBitmap = scaleToBounds(rotated, MAX_IMAGE_DIM_PX)

            val outFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
            FileOutputStream(outFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
                out.flush()
            }
            if (finalBitmap !== decoded) decoded.recycle()

            // Sanity: a failed encode must never silently produce a 0-byte object.
            if (outFile.length() <= 0L) {
                outFile.delete()
                return null
            }
            Log.i(TAG, "Image compressed: ${finalBitmap.width}x${finalBitmap.height} → ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "compressImage failed (${e.message}) — uploading original")
            null
        }
    }

    /**
     * Extracts a poster frame from a video (frame at ~1s, falling back to the
     * first frame for very short clips) scaled to max 720px.
     *
     * @return the JPEG thumbnail file in cacheDir, or null on failure.
     */
    fun extractVideoThumbnailBlocking(context: Context, sourcePath: String): File? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            setDataSource(context, retriever, sourcePath)
            var frame = retriever.getFrameAtTime(
                1_000_000L, // ~1s in
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                frame = scaleToBounds(frame, MAX_THUMB_DIM_PX)
            }
            if (frame == null) return null

            val outFile = File(context.cacheDir, "thumb_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
            FileOutputStream(outFile).use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
                out.flush()
            }
            if (outFile.length() <= 0L) {
                outFile.delete()
                null
            } else {
                outFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "video thumbnail extraction failed: ${e.message}")
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Reads the real duration of a video/audio file in whole seconds
     * (0 when it cannot be determined).
     */
    fun extractMediaDurationSecBlocking(context: Context, sourcePath: String): Int {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            setDataSource(context, retriever, sourcePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            (ms / 1000L).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    // ---------- internals ----------

    private fun openSource(context: Context, path: String): InputStream? = try {
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).inputStream()
        }
    } catch (e: Exception) {
        null
    }

    private fun queryContentSize(context: Context, contentPath: String): Long = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(contentPath), "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun setDataSource(context: Context, retriever: MediaMetadataRetriever, path: String) {
        if (path.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        val longest = maxOf(width, height)
        var sample = 1
        if (longest <= maxDim) return sample
        // Halve repeatedly while the result still covers maxDim — the exact
        // size is then produced by scaleToBounds (no aggressive oversampling).
        while (longest / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifRotation(context: Context, sourcePath: String, bitmap: Bitmap): Bitmap {
        val orientation = try {
            openSource(context, sourcePath)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    /** Downscales so the longest side is <= [maxDim] (never upscales). */
    private fun scaleToBounds(source: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDim) return source
        val scale = maxDim.toFloat() / longest
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        } catch (e: Exception) {
            source
        }
    }
}
