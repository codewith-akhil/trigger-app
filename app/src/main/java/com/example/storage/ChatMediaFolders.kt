package com.example.storage

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.di.AppServiceContainer
import com.example.model.MessageType
import com.example.service.MediaUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Phase 3 media persistence — maps a chat message's media kind to the
 * [TriggerFolder] it must be archived into, infers a sane file extension,
 * and archives outgoing/picked content into the durable Trigger tree.
 *
 * All writes go through [TriggerStorageManager.newUserMediaFile] so every
 * file lands in the browsable WhatsApp-grade layout
 * (`Trigger/Media/<Trigger …>/[Sent/]TRG-<ts>-<rand>.<ext>`) — no code in
 * this file ever builds a path by hand (cloner-safety guarantee preserved).
 *
 * View-once media is NEVER routed through this object: callers must guard
 * before invoking (see [MessageServiceImpl] archive + [ChatViewModel] send
 * flow — hard guard + double-guard against the live Room row).
 */
object ChatMediaFolders {

    private const val TAG = "ChatMediaFolders"

    /**
     * Media kind (+ optional source bucket) → the Trigger folder the bytes
     * belong in:
     *  - IMAGE    → Trigger Images
     *  - VIDEO    → Trigger Video
     *  - AUDIO    → Trigger Voice Notes, UNLESS the source bucket indicates a
     *               plain audio attachment (any non-voice bucket, e.g. legacy
     *               chat_media audio rows) → Trigger Audio;
     *  - DOCUMENT → Trigger Documents.
     *  Non-media kinds have no canonical folder — they map to [TriggerFolder.SHARED]
     *  so a stray call can never crash or misplace bytes.
     */
    fun folderFor(type: MessageType, mediaBucket: String? = null): TriggerFolder = when (type) {
        MessageType.IMAGE -> TriggerFolder.IMAGES
        MessageType.VIDEO -> TriggerFolder.VIDEO
        MessageType.AUDIO ->
            if (mediaBucket.isNullOrBlank() || mediaBucket == MediaUrlResolver.VOICE_NOTES_BUCKET) {
                TriggerFolder.VOICE_NOTES
            } else {
                TriggerFolder.AUDIO
            }
        MessageType.DOCUMENT -> TriggerFolder.DOCUMENTS
        else -> TriggerFolder.SHARED
    }

    /**
     * Extension inference, in priority order (each step wins only when it
     * yields a plausible extension):
     *  1. [mimeType] via [MimeTypeMap] ("image/webp" → "webp");
     *  2. the extension of [fileName];
     *  3. the extension of the last segment of [url] / bare object path
     *     (query string stripped) — covers Task-24 rows whose media_url is
     *     "uid/uuid.jpg" with no display name;
     *  4. sensible per-kind defaults: jpg / mp4 / m4a / bin.
     */
    fun inferExtension(
        mimeType: String?,
        fileName: String?,
        url: String?,
        type: MessageType
    ): String {
        val mime = mimeType?.trim()?.substringBefore(';')?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() && !it.endsWith("/*") }
        if (!mime.isNullOrEmpty()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ext ->
                if (ext.isNotBlank()) return ext.lowercase(Locale.US)
            }
        }
        fileName?.takeIf { it.contains('.') }?.substringAfterLast('.')?.let { ext ->
            if (isPlausibleExtension(ext)) return ext.lowercase(Locale.US)
        }
        url?.substringBefore('?')?.takeIf { it.contains('.') }?.substringAfterLast('.')?.let { ext ->
            if (isPlausibleExtension(ext)) return ext.lowercase(Locale.US)
        }
        return when (type) {
            MessageType.IMAGE -> "jpg"
            MessageType.VIDEO -> "mp4"
            MessageType.AUDIO -> "m4a"
            else -> "bin"
        }
    }

    private fun isPlausibleExtension(ext: String): Boolean =
        ext.length in 1..8 && ext.all { it.isLetterOrDigit() }

    /**
     * Copies a picked/sent source ([sourcePath]: a content:// URI from the
     * picker/camera OR a plain absolute file path) into the storage manager's
     * [TriggerStorageManager.newUserMediaFile] location for [type] with
     * [isSent] = true — i.e. the `Sent/` leaf of the matching media folder —
     * and returns the archived [File], or null on any failure (never throws;
     * a partial target is deleted before returning).
     *
     * Runs on [Dispatchers.IO]; the caller may suspend freely. Archiving at
     * SEND time is deliberate: content:// grants are transient, and the
     * durable copy doubles as the sender's file-first render source and the
     * re-upload source for failed-message retries.
     */
    suspend fun archiveOutgoingCopy(
        sourcePath: String?,
        type: MessageType,
        fileName: String? = null,
        mimeType: String? = null,
        mediaBucket: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val source = sourcePath?.takeIf { it.isNotBlank() } ?: return@withContext null
        var target: File? = null
        try {
            val storage = AppServiceContainer.storageManager
            val ext = inferExtension(mimeType, fileName, source, type)
            target = storage.newUserMediaFile(folderFor(type, mediaBucket), isSent = true, ext)
            val input: InputStream? = if (source.startsWith("content://")) {
                AppServiceContainer.context.contentResolver.openInputStream(Uri.parse(source))
            } else {
                File(source).takeIf { it.isFile }?.inputStream()
            }
            if (input == null) {
                Log.w(TAG, "archiveOutgoingCopy: unreadable source ($source)")
                target.delete()
                return@withContext null
            }
            val copiedBytes = input.use { ins ->
                target.outputStream().use { outs -> ins.copyTo(outs) }
            }
            if (copiedBytes <= 0L) {
                Log.w(TAG, "archiveOutgoingCopy: empty copy for $source")
                target.delete()
                return@withContext null
            }
            target
        } catch (t: Throwable) {
            Log.w(TAG, "archiveOutgoingCopy failed: ${t.message}")
            try {
                target?.delete()
            } catch (_: Exception) {
                // best-effort cleanup only
            }
            null
        }
    }
}
