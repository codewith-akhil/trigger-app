package com.example.service

import kotlinx.coroutines.flow.StateFlow

data class LiveStreamItem(
    val id: String,
    val hostId: String,
    val channelName: String,
    val title: String,
    val description: String = "",
    val category: String = "General",
    val streamerName: String,
    val viewerCount: Int = 0,
    val totalLikes: Int = 0,
    val isLive: Boolean = true,
    val startedAt: Long = System.currentTimeMillis()
)

data class LiveStreamComment(
    val id: String,
    val streamId: String,
    val userId: String,
    val userName: String,
    val avatarUrl: String? = null,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LiveStreamRole {
    HOST,
    AUDIENCE
}

data class CurrentLiveStreamState(
    val stream: LiveStreamItem? = null,
    val role: LiveStreamRole = LiveStreamRole.AUDIENCE,
    val isJoined: Boolean = false,
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val durationSeconds: Long = 0,
    val comments: List<LiveStreamComment> = emptyList(),
    val reactionCount: Int = 0,
    val errorMessage: String? = null
)

interface LiveStreamService {
    val activeStreams: StateFlow<List<LiveStreamItem>>
    val currentStreamState: StateFlow<CurrentLiveStreamState>

    suspend fun fetchActiveStreams(): List<LiveStreamItem>
    suspend fun startLiveStream(title: String, description: String = "", category: String = "Live Talk"): Result<LiveStreamItem>
    suspend fun joinLiveStream(stream: LiveStreamItem): Result<Unit>
    fun leaveLiveStream()
    fun toggleMute()
    fun toggleVideo()
    fun switchCamera()
    suspend fun sendComment(message: String): Result<Unit>
    suspend fun sendReaction(): Result<Unit>
}
