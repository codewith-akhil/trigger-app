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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MediaUrlResolver — single source of truth for turning persisted media
 * coordinates (storedUrl / bucket / objectPath) into a URL that can actually
 * be loaded RIGHT NOW.
 *
 * History:
 *  - v1: rows baked a 1-hour signed URL with no bucket/path → dead media.
 *  - v2: Room persisted mediaBucket + mediaPath (schema v6); chat_media and
 *        voice_notes were PUBLIC, so rows stored permanent /object/public/
 *        URLs and [resolve] rebuilt them from bucket+path.
 *  - v3 (Task 24): chat_media + voice_notes are PRIVATE again (participant-only
 *        storage RLS). Rows now store the BARE OBJECT PATH ("{uid}/{uuid}.ext")
 *        in media_url / media_thumbnail and the bucket in media_bucket; no
 *        permanent URL is ever persisted or exposed. Every render/play fetches
 *        a SHORT-LIVED signed URL (1 h) minted on demand and memoized in
 *        [resignCache]. Stale signed URLs are retained (never evicted) so
 *        previously-viewed media can still be served from Coil's disk cache
 *        (stable cache keys) while offline.
 */
object MediaUrlResolver {

    private const val TAG = "MediaUrlResolver"
    const val CHAT_MEDIA_BUCKET = "chat_media"
    const val VOICE_NOTES_BUCKET = "voice_notes"

    /** Suffix of the in-progress download file before the atomic rename. */
    private const val DOWNLOAD_PART_SUFFIX = ".part"

    /** Buckets whose objects require an authenticated short-lived signed URL. */
    private val PRIVATE_BUCKETS = setOf(CHAT_MEDIA_BUCKET, VOICE_NOTES_BUCKET, "vault_media", "documents", "backups")

    /** Signed-URL lifetime for chat media (seconds) — short-lived by design. */
    const val CHAT_SIGN_TTL_SECONDS = 3_600

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isPrivateBucket(bucket: String?): Boolean {
        val b = bucket?.takeIf { it.isNotBlank() } ?: CHAT_MEDIA_BUCKET
        return b in PRIVATE_BUCKETS
    }

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
     * Recovers the bare object path from whatever is persisted in
     * media_url / media_thumbnail: either the path itself (Task 24 rows) or
     * a legacy public/signed URL that still carries the path.
     */
    fun objectPathOf(stored: String?, bucket: String?, fallbackPath: String?): String? {
        fallbackPath?.takeIf { it.isNotBlank() }?.let { return it }
        val s = stored?.takeIf { it.isNotBlank() } ?: return null
        if (!s.startsWith("http")) {
            // Bare path form ("uid/uuid.ext") — local previews (content://,
            // /data/…, absolute files) are excluded by the slash-shape check.
            if (s.startsWith("content://") || s.startsWith("file://")) return null
            if (s.contains('/') && !s.startsWith("/")) return s
            return null
        }
        return extractObjectPath(s, bucket?.takeIf { it.isNotBlank() } ?: CHAT_MEDIA_BUCKET)
    }

    /**
     * STABLE cache key for Coil (memory + disk): the bucket-qualified object
     * path. Signed URLs rotate, but images must keep hitting the same cache
     * entry across sessions and while offline.
     */
    fun stableCacheKey(bucket: String?, storedUrl: String?, path: String?): String? {
        val b = bucket?.takeIf { it.isNotBlank() } ?: CHAT_MEDIA_BUCKET
        val objectPath = objectPathOf(storedUrl, b, path) ?: return null
        return "$b/$objectPath"
    }

    /**
     * Returns a loadable URL for the given persisted coordinates — WITHOUT
     * any network call (safe to invoke from Room entity mapping on the main
     * thread):
     *  - local previews (content://, file paths) are returned untouched;
     *  - private-bucket coordinates resolve to the last MINTED signed URL
     *    from the memo cache (possibly stale-but-cacheable) or to the stored
     *    value itself; the suspend [resolveWithRefresh] pass upgrades these
     *    to a fresh signature before rendering;
     *  - public-bucket URLs (avatars, stream thumbnails) pass through.
     */
    fun resolve(storedUrl: String?, bucket: String?, path: String?): String? {
        // Local preview of a still-uploading attachment / local recording —
        // hands off.
        if (storedUrl != null && !storedUrl.startsWith("http")) return storedUrl

        val effectiveBucket = bucket?.takeIf { it.isNotBlank() } ?: CHAT_MEDIA_BUCKET

        if (isPrivateBucket(effectiveBucket)) {
            val objectPath = objectPathOf(storedUrl, effectiveBucket, path)
                ?: return storedUrl
            // A previously minted signature (even an expired one — Coil's
            // stable disk-cache key still serves the cached bytes offline).
            resignCache["$effectiveBucket/$objectPath"]?.let { return it.first }
            // Legacy rows may still carry a signed URL in the stored value.
            if (isExpiringUrl(storedUrl)) return storedUrl
            return storedUrl
        }

        // Signed/authenticated URL on a PUBLIC bucket: rebuild a permanent
        // public URL whenever we have (or can recover) the object path.
        if (isExpiringUrl(storedUrl) || storedUrl.isNullOrBlank()) {
            val objectPath = path?.takeIf { it.isNotBlank() }
                ?: storedUrl?.let { extractObjectPath(it, effectiveBucket) }
            if (objectPath != null) {
                return publicUrl(effectiveBucket, objectPath)
            }
            return storedUrl
        }

        // Plain URL already stored — use it as-is.
        return storedUrl
    }

