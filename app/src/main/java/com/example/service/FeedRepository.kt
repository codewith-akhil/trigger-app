package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.model.CurrencyOption
import com.example.model.FeedMediaItem
import com.example.model.FeedPost
import com.example.model.PostComment
import com.example.model.PostMediaType
import com.example.model.PostType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Result of a backend feed operation.
 */
sealed class FeedResult {
    data object Success : FeedResult()
    /** Backend (SQL / edge functions) not deployed yet — honest degraded mode. */
    data class BackendMissing(val detail: String) : FeedResult()
    data class Error(val message: String) : FeedResult()
}

/**
 * REAL feed backend — every post, draft and media file lives in Supabase
 * (tables `feed_posts` / `feed_post_media` / `post_unlocks`, bucket
 * `feed-media`). There is NO mock or seed data in the app anymore.
 *
 * Likes and comments are session overlays for now (in-memory) — they reset
 * on app restart and are never faked with counts.
 */
class FeedRepository(context: Context) {

    private val TAG = "FeedRepository"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("trigger_feed_session", Context.MODE_PRIVATE)

    private val client get() = AppServiceContainer.supabaseClient

    private val _posts = MutableStateFlow<List<FeedPost>>(emptyList())
    val posts: StateFlow<List<FeedPost>> = _posts.asStateFlow()

    private val _drafts = MutableStateFlow<List<FeedPost>>(emptyList())
    val drafts: StateFlow<List<FeedPost>> = _drafts.asStateFlow()

    private val _currencies = MutableStateFlow<List<CurrencyOption>>(emptyList())
    val currencies: StateFlow<List<CurrencyOption>> = _currencies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** null = unknown yet, false = backend tables/functions missing. */
    private val _backendMissing = MutableStateFlow<String?>(null)
    val backendMissing: StateFlow<String?> = _backendMissing.asStateFlow()

    // Session-only engagement overlays (NOT persisted, never seeded)
    private val likedPostIds = mutableSetOf<String>()
    private val likeDeltas = mutableMapOf<String, Int>()
    private val commentsByPost = mutableMapOf<String, MutableList<PostComment>>()
    private val likedCommentIds = mutableSetOf<String>()

    private val _reportedCommentIds = MutableStateFlow<Set<String>>(emptySet())
    val reportedCommentIds: StateFlow<Set<String>> = _reportedCommentIds.asStateFlow()

    private var nextOffset: Int? = 0

    init {
        loadReportedComments()
    }

    // --------------------------------------------------------------------
    // Mapping
    // --------------------------------------------------------------------
    private fun parsePost(obj: JSONObject): FeedPost {
        val mediaItems = mutableListOf<FeedMediaItem>()
        val mediaUrls = mutableListOf<String>()
        val previewUrls = mutableListOf<String>()
        val mediaArr = obj.optJSONArray("media") ?: JSONArray()
        for (i in 0 until mediaArr.length()) {
            val m = mediaArr.getJSONObject(i)
            val item = FeedMediaItem(
                url = m.optString("url", "").takeIf { it.isNotBlank() },
                previewUrl = m.optString("previewUrl", "").takeIf { it.isNotBlank() },
                mimeType = m.optString("mimeType", "image/jpeg"),
                width = if (m.has("width") && !m.isNull("width")) m.optInt("width") else null,
                height = if (m.has("height") && !m.isNull("height")) m.optInt("height") else null,
                durationMs = if (m.has("durationMs") && !m.isNull("durationMs")) m.optLong("durationMs") else null
            )
            mediaItems.add(item)
            item.url?.let { mediaUrls.add(it) }
            item.previewUrl?.let { previewUrls.add(it) }
        }

        val comments = commentsByPost[obj.optString("id")] ?: emptyList()
        val likeDelta = likeDeltas[obj.optString("id")] ?: 0
        val isMine = obj.optBoolean("isMine", false)

        return FeedPost(
            id = obj.optString("id"),
            authorId = obj.optString("authorId"),
            authorName = obj.optString("authorName", "Creator"),
            authorUsername = obj.optString("authorUsername", "creator"),
            authorAvatarUrl = obj.optString("authorAvatarUrl", "").takeIf { it.isNotBlank() },
            description = obj.optString("description", ""),
            mediaType = if (obj.optString("mediaType") == "VIDEO") PostMediaType.VIDEO else PostMediaType.IMAGE,
            postType = if (obj.optString("postType") == "PAID") PostType.PAID else PostType.FREE,
            priceAmount = obj.optDouble("priceAmount", 0.0),
            currency = obj.optString("currency", "INR"),
            timestamp = obj.optLong("publishedAt", 0L).takeIf { it > 0 }
                ?: (obj.optString("publishedAt").takeIf { it.isNotBlank() }?.let { parseIsoDate(it) }
                    ?: System.currentTimeMillis()),
            likesCount = likeDelta.coerceAtLeast(0),
            isLikedByMe = likedPostIds.contains(obj.optString("id")),
            commentsCount = comments.size,
            comments = comments,
            isUnlocked = obj.optBoolean("isUnlocked", false),
            isMine = isMine,
            isDraft = obj.optString("status") == "draft",
            mediaUrls = mediaUrls,
            lockedPreviewUrls = previewUrls,
            mediaItems = mediaItems
        )
    }

