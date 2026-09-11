package com.example.model

import java.util.UUID

enum class PostType {
    FREE,
    PAID
}

enum class PostMediaType {
    IMAGE,
    VIDEO
}

data class PostComment(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String? = null,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val likesCount: Int = 0,
    val isLiked: Boolean = false
)

data class FeedPost(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String = "",
    val authorName: String = "",
    val authorUsername: String = "",
    val authorAvatarUrl: String? = null,
    val description: String = "",
    val mediaUrls: List<String> = emptyList(),
    val mediaType: PostMediaType = PostMediaType.IMAGE,
    val postType: PostType = PostType.FREE,
    val priceAmount: Double = 0.0,
    val currency: String = "INR",
    val timestamp: Long = System.currentTimeMillis(),
    val likesCount: Int = 0,
    val isLikedByMe: Boolean = false,
    val commentsCount: Int = 0,
    val comments: List<PostComment> = emptyList(),
    val isUnlocked: Boolean = false
)
