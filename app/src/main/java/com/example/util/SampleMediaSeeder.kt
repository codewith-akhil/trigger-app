package com.example.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SamplePhotoPreset(
    val id: String,
    val title: String,
    val description: String,
    @DrawableRes val resId: Int
)

object SampleMediaSeeder {

    val samplePresets = listOf(
        SamplePhotoPreset(
            id = "mountain_lake",
            title = "Alpine Lake",
            description = "Nature landscape",
            resId = R.drawable.sample_mountain_lake
        ),
        SamplePhotoPreset(
            id = "coffee_cafe",
            title = "Morning Cafe",
            description = "Artisan latte & notebook",
            resId = R.drawable.sample_coffee_cafe
        ),
        SamplePhotoPreset(
            id = "media_sample",
            title = "Golden Sunset",
            description = "Palms & vibrant sky",
            resId = R.drawable.img_media_sample
        ),
        SamplePhotoPreset(
            id = "welcome_art",
            title = "Illustration",
            description = "Vector artwork",
            resId = R.drawable.img_welcome_art
        )
    )

    /**
     * Copies the drawable to cache and returns a content:// URI via FileProvider
     * so it can be passed directly to the Crop Editor.
     */
    fun getPresetUri(context: Context, preset: SamplePhotoPreset): Uri {
        val sampleDir = File(context.cacheDir, "sample_media").apply { mkdirs() }
        val targetFile = File(sampleDir, "${preset.id}.jpg")
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.resources.openRawResource(preset.resId).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile
        )
    }

    /**
     * Seeds the sample photos into Android's system MediaStore / Pictures directory
     * so that they appear inside the system Photo Picker and device gallery.
     */
    suspend fun seedToDeviceGallery(context: Context): Int = withContext(Dispatchers.IO) {
        var count = 0
        val resolver = context.contentResolver

        for (preset in samplePresets) {
            try {
                val displayName = "Trigger_${preset.id}.jpg"

                // Check if already seeded to avoid duplicates
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(displayName)
                val exists = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor -> cursor.count > 0 } ?: false

                if (exists) {
                    count++
                    continue
                }

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TriggerSample")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        context.resources.openRawResource(preset.resId).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    count++
                }
            } catch (e: Exception) {
                android.util.Log.w("SampleMediaSeeder", "Failed to seed ${preset.id}: ${e.message}")
            }
        }
        count
    }
}