    /** In-memory re-sign cache: "bucket/path" → (url, expiresAtEpochMs).
     *  Expired entries are RETAINED (never evicted) — they double as the
     *  offline fallback so previously-viewed media can still resolve to a
     *  cache-key-stable URL. */
    private val resignCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Suspend resolver for media actually being rendered: private-bucket
     * coordinates get a FRESH short-lived signed URL (cached until 5 min
     * before expiry). Public-bucket URLs go through the sync [resolve].
     * When [forceRefresh] is set the memo cache is bypassed (player retry
     * after a failed/expired signature).
     */
    suspend fun resolveWithRefresh(
        storedUrl: String?,
        bucket: String?,
        path: String?,
        forceRefresh: Boolean = false
    ): String? {
        val effectiveBucket = bucket?.takeIf { it.isNotBlank() } ?: CHAT_MEDIA_BUCKET
        if (isPrivateBucket(effectiveBucket)) {
            val objectPath = objectPathOf(storedUrl, effectiveBucket, path)
            if (objectPath != null) {
                val key = "$effectiveBucket/$objectPath"
                val cached = resignCache[key]
                val now = System.currentTimeMillis()
                if (!forceRefresh && cached != null && cached.second > now + 5 * 60_000L) {
                    return cached.first
                }
                val fresh = refreshSignedUrl(effectiveBucket, objectPath, CHAT_SIGN_TTL_SECONDS)
                if (fresh != null) {
                    resignCache[key] = fresh to (now + (CHAT_SIGN_TTL_SECONDS - 300L) * 1000L)
                    return fresh
                }
                // Signing failed (offline / revoked): fall back to the last
                // known signature, then to whatever was stored.
                cached?.let { return it.first }
                if (storedUrl != null && storedUrl.startsWith("http")) return storedUrl
                return null
            }
        } else if (storedUrl != null && isExpiringUrl(storedUrl)) {
            val objectPath = path?.takeIf { it.isNotBlank() }
                ?: extractObjectPath(storedUrl, effectiveBucket)
            if (objectPath != null) {
                val key = "$effectiveBucket/$objectPath"
                val cached = resignCache[key]
                val now = System.currentTimeMillis()
                if (!forceRefresh && cached != null && cached.second > now + 5 * 60_000L) {
                    return cached.first
                }
                val fresh = refreshSignedUrl(effectiveBucket, objectPath)
                if (fresh != null) {
                    resignCache[key] = fresh to (now + (604_800L - 3_600L) * 1000L)
                    return fresh
                }
                cached?.let { return it.first }
            }
        }
        return resolve(storedUrl, bucket, path)
    }

    /** Permanent public URL for a public bucket (avatars, stream thumbs). */
    fun publicUrl(bucket: String, objectPath: String): String {
        val base = com.example.config.BackendConfig.SUPABASE_URL
        return "$base/storage/v1/object/public/$bucket/$objectPath"
    }

    /**
     * Phase 3 media persistence — streams [url] into [target] over the SAME
     * OkHttp client the signed-URL minting uses. Bytes land in
     * "<target>.part" first and are atomically renamed on success, so
     * [target] is either a complete file or absent — never truncated.
     *
     * Never throws: any failure (offline, revoked signature, disk full) is
     * logged, the .part file is cleaned up and false is returned — the caller
     * keeps the row's localMediaPath null and the UI falls back to the URL
     * pipeline. Dispatches onto [Dispatchers.IO] internally.
     */
    suspend fun downloadToFile(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
        val part = File(target.absolutePath + DOWNLOAD_PART_SUFFIX)
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "downloadToFile: HTTP ${response.code} for ${url.take(120)}")
                    return@withContext false
                }
                val body = response.body ?: run {
                    Log.w(TAG, "downloadToFile: empty body for ${url.take(120)}")
                    return@withContext false
                }
                body.byteStream().use { input ->
                    part.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (part.length() <= 0L) {
                part.delete()
                return@withContext false
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                // Cross-filesystem rename fallback (same tree in practice —
                // cheap insurance against exotic mount layouts).
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "downloadToFile failed: ${t.message}")
            try {
                part.delete()
            } catch (_: Exception) {
                // cleanup only — never rethrow
            }
            false
        }
    }

    /**
     * Signs an object through the Storage API
     * (POST /storage/v1/object/sign/{bucket}/{path}). Storage RLS authorizes
     * the caller — only the conversation participants (or the owner) succeed.
     */
    suspend fun refreshSignedUrl(bucket: String, objectPath: String, expiresIn: Int = 604_800): String? {
        return withContext(Dispatchers.IO) {
            try {
                val supabaseClient = AppServiceContainer.supabaseClient
                val baseUrl = com.example.config.BackendConfig.SUPABASE_URL
                val token = supabaseClient.ensureFreshAccessToken()
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
                        when {
                            // Storage returns "/object/sign/{bucket}/{path}?token=…"
                            // — it must be fetched under /storage/v1 (verified
                            // live: {base}{signedPath} 404s, {base}/storage/v1
                            // {signedPath} 200s).
                            signedPath.startsWith("http") -> signedPath
                            signedPath.startsWith("/storage/v1/") -> "$baseUrl$signedPath"
                            else -> "$baseUrl/storage/v1$signedPath"
                        }
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
