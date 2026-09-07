package com.example.service

import android.util.Log
import com.example.di.AppServiceContainer
import com.example.model.UploadTask
import com.example.service.MediaUrlResolver
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

/**
 * UploadServiceImpl — REAL upload via the upload-chat-media edge function.
 *
 * Reads the file from the Android content:// URI, sends it as raw bytes to
 * the edge function (which validates size server-side + uploads to Storage),
 * and reports real progress.
 */
class UploadServiceImpl(
    private val scope: CoroutineScope,
    private val onUploadComplete: suspend (UploadTask) -> Unit = {},
    private val onUploadFailed: suspend (UploadTask) -> Unit = {}
) : UploadService {

    companion object {
        private const val TAG = "UploadServiceImpl"
    }

    private val _activeUploads = MutableStateFlow<List<UploadTask>>(emptyList())
    override val activeUploads = _activeUploads.asStateFlow()

    private val jobMap = ConcurrentHashMap<String, Job>()
    private val tasksMap = ConcurrentHashMap<String, UploadTask>()

    // OkHttp client with longer timeouts for file uploads
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun enqueueUpload(task: UploadTask) {
        tasksMap[task.id] = task
        refreshState()

        val job = scope.launch {
            try {
                // Resolve the upload source WITHOUT materializing the whole
                // file in RAM (a 250 MB video previously did readBytes() →
                // OOM). We only query its size; bytes stream during upload.
                val uploadSource = withContext(Dispatchers.IO) {
                    resolveUploadSource(task.filePath ?: "")
                }
                if (uploadSource == null || uploadSource.size <= 0L) {
                    throw Exception("Could not read file")
                }

                // Determine the file type for the edge function
                val fileType = when (task.fileType) {
                    com.example.model.MessageType.IMAGE -> "IMAGE"
                    com.example.model.MessageType.VIDEO -> "VIDEO"
                    com.example.model.MessageType.AUDIO -> "AUDIO"
                    else -> "DOCUMENT"
                }

                // Build the upload request to the edge function
                val supabaseClient = AppServiceContainer.supabaseClient
                val baseUrl = com.example.config.BackendConfig.SUPABASE_URL
                // Refresh-before-use: a stale ~1 h old access token made every
                // upload 401 until some other call happened to refresh it.
                val token = supabaseClient.ensureFreshAccessToken()
                    ?: com.example.config.BackendConfig.SUPABASE_ANON_KEY
                val anonKey = com.example.config.BackendConfig.SUPABASE_ANON_KEY

                // Update progress to "uploading"
                val uploadingTask = task.copy(uploadedBytes = 0L, isCompleted = false)
                tasksMap[task.id] = uploadingTask
                refreshState()

                // Create the STREAMING request body — bytes are piped from the
                // content:// URI / file straight to the socket.
                val mimeType = task.mimeType ?: "application/octet-stream"
                val requestBody = StreamingSourceRequestBody(
                    contentType = mimeType.toMediaType(),
                    size = uploadSource.size,
                    opener = uploadSource.open
                )

                // x-file-name must be Latin-1 per OkHttp — non-ASCII filenames
                // (e.g. Hindi) previously crashed the header write. Percent-
                // encode; the edge function decodes it.
                val encodedFileName = android.net.Uri.encode(task.fileName)
                val request = Request.Builder()
                    .url("$baseUrl/functions/v1/upload-chat-media")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", mimeType)
                    .addHeader("x-file-type", fileType)
                    .addHeader("x-file-name", encodedFileName)
                    .addHeader("x-mime-type", mimeType)
                    .addHeader("x-file-size", uploadSource.size.toString())
                    .post(requestBody)
                    .build()

                // Execute the upload — OkHttp calls MUST leave the caller's
                // (main) dispatcher; previously execute() ran on Main and
                // threw NetworkOnMainThreadException on EVERY media send.
                val response = withContext(Dispatchers.IO) {
                    uploadClient.newCall(request).execute()
                }
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val mediaUrl = json.optString("url", "")
                    val bucket = json.optString("bucket", MediaUrlResolver.CHAT_MEDIA_BUCKET)

                    // The edge function returns the PUBLIC url and chat_media is
                    // a public bucket (migration 20260918_chat_fixes.sql), so
                    // the plain url is permanent — no more 7-day signing that
                    // used to brick every chat image after a week. The object
                    // path is persisted too so any device can rebuild the url
                    // from scratch (or re-sign, for private buckets).
                    val objectPath = MediaUrlResolver.extractObjectPath(mediaUrl, bucket)
                        ?: task.mediaPath
                    val finalUrl = if (mediaUrl.isNotEmpty()) {
                        if (bucket == MediaUrlResolver.CHAT_MEDIA_BUCKET) {
                            MediaUrlResolver.resolve(mediaUrl, bucket, objectPath)
                        } else {
                            // Private bucket — keep the signed-url behaviour.
                            fetchSignedUrl(bucket, extractObjectPath(mediaUrl, bucket))
                                ?: mediaUrl
                        }
                    } else {
                        mediaUrl
                    }

                    // Update the message with the real media URL
                    val completedTask = task.copy(
                        uploadedBytes = uploadSource.size,
                        isCompleted = true,
                        remainingSeconds = 0,
                        mediaUrl = finalUrl,
                        bucket = bucket,
                        mediaPath = objectPath
                    )
                    tasksMap[task.id] = completedTask
                    refreshState()

                    Log.i(TAG, "Upload completed: ${task.fileName} → $finalUrl")
                    onUploadComplete(completedTask)

                    // Remove after a short delay
                    delay(1000)
                    tasksMap.remove(task.id)
                    refreshState()
                } else {
                    throw Exception("Upload failed: HTTP ${response.code} - $responseBody")
                }

            } catch (e: CancellationException) {
                tasksMap.remove(task.id)
                refreshState()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
                val failedTask = task.copy(
                    isFailed = true,
                    errorMessage = e.localizedMessage ?: "Upload failed"
                )
                tasksMap[task.id] = failedTask
                refreshState()
                // Notify the message layer so the staged message flips to
                // FAILED and shows the retry affordance (was stuck SENDING).
                try {
                    onUploadFailed(failedTask)
                } catch (e2: Exception) {
                    Log.w(TAG, "onUploadFailed callback error: ${e2.message}")
                }
            }
        }
        jobMap[task.id] = job
    }

    /**
     * Streaming upload body — wraps a lazily-opened InputStream so the file
     * never has to fit in the Java heap.
     */
    private class StreamingSourceRequestBody(
        private val contentType: okhttp3.MediaType,
        private val size: Long,
        private val opener: () -> InputStream?
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = contentType
        override fun contentLength(): Long = size
        override fun writeTo(sink: okio.BufferedSink) {
            val source = opener() ?: throw java.io.IOException("Upload source unavailable")
            source.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    sink.write(buf, 0, read)
                }
            }
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
                val file = java.io.File(path)
                if (!file.exists() || file.length() <= 0L) return null
                UploadSource(file.length()) { file.inputStream() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve upload source: ${e.message}")
            null
        }
    }

    private class UploadSource(val size: Long, val open: () -> InputStream?)

    /**
     * Extracts the object path (the part after /object/public/{bucket}/) from
     * a Supabase public URL.
     */
    private fun extractObjectPath(publicUrl: String, bucket: String): String {
        val marker = "/storage/v1/object/public/$bucket/"
        val idx = publicUrl.indexOf(marker)
        return if (idx >= 0) {
            publicUrl.substring(idx + marker.length)
        } else {
            // Fallback: assume the URL is just the path.
            publicUrl.substringAfterLast("/")
        }
    }

    /**
     * Calls the Supabase Storage API to create a signed URL for a private-bucket
     * object. Returns null on failure.
     *
     *   POST /storage/v1/object/sign/{bucket}/{path}
     *   Body: { "expiresIn": 604800 }
     *   Response: { "signedURL": "/storage/v1/object/sign/...?token=..." }
     *
     * Tries the 7-day max expiry first (long-lived message media), falling
     * back to 1 hour if the project rejects the long expiry.
     */
    private suspend fun fetchSignedUrl(bucket: String, objectPath: String): String? {
        return withContext(Dispatchers.IO) {
            fetchSignedUrlWithExpiry(bucket, objectPath, 604_800)
                ?: fetchSignedUrlWithExpiry(bucket, objectPath, 3_600)
        }
    }

    private suspend fun fetchSignedUrlWithExpiry(bucket: String, objectPath: String, expiresIn: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val supabaseClient = AppServiceContainer.supabaseClient
                val baseUrl = com.example.config.BackendConfig.SUPABASE_URL
                val token = supabaseClient.currentSession?.accessToken
                    ?: com.example.config.BackendConfig.SUPABASE_ANON_KEY
                val anonKey = com.example.config.BackendConfig.SUPABASE_ANON_KEY

                val body = JSONObject().put("expiresIn", expiresIn).toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/storage/v1/object/sign/$bucket/$objectPath")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = uploadClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val signedPath = json.optString("signedURL", "")
                    if (signedPath.isNotEmpty()) {
                        // signedURL is a relative path — prepend the base URL
                        if (signedPath.startsWith("http")) signedPath
                        else "$baseUrl$signedPath"
                    } else null
                } else {
                    Log.w(TAG, "Signed URL request failed: ${response.code} - $responseBody")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchSignedUrl failed: ${e.message}")
                null
            }
        }
    }

    override fun cancelUpload(taskId: String) {
        jobMap[taskId]?.cancel()
        jobMap.remove(taskId)
        tasksMap.remove(taskId)
        refreshState()
    }

    override fun retryUpload(taskId: String) {
        val task = tasksMap[taskId] ?: return
        val fresh = task.copy(
            uploadedBytes = 0L,
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