    private fun parseIsoDate(iso: String): Long? = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(iso)?.time
        } catch (e2: Exception) {
            null
        }
    }

    private fun backendMissingResult(detail: String): FeedResult.BackendMissing {
        _backendMissing.value = detail
        return FeedResult.BackendMissing(detail)
    }

    private fun mapFunctionError(message: String): FeedResult {
        val lower = message.lowercase()
        return when {
            lower.contains("relation") && lower.contains("does not exist") ||
                lower.contains("schema cache") ->
                FeedResult.BackendMissing("Feed database tables are not deployed yet. Run supabase/migrations/20260911_feed_system.sql.")
            lower.contains("404") ->
                FeedResult.BackendMissing("Feed backend functions are not deployed yet (get-feed / save-feed-post).")
            else -> FeedResult.Error(message)
        }
    }

    // --------------------------------------------------------------------
    // Feed loading (newest first — the edge function orders published_at DESC)
    // --------------------------------------------------------------------
    suspend fun refresh(): FeedResult {
        _isLoading.value = true
        try {
            val res = client.invokeFunction("get-feed", JSONObject().put("scope", "feed").put("limit", 20).put("offset", 0))
            return when (res) {
                is SupabaseResult.Success -> {
                    nextOffset = if (res.data.has("nextOffset") && !res.data.isNull("nextOffset")) res.data.optInt("nextOffset") else null
                    val list = mutableListOf<FeedPost>()
                    val arr = res.data.optJSONArray("posts") ?: JSONArray()
                    for (i in 0 until arr.length()) list.add(parsePost(arr.getJSONObject(i)))
                    _posts.value = list
                    _backendMissing.value = null
                    FeedResult.Success
                }
                is SupabaseResult.Error -> {
                    Log.e(TAG, "refresh failed: ${res.message}")
                    val mapped = mapFunctionError(res.message)
                    if (mapped is FeedResult.BackendMissing) backendMissingResult(mapped.detail) else FeedResult.Error(res.message)
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /** Older posts (pagination). Newest are always at the top of [_posts]. */
    suspend fun loadMore(): FeedResult {
        val offset = nextOffset ?: return FeedResult.Success
        _isLoadingMore.value = true
        try {
            val res = client.invokeFunction("get-feed", JSONObject().put("scope", "feed").put("limit", 20).put("offset", offset))
            return when (res) {
                is SupabaseResult.Success -> {
                    nextOffset = if (res.data.has("nextOffset") && !res.data.isNull("nextOffset")) res.data.optInt("nextOffset") else null
                    val arr = res.data.optJSONArray("posts") ?: JSONArray()
                    val more = mutableListOf<FeedPost>()
                    for (i in 0 until arr.length()) more.add(parsePost(arr.getJSONObject(i)))
                    _posts.value = _posts.value + more
                    FeedResult.Success
                }
                is SupabaseResult.Error -> FeedResult.Error(res.message)
            }
        } finally {
            _isLoadingMore.value = false
        }
    }

    /** Re-fetch a single post (e.g. after payment) and splice it into place. */
    suspend fun getPostLive(postId: String): FeedPost? {
        val res = client.invokeFunction(
            "get-feed",
            JSONObject().put("scope", "post").put("postId", postId)
        )
        return when (res) {
            is SupabaseResult.Success -> {
                val arr = res.data.optJSONArray("posts") ?: return null
                if (arr.length() == 0) return null
                val fresh = parsePost(arr.getJSONObject(0))
                _posts.value = _posts.value.map { if (it.id == fresh.id) fresh else it }
                fresh
            }
            is SupabaseResult.Error -> null
        }
    }

    // --------------------------------------------------------------------
    // Drafts
    // --------------------------------------------------------------------
    suspend fun refreshDrafts(): FeedResult {
        val res = client.invokeFunction("get-feed", JSONObject().put("scope", "drafts"))
        return when (res) {
            is SupabaseResult.Success -> {
                val arr = res.data.optJSONArray("posts") ?: JSONArray()
                val list = mutableListOf<FeedPost>()
                for (i in 0 until arr.length()) list.add(parsePost(arr.getJSONObject(i)))
                _drafts.value = list
                FeedResult.Success
            }
            is SupabaseResult.Error -> mapFunctionError(res.message)
        }
    }

    // --------------------------------------------------------------------
    // Currency picker — backend `countries` table (fx rates included)
    // --------------------------------------------------------------------
    suspend fun loadCurrencies(): Boolean {
        if (_currencies.value.isNotEmpty()) return true
        val res = client.getTable(
            "countries",
            "select=currency_code,country_name,currency_symbol,fx_rate&currency_code=not.is.null&order=currency_code.asc"
        )
        return when (res) {
            is SupabaseResult.Success -> {
                val list = mutableListOf<CurrencyOption>()
                for (i in 0 until res.data.length()) {
                    val o = res.data.getJSONObject(i)
                    val code = o.optString("currency_code", "")
                    if (code.isNotBlank()) {
                        list.add(
                            CurrencyOption(
                                code = code,
                                symbol = o.optString("currency_symbol", "").takeIf { it.isNotBlank() },
                                countryName = o.optString("country_name", "").takeIf { it.isNotBlank() },
                                fxRate = o.optDouble("fx_rate", 1.0)
                            )
                        )
                    }
                }
                _currencies.value = list
                list.isNotEmpty()
            }
            is SupabaseResult.Error -> false
        }
    }

    // --------------------------------------------------------------------
    // Post writes (save-feed-post) — backend generates the id
    // --------------------------------------------------------------------
    suspend fun createPost(
        description: String,
        mediaType: PostMediaType,
        postType: PostType,
        priceAmount: Double,
        currency: String
    ): FeedResultWithId {
        val payload = JSONObject()
            .put("action", "create")
            .put("description", description.take(300))
            .put("mediaType", mediaType.name.lowercase())
            .put("postType", postType.name.lowercase())
            .put("currency", currency.uppercase())
        if (postType == PostType.PAID) payload.put("priceAmount", priceAmount)

        val res = client.invokeFunction("save-feed-post", payload)
        return when (res) {
            is SupabaseResult.Success -> {
                val postId = res.data.optString("postId", "")
                if (postId.isBlank()) FeedResultWithId.Error("Backend returned no post id")
                else FeedResultWithId.Success(postId)
            }
            is SupabaseResult.Error -> mapFunctionError(res.message).let {
                when (it) {
                    is FeedResult.BackendMissing -> FeedResultWithId.BackendMissing(it.detail)
                    is FeedResult.Error -> FeedResultWithId.Error(it.message)
                    FeedResult.Success -> FeedResultWithId.Error(res.message)
                }
            }
        }
    }

    /** Save draft metadata (Instagram-style drafts live on the backend). */
    suspend fun saveDraft(
        postId: String,
        description: String,
        postType: PostType,
        priceAmount: Double,
        currency: String
    ): FeedResult = updatePost(postId, description, postType, priceAmount, currency)

    suspend fun publishPost(
        postId: String,
        description: String,
        postType: PostType,
        priceAmount: Double,
        currency: String
    ): FeedResult {
        val payload = JSONObject()
            .put("action", "publish")
            .put("postId", postId)
            .put("description", description.take(300))
            .put("postType", postType.name.lowercase())
            .put("currency", currency.uppercase())
        if (postType == PostType.PAID) payload.put("priceAmount", priceAmount)

        val res = client.invokeFunction("save-feed-post", payload)
        return when (res) {
            is SupabaseResult.Success -> {
                // drop from drafts, prepend to feed (newest first)
                _drafts.value = _drafts.value.filter { it.id != postId }
                FeedResult.Success
            }
            is SupabaseResult.Error -> mapFunctionError(res.message)
        }
    }

    suspend fun deletePost(postId: String): FeedResult {
        val res = client.invokeFunction(
            "save-feed-post",
            JSONObject().put("action", "delete").put("postId", postId)
        )
        return when (res) {
            is SupabaseResult.Success -> {
                _posts.value = _posts.value.filter { it.id != postId }
                _drafts.value = _drafts.value.filter { it.id != postId }
                FeedResult.Success
            }
            is SupabaseResult.Error -> mapFunctionError(res.message)
        }
    }

    private suspend fun updatePost(
        postId: String,
        description: String,
        postType: PostType,
        priceAmount: Double,
        currency: String
    ): FeedResult {
        val payload = JSONObject()
            .put("action", "update")
            .put("postId", postId)
            .put("description", description.take(300))
            .put("postType", postType.name.lowercase())
            .put("currency", currency.uppercase())
        if (postType == PostType.PAID) payload.put("priceAmount", priceAmount)

        val res = client.invokeFunction("save-feed-post", payload)
        return when (res) {
            is SupabaseResult.Success -> FeedResult.Success
            is SupabaseResult.Error -> mapFunctionError(res.message)
        }
    }

    // --------------------------------------------------------------------
    // Media upload — private `feed-media` bucket, path {userId}/{postId}/{i}.{ext}
    // --------------------------------------------------------------------
    suspend fun uploadFeedMedia(
        postId: String,
        position: Int,
        bytes: ByteArray,
        mimeType: String,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null
    ): FeedResultWithPath {
        val userId = client.currentUser?.id
            ?: return FeedResultWithPath.Error("Not signed in")
        val ext = when {
            mimeType.contains("mp4") || mimeType.contains("video") -> "mp4"
            mimeType.contains("png") -> "png"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
        val path = "$userId/$postId/$position.$ext"
        val upload = client.uploadFile(
            bucketName = "feed-media",
            fileName = path,
            fileBytes = bytes,
            mimeType = mimeType
        )
        return when (upload) {
            is SupabaseResult.Success -> FeedResultWithPath.Success(path)
            is SupabaseResult.Error -> {
                val lower = upload.message.lowercase()
                if (lower.contains("bucket") && lower.contains("not found") ||
                    lower.contains("404") || lower.contains("409")) {
                    FeedResultWithPath.BackendMissing("feed-media storage bucket is missing. Run the feed SQL migration.")
                } else {
                    FeedResultWithPath.Error(upload.message)
                }
            }
        }
    }

    /**
     * Attach final media metadata rows to a post (replace existing).
     * Called right before publish / draft save.
     */
    suspend fun attachMediaMetadata(
        postId: String,
        items: List<Triple<String, String, Pair<Int, Int>?>>, // (storagePath, mimeType, (w,h)?)
        durationMs: Long? = null
    ): FeedResult {
        val mediaArr = JSONArray()
        items.forEachIndexed { index, (path, mime, dims) ->
            val o = JSONObject()
                .put("position", index)
                .put("storagePath", path)
                .put("mimeType", mime)
            dims?.let { o.put("width", it.first).put("height", it.second) }
            durationMs?.let { o.put("durationMs", it) }
            mediaArr.put(o)
        }
        val res = client.invokeFunction(
            "save-feed-post",
            JSONObject().put("action", "update").put("postId", postId).put("media", mediaArr)
        )
        return when (res) {
            is SupabaseResult.Success -> FeedResult.Success
            is SupabaseResult.Error -> mapFunctionError(res.message)
        }
    }

    // --------------------------------------------------------------------
    // Session engagement overlays
    // --------------------------------------------------------------------
    fun getPost(postId: String): FeedPost? =
        _posts.value.find { it.id == postId } ?: _drafts.value.find { it.id == postId }

    fun toggleLike(postId: String) {
        val willLike = !likedPostIds.contains(postId)
        if (willLike) likedPostIds.add(postId) else likedPostIds.remove(postId)
        likeDeltas[postId] = ((likeDeltas[postId] ?: 0) + if (willLike) 1 else -1).coerceAtLeast(0)

        fun bump(p: FeedPost) = if (p.id == postId) {
            p.copy(isLikedByMe = willLike, likesCount = (p.likesCount + if (willLike) 1 else -1).coerceAtLeast(0))
        } else p
        _posts.value = _posts.value.map(::bump)
        _drafts.value = _drafts.value.map(::bump)
    }

    fun addComment(postId: String, comment: PostComment) {
        val list = commentsByPost.getOrPut(postId) { mutableListOf() }
        list.add(comment)
        fun bump(p: FeedPost) = if (p.id == postId) {
            p.copy(comments = list.toList(), commentsCount = list.size)
        } else p
        _posts.value = _posts.value.map(::bump)
        _drafts.value = _drafts.value.map(::bump)
    }

    fun toggleCommentLike(postId: String, commentId: String) {
        val willLike = !likedCommentIds.contains(commentId)
        if (willLike) likedCommentIds.add(commentId) else likedCommentIds.remove(commentId)
        val list = commentsByPost[postId] ?: return
        val idx = list.indexOfFirst { it.id == commentId }
        if (idx >= 0) {
            val c = list[idx]
            list[idx] = c.copy(
                isLiked = willLike,
                likesCount = (c.likesCount + if (willLike) 1 else -1).coerceAtLeast(0)
            )
            fun bump(p: FeedPost) = if (p.id == postId) p.copy(comments = list.toList()) else p
            _posts.value = _posts.value.map(::bump)
            _drafts.value = _drafts.value.map(::bump)
        }
    }

    fun reportComment(postId: String, commentId: String, reason: String, details: String = "") {
        val updatedSet = _reportedCommentIds.value + commentId
        _reportedCommentIds.value = updatedSet
        prefs.edit().putStringSet("reported_comment_ids", updatedSet).apply()
        try {
            val reportJson = JSONObject().apply {
                put("postId", postId)
                put("commentId", commentId)
                put("reason", reason)
                put("details", details)
                put("timestamp", System.currentTimeMillis())
            }
            prefs.edit().putString("comment_report_${commentId}", reportJson.toString()).apply()
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun loadReportedComments() {
        val saved = prefs.getStringSet("reported_comment_ids", emptySet()) ?: emptySet()
        _reportedCommentIds.value = saved
    }

    fun unlockPaidPostLocally(postId: String) {
        fun bump(p: FeedPost) = if (p.id == postId) p.copy(isUnlocked = true) else p
        _posts.value = _posts.value.map(::bump)
        _drafts.value = _drafts.value.map(::bump)
    }
}

/** createPost variant carrying a backend-generated post id. */
sealed class FeedResultWithId {
    data class Success(val postId: String) : FeedResultWithId()
    data class BackendMissing(val detail: String) : FeedResultWithId()
    data class Error(val message: String) : FeedResultWithId()
}

/** uploadFeedMedia variant carrying the storage path. */
sealed class FeedResultWithPath {
    data class Success(val storagePath: String) : FeedResultWithPath()
    data class BackendMissing(val detail: String) : FeedResultWithPath()
    data class Error(val message: String) : FeedResultWithPath()
}
