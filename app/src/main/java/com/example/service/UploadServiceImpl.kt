package com.example.service

import android.util.Log
import android.webkit.MimeTypeMap
import com.example.di.AppServiceContainer
import com.example.model.MessageType
import com.example.model.UploadTask
import com.example.util.MediaCompressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.Sink
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * UploadServiceImpl — DIRECT client → Supabase Storage upload.
 *
 * History: files were relayed through the `upload-chat-media` edge function,
 * which buffered the WHOLE file in Deno RAM (`req.arrayBuffer()`) and
 * re-uploaded it — every byte crossed the network twice and 250 MB videos
 * died of memory pressure. The client now PUTs straight to
 * `/storage/v1/object/{bucket}/{userId}/{uuid}.{ext}` using the same
 * authenticated-OkHttp pattern SupabaseClient.uploadFile uses (Storage RLS
 * `*_owner_write` policies allow writes into the user's own folder), and only
 * the light `send-message` round-trip remains after the bytes land.
 *
 * Progress: a CountingSink inside the streaming request body throttles
 * (~120 ms) real byte counts into [tasksMap] → percent, rolling speed and
 * remaining seconds (the UploadTask fields existed but were never computed).
 *
 * Cancellation: [cancelUpload] aborts the in-flight OkHttp call, emits a
 * TERMINAL failed task state and flips the staged message row to FAILED
 * (via the onUploadFailed callback wired in AppServiceContainer) so the
 * bubble shows the WhatsApp-style retry affordance instead of a spinner.
 */
class UploadServiceImpl(
    private val scope: CoroutineScope,
    private val onUploadComplete: suspend (UploadTask) -> Unit = {},
    private val onUploadFailed: suspend (UploadTask) -> Unit = {}
) : UploadService {

    companion object {
        private const val TAG = "UploadServiceImpl"
        private const val CHAT_MEDIA_BUCKET = "chat_media"
        private const val VOICE_NOTES_BUCKET = "voice_notes"
        private const val PROGRESS_INTERVAL_MS = 120L
        private const val SPEED_WINDOW_MS = 2000L
    }

    private val _activeUploads = MutableStateFlow<List<UploadTask>>(emptyList())
    override val activeUploads = _activeUploads.asStateFlow()

    private val jobMap = ConcurrentHashMap<String, Job>()
    private val callMap = ConcurrentHashMap<String, Call>()
    private val tasksMap = ConcurrentHashMap<String, UploadTask>()

    // OkHttp client with long read/write timeouts — a 250 MB video on a slow
    // uplink previously died at 120s of silence mid-transfer.
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    override fun enqueueUpload(task: UploadTask) {
        // A retry re-enqueues with the same deterministic id — drop any stale
        // terminal state so the bubble overlay restarts from 0%.
        tasksMap[task.id] = task.copy(
            uploadedBytes = 0L,
            speedBytesPerSec = 0.0,
            remainingSeconds = 0,
            isCompleted = false,
            isFailed = false,
            errorMessage = null
        )
        refreshState()

        val job = scope.launch {
            val taskId = task.id
            try {
                val supabaseClient = AppServiceContainer.supabaseClient
                val baseUrl = com.example.config.BackendConfig.SUPABASE_URL
                // Refresh-before-use: a stale ~1 h old access token made every
                // upload 401 until some other call happened to refresh it.
                val token = supabaseClient.ensureFreshAccessToken()
                    ?: com.example.config.BackendConfig.SUPABASE_ANON_KEY
                val anonKey = com.example.config.BackendConfig.SUPABASE_ANON_KEY
                // Storage RLS scopes the write to the user's own folder — the
                // object key MUST start with the auth uuid.
                val userId = supabaseClient.currentSession?.user?.id
                if (userId.isNullOrBlank()) {
                    throw Exception("Not signed in — cannot upload media")
                }

                // Resolve the upload source WITHOUT materializing the whole
                // file in RAM (a 250 MB video previously did readBytes() →
                // OOM). We only query its size; bytes stream during upload.
                var sourcePath = task.filePath ?: ""
                var uploadSource = withContext(Dispatchers.IO) {
                    resolveUploadSource(sourcePath)
                }
                if (uploadSource == null || uploadSource.size <= 0L) {
                    throw Exception("Could not read file")
                }

                // ---- Image compression (WhatsApp-style) ----
                // Downscale to max 1600px longest side + JPEG q82. Skipped for
                // GIFs and anything already <= 300 KB. Failure falls back to
                // the original — an upload is never blocked by this.
                if (task.fileType == MessageType.IMAGE) {
                    val compressed = withContext(Dispatchers.IO) {
                        MediaCompressor.compressImageBlocking(
                            context = AppServiceContainer.context,
                            sourcePath = sourcePath,
                            fileName = task.fileName,
                            mimeType = task.mimeType
                        )
                    }
                    if (compressed != null && compressed.exists() && compressed.length() > 0L) {
                        sourcePath = compressed.absolutePath
                        uploadSource = UploadSource(compressed.length()) { compressed.inputStream() }
                    }
                }

                // The mime drives the Storage content-type AND the bucket's
                // allowed_mime_types gate. Compressed images are JPEG now;
                // otherwise derive from the extension when the picker didn't
                // provide a usable mime.
                val mimeType = resolveMimeType(task, sourcePath)
                val bucket = if (task.fileType == MessageType.AUDIO) VOICE_NOTES_BUCKET else CHAT_MEDIA_BUCKET

                // Update progress to "uploading"
                val uploadingTask = (tasksMap[taskId] ?: task).copy(
                    totalBytes = uploadSource.size,
                    uploadedBytes = 0L,
                    isCompleted = false
                )
                tasksMap[taskId] = uploadingTask
                refreshState()

                // Object key: {userId}/{uuid}.{ext} — the extension is
                // sanitized exactly like the edge function did (an unsanitized
                // filename once produced "…/x./../../evil" fragments).
                val ext = sanitizedExtension(task.fileName)
                val objectPath = "$userId/${java.util.UUID.randomUUID()}.$ext"

                val requestBody = StreamingSourceRequestBody(
                    contentType = mimeType.toMediaType(),
                    size = uploadSource.size,
                    opener = uploadSource.open,
                    onProgress = { written, speed, remaining ->
                        updateProgress(taskId, written, speed, remaining)
                    }
                )

                val request = Request.Builder()
                    .url("$baseUrl/storage/v1/object/$bucket/$objectPath")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", mimeType)
                    .post(requestBody)
                    .build()

                // Execute the upload — OkHttp calls MUST leave the caller's
                // (main) dispatcher; previously execute() ran on Main and
                // threw NetworkOnMainThreadException on EVERY media send.
                val call = uploadClient.newCall(request)
                callMap[taskId] = call
                val response = withContext(Dispatchers.IO) {
                    call.execute()
                }
                val responseBody = response.body?.string() ?: ""
                response.use { }

                if (!response.isSuccessful) {
                    throw Exception("Storage upload failed: ${response.code} - ${responseBody.take(200)}")
                }

                // Task 24: chat_media + voice_notes are PRIVATE buckets with
                // participant-only storage RLS. Persist the BARE OBJECT PATH
                // ("{uid}/{uuid}.ext") — never a public URL. Every reader
                // mints a short-lived signed URL from bucket+path via
                // MediaUrlResolver at render/play time.
                val finalUrl = objectPath

                // ---- Video thumbnail (poster frame) ----
                // Uploaded alongside the media so BOTH parties' bubbles show a
                // real frame instead of Coil trying (and failing) to decode a
                // video URL as an image.
                var thumbnailUrl: String? = null
                val thumbnailPath = task.thumbnailPath
                if (thumbnailPath != null && File(thumbnailPath).exists()) {
                    try {
                        val thumbObjectPath = "$userId/${java.util.UUID.randomUUID()}.jpg"
                        val thumbRequest = Request.Builder()
                            .url("$baseUrl/storage/v1/object/$bucket/$thumbObjectPath")
                            .addHeader("Authorization", "Bearer $token")
                            .addHeader("apikey", anonKey)
                            .addHeader("Content-Type", "image/jpeg")
                            .post(
                                File(thumbnailPath).asRequestBody("image/jpeg".toMediaType())
                            )
                            .build()
                        val thumbResponse = withContext(Dispatchers.IO) {
                            uploadClient.newCall(thumbRequest).execute()
                        }
                        thumbResponse.use { r ->
                            if (r.isSuccessful) {
                                // Bare object path (private bucket — see Task 24 note above).
                                thumbnailUrl = thumbObjectPath
                            } else {
                                Log.w(TAG, "Thumbnail upload failed: ${r.code}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail upload error: ${e.message}")
                    }
                }

                // Update the task with the real media URL — the message layer
                // (completeMediaUpload) creates the server row from this.
                val completedTask = (tasksMap[taskId] ?: task).copy(
                    uploadedBytes = uploadSource.size,
                    isCompleted = true,
                    remainingSeconds = 0,
                    mediaUrl = finalUrl,
                    bucket = bucket,
                    mediaPath = objectPath,
                    thumbnailUrl = thumbnailUrl
                )
                tasksMap[taskId] = completedTask
                refreshState()

                Log.i(TAG, "Upload completed: ${task.fileName} → $finalUrl")
                onUploadComplete(completedTask)

                // No artificial linger — the bubble flips to ✓ the instant the
                // server row is created (delay(1000) removed).
                tasksMap.remove(taskId)
                refreshState()
            } catch (e: CancellationException) {
                // User cancel: cancelUpload already emitted the terminal failed
                // state and fired onUploadFailed — only clean up here.
                if (tasksMap[taskId]?.isFailed != true) {
                    markTaskFailed(task, "Upload cancelled")
                }
                refreshState()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
                val alreadyFailed = tasksMap[taskId]?.isFailed == true
                if (!alreadyFailed) {
                    markTaskFailed(task, e.localizedMessage ?: "Upload failed")
                }
            } finally {
                callMap.remove(task.id)
                jobMap.remove(task.id)
            }
        }
        jobMap[task.id] = job
    }

    /**
     * Emits the terminal failed task state and flips the staged message row
     * to FAILED (Room) so the bubble shows the retry affordance. Idempotent —
     * the cancel path and the error path both funnel through here.
     */
    private fun markTaskFailed(task: UploadTask, reason: String) {
        val failedTask = (tasksMap[task.id] ?: task).copy(
            isFailed = true,
            isCompleted = false,
            errorMessage = reason
        )
        tasksMap[task.id] = failedTask
        refreshState()
        // New coroutine: the caller's job may already be cancelled — the Room
        // status update must still land.
        scope.launch {
            try {
                onUploadFailed(failedTask)
            } catch (e2: Exception) {
                Log.w(TAG, "onUploadFailed callback error: ${e2.message}")
            }
        }
    }

    private fun updateProgress(taskId: String, writtenBytes: Long, speedBytesPerSec: Double, remainingSeconds: Int) {
        val current = tasksMap[taskId] ?: return
        if (current.isCompleted || current.isFailed) return
        tasksMap[taskId] = current.copy(
            uploadedBytes = writtenBytes,
            speedBytesPerSec = speedBytesPerSec,
            remainingSeconds = remainingSeconds.coerceAtLeast(0)
        )
        refreshState()
    }

    /** Resolves the upload mime: picker-provided → extension map → type default. */
    private fun resolveMimeType(task: UploadTask, sourcePath: String): String {
        val provided = task.mimeType?.takeIf { it.isNotBlank() && it != "null" }
        if (provided != null && !provided.endsWith("/*")) {
            // A compressed image is always JPEG now regardless of the source mime.
            if (task.fileType == MessageType.IMAGE && sourcePath.contains("compressed_")) {
                return "image/jpeg"
            }
            return provided.lowercase(Locale.US)
        }
        val fromExt = task.fileName.substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length <= 5 }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return fromExt ?: when (task.fileType) {
            MessageType.IMAGE -> "image/jpeg"
            MessageType.VIDEO -> "video/mp4"
            MessageType.AUDIO -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizedExtension(fileName: String): String {
        val rawExt = if (fileName.contains(".")) fileName.substringAfterLast('.') else "bin"
        return if (rawExt.length in 1..8 && rawExt.all { it.isLetterOrDigit() }) rawExt.lowercase(Locale.US) else "bin"
    }

    /**
     * Streaming upload body — pipes a lazily-opened InputStream straight to
     * the socket through a [CountingSink] so the file never has to fit in the
     * Java heap AND real progress is reported while it flows.
     */
    private class StreamingSourceRequestBody(
        private val contentType: okhttp3.MediaType,
        private val size: Long,
        private val opener: () -> InputStream?,
        private val onProgress: (written: Long, speedBytesPerSec: Double, remainingSeconds: Int) -> Unit
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = contentType
        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            val countingSink = CountingSink(sink, size, onProgress)
            val buffered = countingSink.buffer()
            val source = opener() ?: throw java.io.IOException("Upload source unavailable")
            source.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    buffered.write(buf, 0, read)
                }
                buffered.flush()
            }
        }
    }

    /** Counts every byte handed to OkHttp and throttles task-map updates. */
    private class CountingSink(
        delegate: Sink,
        private val totalSize: Long,
        private val onProgress: (Long, Double, Int) -> Unit
    ) : ForwardingSink(delegate) {
        private var bytesWritten = 0L
        private var lastEmitAt = 0L
        private val samples = ArrayDeque<Pair<Long, Long>>() // (timeMs, cumulativeBytes)

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount

            val now = System.currentTimeMillis()
            if (now - lastEmitAt < PROGRESS_INTERVAL_MS) return
            lastEmitAt = now

            // Rolling speed over the last ~2s of samples (stable, no spikes).
            samples.addLast(now to bytesWritten)
            while (samples.size > 2 && now - samples.first().first > SPEED_WINDOW_MS) {
                samples.removeFirst()
            }
            val (t0, b0) = samples.first()
            val elapsedSec = ((now - t0).coerceAtLeast(1L)) / 1000.0
            val speed = ((bytesWritten - b0) / elapsedSec).coerceAtLeast(0.0)
            val remaining = if (speed > 1.0) (((totalSize - bytesWritten).coerceAtLeast(0L)) / speed).toInt() else 0
            onProgress(bytesWritten, speed, remaining)
        }
    }

    /**
     * Resolves (size, stream-opener) from a content:// URI or file path
     * WITHOUT reading the bytes into memory. Must be called on IO.
     */
    private fun resolveUploadSource(path: String): UploadSource? {
        return try {
            val context = AppServiceContainer.context
            if (path.startsWith("content://")) {
                val uri = android.net.Uri.parse(path)
                var size = -1L
                // Prefer the OpenableColumns SIZE, fall back to the stream length.
                context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0)
                }
                if (size <= 0L) {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                        size = fd.length
                    }
                }
                if (size <= 0L) return null
                UploadSource(size) { context.contentResolver.openInputStream(uri) }
            } else {
                val file = File(path)
                if (!file.exists() || file.length() <= 0L) return null
                UploadSource(file.length()) { file.inputStream() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve upload source: ${e.message}")
            null
        }
    }

    private class UploadSource(val size: Long, val open: () -> InputStream?)

    override fun cancelUpload(taskId: String) {
        // 1. Abort the socket write immediately (job.cancel() alone would wait
        //    for the current blocking write to finish — up to forever).
        callMap.remove(taskId)?.cancel()
        // 2. Kill the coroutine.
        jobMap.remove(taskId)?.cancel()
        // 3. Terminal state + FAILED message row (retry affordance), unless
        //    the upload already finished/failed on its own.
        val task = tasksMap[taskId]
        if (task != null && !task.isCompleted && !task.isFailed) {
            markTaskFailed(task, "Upload cancelled")
        }
    }

    override fun retryUpload(taskId: String) {
        val task = tasksMap[taskId] ?: return
        val fresh = task.copy(
            uploadedBytes = 0L,
            speedBytesPerSec = 0.0,
            remainingSeconds = 0,
            isFailed = false,
            isCompleted = false,
            errorMessage = null
        )
        enqueueUpload(fresh)
    }

    override fun getUploadTaskFlow(taskId: String): Flow<UploadTask?> {
        return activeUploads.map { list -> list.find { it.id == taskId } }
    }

    private fun refreshState() {
        _activeUploads.value = tasksMap.values.toList()
    }
}
