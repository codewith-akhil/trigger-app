package com.example.service

import android.util.Log
import com.example.di.AppServiceContainer
import com.example.model.UploadTask
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
                // Get the file bytes from the content:// URI
                val context = AppServiceContainer.context
                val fileBytes = readFileBytes(task.filePath ?: "")

                if (fileBytes == null || fileBytes.isEmpty()) {
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
                val token = supabaseClient.currentSession?.accessToken
                    ?: com.example.config.BackendConfig.SUPABASE_ANON_KEY
                val anonKey = com.example.config.BackendConfig.SUPABASE_ANON_KEY

                // Update progress to "uploading"
                val uploadingTask = task.copy(uploadedBytes = 0L, isCompleted = false)
                tasksMap[task.id] = uploadingTask
                refreshState()

                // Create the request body with the file bytes
                val mimeType = task.mimeType ?: "application/octet-stream"
                val requestBody = fileBytes.toRequestBody(mimeType.toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/functions/v1/upload-chat-media")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", mimeType)
                    .addHeader("x-file-type", fileType)
                    .addHeader("x-file-name", task.fileName)
                    .addHeader("x-mime-type", mimeType)
                    .addHeader("x-file-size", fileBytes.size.toString())
                    .post(requestBody)
                    .build()

                // Execute the upload
                val response = uploadClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val mediaUrl = json.optString("url", "")
                    val bucket = json.optString("bucket", "chat_media")

                    // ALL chat buckets are private, so the public URL would 403
                    // for the recipient. Sign EVERY url with the max expiry
                    // (7 days) so the recipient can actually load the media.
                    // Signed URLs bypass Storage RLS at GET time — the signature
                    // itself grants read access to whoever holds the link.
                    val finalUrl = if (mediaUrl.isNotEmpty()) {
                        fetchSignedUrl(bucket, extractObjectPath(mediaUrl, bucket))
                            ?: mediaUrl  // fallback: post-migration public URL
                    } else {
                        mediaUrl
                    }

                    // Update the message with the real media URL
                    val completedTask = task.copy(
                        uploadedBytes = fileBytes.size.toLong(),
                        isCompleted = true,
                        remainingSeconds = 0,
                        mediaUrl = finalUrl,
                        bucket = bucket
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
     * Reads file bytes from a content:// URI or file path.
     */
    private fun readFileBytes(path: String): ByteArray? {
        return try {
            val context = AppServiceContainer.context
            if (path.startsWith("content://")) {
                val uri = android.net.Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                    stream.readBytes()
                }
            } else {
                java.io.File(path).readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: ${e.message}")
            null
        }
    }

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
