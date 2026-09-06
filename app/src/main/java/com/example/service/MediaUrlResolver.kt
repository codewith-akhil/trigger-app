package com.example.service

import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MediaUrlResolver — single source of truth for turning persisted media
 * coordinates (storedUrl / bucket / objectPath) into a URL that can actually
 * be loaded RIGHT NOW.
 *
 * History: messages used to bake a 1-hour (later 7-day) SIGNED url into the
 * row and nothing else. After expiry every device showed dead media forever
 * because neither the bucket nor the object path had been kept. Now:
 *  - Room persists mediaBucket + mediaPath (schema v6);
 *  - [resolve] rebuilds a permanent PUBLIC url whenever the stored url is a
 *    signed/authenticated one or missing (chat_media is public since
 *    migration 20260918_chat_fixes.sql);
 *  - [refreshSignedUrl] stays available for PRIVATE buckets (re-sign through
 *    the Storage API with a 7-day expiry).
 */
object MediaUrlResolver {

    private const val TAG = "MediaUrlResolver"
    const val CHAT_MEDIA_BUCKET = "chat_media"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** True when the URL carries an expiring signature instead of being plain public. */
    fun isExpiringUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("/object/sign/") ||
            url.contains("/object/authenticated/") ||
            url.contains("token=")
    }

    /** Extracts the object path from a public or signed Supabase storage URL. */
    fun extractObjectPath(url: String, bucket: String): String? {
        for (marker in listOf(
            "/storage/v1/object/public/$bucket/",
            "/storage/v1/object/sign/$bucket/",
            "/storage/v1/object/authenticated/$bucket/"
        )) {
            val idx = url.indexOf(marker)
            if (idx >= 0) {
                // strip any "?token=..." query string
                return url.substring(idx + marker.length).substringBefore('?')
            }
        }
        return null
    }

    /**
     * Returns a loadable URL for the given persisted coordinates.
     *
     * Order of preference:
     *  1. stored URL when it is already a plain public/https url that never
     *     expires AND (when a path is known) actually points at that path;
     *  2. rebuilt public URL from bucket+path (bucket is public);
     *  3. stored URL as last resort (content:// previews of staged uploads
     *     are returned untouched).
     */
    fun resolve(storedUrl: String?, bucket: String?, path: String?): String? {
        // Local preview of a still-uploading attachment — hands off.
        if (storedUrl != null && !storedUrl.startsWith("http")) return storedUrl

        val effectiveBucket = bucket?.takeIf { it.isNotBlank() } ?: CHAT_MEDIA_BUCKET

        // Signed/authenticated URL: rebuild a permanent public URL whenever we
        // have (or can recover) the object path.
        if (isExpiringUrl(storedUrl) || storedUrl.isNullOrBlank()) {
            val objectPath = path?.takeIf { it.isNotBlank() }
                ?: storedUrl?.let { extractObjectPath(it, effectiveBucket) }
            if (objectPath != null) {
                return publicUrl(effectiveBucket, objectPath)
            }
            // No path recoverable — fall through and return the stored URL
            // (it may still be within its validity window).
            return storedUrl
        }

        // Plain URL already stored — use it as-is.
        return storedUrl
    }

    /** Permanent public URL for a public bucket. */
    fun publicUrl(bucket: String, objectPath: String): String {
        val base = com.example.config.BackendConfig.SUPABASE_URL
        return "$base/storage/v1/object/public/$bucket/$objectPath"
    }

    /**
     * Re-signs an object in a PRIVATE bucket through the Storage API
     * (POST /storage/v1/object/sign/{bucket}/{path}, 7-day expiry with a
     * 1-hour fallback). Used when the bucket is not public.
     */
    suspend fun refreshSignedUrl(bucket: String, objectPath: String, expiresIn: Int = 604_800): String? {
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

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val signedPath = JSONObject(responseBody).optString("signedURL", "")
                    if (signedPath.isNotEmpty()) {
                        if (signedPath.startsWith("http")) signedPath else "$baseUrl$signedPath"
                    } else null
                } else {
                    // Retry with the 1-hour fallback the project may enforce.
                    if (expiresIn > 3_600) refreshSignedUrl(bucket, objectPath, 3_600) else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshSignedUrl failed: ${e.message}")
                null
            }
        }
    }

    /** True when the string looks like a PostgreSQL UUID. */
    fun isUuid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.length == 36 && value.count { it == '-' } == 4 &&
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
    }
}
