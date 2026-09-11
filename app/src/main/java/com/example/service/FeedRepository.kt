package com.example.service

import android.content.Context
import android.content.SharedPreferences
import com.example.model.FeedPost
import com.example.model.PostComment
import com.example.model.PostMediaType
import com.example.model.PostType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class FeedRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("trigger_feed_posts", Context.MODE_PRIVATE)

    private val _posts = MutableStateFlow<List<FeedPost>>(emptyList())
    val posts: StateFlow<List<FeedPost>> = _posts.asStateFlow()

    private val _reportedCommentIds = MutableStateFlow<Set<String>>(emptySet())
    val reportedCommentIds: StateFlow<Set<String>> = _reportedCommentIds.asStateFlow()

    init {
        loadPosts()
        loadReportedComments()
    }

    private fun loadReportedComments() {
        val saved = prefs.getStringSet("reported_comment_ids", emptySet()) ?: emptySet()
        _reportedCommentIds.value = saved
    }

    private fun loadPosts() {
        val jsonStr = prefs.getString("feed_posts_json", null)
        if (jsonStr.isNullOrBlank()) {
            val initial = createInitialSeedPosts()
            _posts.value = initial
            savePostsToPrefs(initial)
        } else {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<FeedPost>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val mediaUrlsList = mutableListOf<String>()
                    val urlsArr = obj.optJSONArray("mediaUrls")
                    if (urlsArr != null) {
                        for (u in 0 until urlsArr.length()) {
                            mediaUrlsList.add(urlsArr.getString(u))
                        }
                    }

                    val commentsList = mutableListOf<PostComment>()
                    val commsArr = obj.optJSONArray("comments")
                    if (commsArr != null) {
                        for (c in 0 until commsArr.length()) {
                            val cObj = commsArr.getJSONObject(c)
                            commentsList.add(
                                PostComment(
                                    id = cObj.optString("id", UUID.randomUUID().toString()),
                                    authorId = cObj.optString("authorId", ""),
                                    authorName = cObj.optString("authorName", "User"),
                                    authorAvatarUrl = cObj.optString("authorAvatarUrl", null),
                                    text = cObj.optString("text", ""),
                                    timestamp = cObj.optLong("timestamp", System.currentTimeMillis()),
                                    likesCount = cObj.optInt("likesCount", 0),
                                    isLiked = cObj.optBoolean("isLiked", false)
                                )
                            )
                        }
                    }

                    list.add(
                        FeedPost(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            authorId = obj.optString("authorId", ""),
                            authorName = obj.optString("authorName", "Creator"),
                            authorUsername = obj.optString("authorUsername", "creator"),
                            authorAvatarUrl = obj.optString("authorAvatarUrl", null),
                            description = obj.optString("description", ""),
                            mediaUrls = mediaUrlsList,
                            mediaType = if (obj.optString("mediaType") == "VIDEO") PostMediaType.VIDEO else PostMediaType.IMAGE,
                            postType = if (obj.optString("postType") == "PAID") PostType.PAID else PostType.FREE,
                            priceAmount = obj.optDouble("priceAmount", 0.0),
                            currency = obj.optString("currency", "INR"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            likesCount = obj.optInt("likesCount", 0),
                            isLikedByMe = obj.optBoolean("isLikedByMe", false),
                            commentsCount = obj.optInt("commentsCount", commentsList.size),
                            comments = commentsList,
                            isUnlocked = obj.optBoolean("isUnlocked", false)
                        )
                    )
                }
                _posts.value = list
            } catch (e: Exception) {
                val initial = createInitialSeedPosts()
                _posts.value = initial
            }
        }
    }

    private fun savePostsToPrefs(posts: List<FeedPost>) {
        try {
            val array = JSONArray()
            for (p in posts) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("authorId", p.authorId)
                obj.put("authorName", p.authorName)
                obj.put("authorUsername", p.authorUsername)
                if (p.authorAvatarUrl != null) obj.put("authorAvatarUrl", p.authorAvatarUrl)
                obj.put("description", p.description)
                val mediaArr = JSONArray()
                p.mediaUrls.forEach { mediaArr.put(it) }
                obj.put("mediaUrls", mediaArr)
                obj.put("mediaType", p.mediaType.name)
                obj.put("postType", p.postType.name)
                obj.put("priceAmount", p.priceAmount)
                obj.put("currency", p.currency)
                obj.put("timestamp", p.timestamp)
                obj.put("likesCount", p.likesCount)
                obj.put("isLikedByMe", p.isLikedByMe)
                obj.put("commentsCount", p.commentsCount)
                obj.put("isUnlocked", p.isUnlocked)

                val commsArr = JSONArray()
                for (c in p.comments) {
                    val cObj = JSONObject()
                    cObj.put("id", c.id)
                    cObj.put("authorId", c.authorId)
                    cObj.put("authorName", c.authorName)
                    if (c.authorAvatarUrl != null) cObj.put("authorAvatarUrl", c.authorAvatarUrl)
                    cObj.put("text", c.text)
                    cObj.put("timestamp", c.timestamp)
                    cObj.put("likesCount", c.likesCount)
                    cObj.put("isLiked", c.isLiked)
                    commsArr.put(cObj)
                }
                obj.put("comments", commsArr)

                array.put(obj)
            }
            prefs.edit().putString("feed_posts_json", array.toString()).apply()
        } catch (e: Exception) {
            // best-effort persistence
        }
    }

    fun getPost(postId: String): FeedPost? {
        return _posts.value.find { it.id == postId }
    }

    fun addPost(newPost: FeedPost) {
        val updated = listOf(newPost) + _posts.value
        _posts.value = updated
        savePostsToPrefs(updated)
    }

    fun toggleLike(postId: String) {
        val updated = _posts.value.map { p ->
            if (p.id == postId) {
                val willBeLiked = !p.isLikedByMe
                val newCount = if (willBeLiked) p.likesCount + 1 else (p.likesCount - 1).coerceAtLeast(0)
                p.copy(isLikedByMe = willBeLiked, likesCount = newCount)
            } else {
                p
            }
        }
        _posts.value = updated
        savePostsToPrefs(updated)
    }

    fun addComment(postId: String, comment: PostComment) {
        val updated = _posts.value.map { p ->
            if (p.id == postId) {
                val newComments = p.comments + comment
                p.copy(comments = newComments, commentsCount = newComments.size)
            } else {
                p
            }
        }
        _posts.value = updated
        savePostsToPrefs(updated)
    }

    fun toggleCommentLike(postId: String, commentId: String) {
        val updated = _posts.value.map { p ->
            if (p.id == postId) {
                val newComments = p.comments.map { c ->
                    if (c.id == commentId) {
                        val willBeLiked = !c.isLiked
                        val newCount = if (willBeLiked) c.likesCount + 1 else (c.likesCount - 1).coerceAtLeast(0)
                        c.copy(isLiked = willBeLiked, likesCount = newCount)
                    } else {
                        c
                    }
                }
                p.copy(comments = newComments)
            } else {
                p
            }
        }
        _posts.value = updated
        savePostsToPrefs(updated)
    }

    fun unlockPaidPost(postId: String) {
        val updated = _posts.value.map { p ->
            if (p.id == postId) {
                p.copy(isUnlocked = true)
            } else {
                p
            }
        }
        _posts.value = updated
        savePostsToPrefs(updated)
    }

    fun reportComment(postId: String, commentId: String, reason: String, details: String = "") {
        val updatedSet = _reportedCommentIds.value + commentId
        _reportedCommentIds.value = updatedSet
        prefs.edit().putStringSet("reported_comment_ids", updatedSet).apply()

        // Also save audit log in SharedPreferences
        try {
            val reportLogKey = "comment_report_${commentId}_${System.currentTimeMillis()}"
            val reportJson = JSONObject().apply {
                put("postId", postId)
                put("commentId", commentId)
                put("reason", reason)
                put("details", details)
                put("timestamp", System.currentTimeMillis())
            }
            prefs.edit().putString(reportLogKey, reportJson.toString()).apply()
        } catch (e: Exception) {
            // best-effort logging
        }
    }

    suspend fun refreshPosts(): Boolean {
        // Simulate network fetch latency
        kotlinx.coroutines.delay(800)
        // If posts list was empty, re-seed posts so user has content
        if (_posts.value.isEmpty()) {
            val seed = createInitialSeedPosts()
            _posts.value = seed
            savePostsToPrefs(seed)
        } else {
            // Re-read latest posts
            loadPosts()
        }
        return true
    }

    fun seedOrExplorePosts() {
        val seed = createInitialSeedPosts()
        _posts.value = seed
        savePostsToPrefs(seed)
    }

    fun clearPostsForEmptyStateDemo() {
        _posts.value = emptyList()
        prefs.edit().remove("feed_posts_json").apply()
    }

    private fun createInitialSeedPosts(): List<FeedPost> {
        val now = System.currentTimeMillis()
        return listOf(
            FeedPost(
                id = "seed_post_1",
                authorId = "user_akash",
                authorName = "Akash Varma",
                authorUsername = "akash.creates",
                authorAvatarUrl = null,
                description = "Sunset golden hour from the southern hilltops. Shot on manual 35mm lens. What do you think about the light grading? #photography #goldenhour #sunset",
                mediaUrls = listOf(
                    "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=800&q=80",
                    "https://images.unsplash.com/photo-1472214103451-9374bd1c798e?w=800&q=80"
                ),
                mediaType = PostMediaType.IMAGE,
                postType = PostType.FREE,
                priceAmount = 0.0,
                currency = "INR",
                timestamp = now - 3600000L * 2,
                likesCount = 142,
                isLikedByMe = false,
                commentsCount = 2,
                comments = listOf(
                    PostComment(
                        authorName = "Priya Sharma",
                        text = "The warm tone on this is surreal! Which lens did you use?",
                        timestamp = now - 3600000L
                    ),
                    PostComment(
                        authorName = "Dev Patel",
                        text = "Awesome composition! Loved the second slide as well.",
                        timestamp = now - 1800000L
                    )
                )
            ),
            FeedPost(
                id = "seed_post_2",
                authorId = "user_sarah_pro",
                authorName = "Sarah Jenkins",
                authorUsername = "sarah.cinema",
                authorAvatarUrl = null,
                description = "Exclusive masterclass: Professional Color Grading and LUT Workflow breakdown for cinematic reels. Includes 5 downloadable LUTs + project file breakdown.",
                mediaUrls = listOf(
                    "https://images.unsplash.com/photo-1536240478700-b869070f9279?w=800&q=80"
                ),
                mediaType = PostMediaType.IMAGE,
                postType = PostType.PAID,
                priceAmount = 199.0,
                currency = "INR",
                timestamp = now - 3600000L * 5,
                likesCount = 89,
                isLikedByMe = false,
                commentsCount = 1,
                comments = listOf(
                    PostComment(
                        authorName = "Rohan Verma",
                        text = "Just unlocked this! The color science chapter is a game changer.",
                        timestamp = now - 3600000L * 3
                    )
                ),
                isUnlocked = false
            ),
            FeedPost(
                id = "seed_post_3",
                authorId = "user_maya_travel",
                authorName = "Maya Roy",
                authorUsername = "maya.wanderlust",
                authorAvatarUrl = null,
                description = "Exploring hidden waterfalls deep in the mountain ridge! 🏔️✨ The sound of rushing fresh water is pure therapy. Swipe right for the forest trail.",
                mediaUrls = listOf(
                    "https://images.unsplash.com/photo-1432405972618-c60b0225b8f9?w=800&q=80",
                    "https://images.unsplash.com/photo-1448375240586-882707db888b?w=800&q=80"
                ),
                mediaType = PostMediaType.IMAGE,
                postType = PostType.FREE,
                priceAmount = 0.0,
                currency = "INR",
                timestamp = now - 3600000L * 9,
                likesCount = 275,
                isLikedByMe = true,
                commentsCount = 3,
                comments = listOf(
                    PostComment(
                        authorName = "Ananya Sen",
                        text = "Where is this location?! Adding to my bucket list immediately!",
                        timestamp = now - 3600000L * 8
                    ),
                    PostComment(
                        authorName = "Maya Roy",
                        text = "It's near Western Ghats trekking route! Will post exact coordinates soon.",
                        timestamp = now - 3600000L * 7
                    )
                )
            )
        )
    }
}
