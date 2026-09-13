package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.model.CurrencyOption
import com.example.model.FeedMediaItem
import com.example.model.FeedPost
import com.example.model.PostComment
import com.example.model.PostMediaType
import com.example.model.PostType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * REAL feed backend — every post, draft, media file, like and comment lives in
 * Supabase (tables `feed_posts` / `feed_post_media` / `post_unlocks` /
 * `feed_post_likes` / `feed_comments` / `feed_comment_likes`, bucket
 * `feed-media`). There is NO mock or seed data in the app anymore.
 *
 * Engagement is SERVER-PERSISTENT via edge functions (toggle-post-like,
 * add-post-comment, toggle-comment-like, get-post-comments). The client keeps
 * in-memory comment lists only as a render cache for the open post; counters
 * and like state always come from the server (optimistic UI reconciled with
 * the authoritative response).
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

    // Render cache for the open post's comments (server data, refreshed on
    // every post open via get-post-comments). NOT the source of truth —
    // counters/like state come from the server responses.
    private val commentsByPost = mutableMapOf<String, MutableList<PostComment>>()

    /** Background scope for optimistic UI + server reconciliation. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            likesCount = obj.optInt("likeCount", 0),
            isLikedByMe = obj.optBoolean("isLikedByMe", false),
            commentsCount = if (comments.isNotEmpty()) comments.size else obj.optInt("commentCount", 0),
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
    // Engagement — server-persistent (likes + comments), optimistic UI
    // --------------------------------------------------------------------
    fun getPost(postId: String): FeedPost? =
        _posts.value.find { it.id == postId } ?: _drafts.value.find { it.id == postId }

    private fun bumpPost(postId: String, transform: (FeedPost) -> FeedPost) {
        _posts.value = _posts.value.map { if (it.id == postId) transform(it) else it }
        _drafts.value = _drafts.value.map { if (it.id == postId) transform(it) else it }
    }

    /** Server comment JSON → UI model. */
    private fun parseComment(o: JSONObject): PostComment = PostComment(
        id = o.optString("id"),
        authorId = o.optString("authorId"),
        authorName = o.optString("authorName", "Creator"),
        authorAvatarUrl = o.optString("authorAvatarUrl", "").takeIf { it.isNotBlank() },
        text = o.optString("body", ""),
        timestamp = parseIsoDate(o.optString("createdAt")) ?: System.currentTimeMillis(),
        likesCount = o.optInt("likesCount", 0),
        isLiked = o.optBoolean("likedByMe", false)
    )

    /**
     * Load (or refresh) the post's comments from the server. Called on post
     * open — PostViewScreen LaunchedEffect.
     */
    suspend fun loadComments(postId: String): FeedResult {
        val res = client.invokeFunction(
            "get-post-comments",
            JSONObject().put("postId", postId).put("limit", 50).put("offset", 0)
        )
        return when (res) {
            is SupabaseResult.Success -> {
                val list = mutableListOf<PostComment>()
                val arr = res.data.optJSONArray("comments") ?: JSONArray()
                for (i in 0 until arr.length()) list.add(parseComment(arr.getJSONObject(i)))
                commentsByPost[postId] = list
                bumpPost(postId) { it.copy(comments = list.toList(), commentsCount = list.size) }
                FeedResult.Success
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "loadComments failed: ${res.message}")
                mapFunctionError(res.message)
            }
        }
    }

    /**
     * Optimistic like flip, then server toggle; the authoritative likeCount /
     * liked state from the response replaces the optimistic values.
     */
    fun toggleLike(postId: String) {
        val current = getPost(postId) ?: return
        val willLike = !current.isLikedByMe
        bumpPost(postId) {
            it.copy(
                isLikedByMe = willLike,
                likesCount = (it.likesCount + if (willLike) 1 else -1).coerceAtLeast(0)
            )
        }
        scope.launch {
            val res = client.invokeFunction(
                "toggle-post-like", JSONObject().put("postId", postId)
            )
            if (res is SupabaseResult.Success) {
                val liked = res.data.optBoolean("liked", willLike)
                val count = res.data.optInt("likeCount", -1)
                bumpPost(postId) {
                    it.copy(
                        isLikedByMe = liked,
                        likesCount = if (count >= 0) count else it.likesCount
                    )
                }
            } else if (res is SupabaseResult.Error) {
                Log.w(TAG, "toggleLike server call failed, keeping optimistic state: ${res.message}")
            }
        }
    }

    /**
     * Optimistic append, then server insert; on success the local placeholder
     * is replaced by the server row (real id, profile, counters).
     */
    fun addComment(postId: String, comment: PostComment) {
        val list = commentsByPost.getOrPut(postId) { mutableListOf() }
        list.add(comment)
        bumpPost(postId) { it.copy(comments = list.toList(), commentsCount = list.size) }
        scope.launch {
            val res = client.invokeFunction(
                "add-post-comment",
                JSONObject().put("postId", postId).put("body", comment.text)
            )
            if (res is SupabaseResult.Success) {
                val server = res.data.optJSONObject("comment")
                val count = res.data.optInt("commentCount", -1)
                if (server != null) {
                    val fresh = parseComment(server)
                    val updated = commentsByPost.getOrPut(postId) { mutableListOf() }
                    val idx = updated.indexOfFirst { it.id == comment.id }
                    if (idx >= 0) updated[idx] = fresh else updated.add(fresh)
                    bumpPost(postId) {
                        it.copy(
                            comments = updated.toList(),
                            commentsCount = if (count >= 0) count else updated.size
                        )
                    }
                }
            } else if (res is SupabaseResult.Error) {
                Log.w(TAG, "addComment server call failed, keeping optimistic state: ${res.message}")
            }
        }
    }

    /** Optimistic comment-like flip, reconciled with the server response. */
    fun toggleCommentLike(postId: String, commentId: String) {
        val list = commentsByPost[postId] ?: return
        val idx = list.indexOfFirst { it.id == commentId }
        if (idx < 0) return
        val willLike = !list[idx].isLiked
        list[idx] = list[idx].copy(
            isLiked = willLike,
            likesCount = (list[idx].likesCount + if (willLike) 1 else -1).coerceAtLeast(0)
        )
        bumpPost(postId) { it.copy(comments = list.toList()) }
        scope.launch {
            val res = client.invokeFunction(
                "toggle-comment-like", JSONObject().put("commentId", commentId)
            )
            if (res is SupabaseResult.Success) {
                val liked = res.data.optBoolean("liked", willLike)
                val count = res.data.optInt("likesCount", -1)
                val current = commentsByPost[postId] ?: return@launch
                val i = current.indexOfFirst { it.id == commentId }
                if (i >= 0) {
                    current[i] = current[i].copy(
                        isLiked = liked,
                        likesCount = if (count >= 0) count else current[i].likesCount
                    )
                    bumpPost(postId) { it.copy(comments = current.toList()) }
                }
            } else if (res is SupabaseResult.Error) {
                Log.w(TAG, "toggleCommentLike server call failed: ${res.message}")
            }
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
