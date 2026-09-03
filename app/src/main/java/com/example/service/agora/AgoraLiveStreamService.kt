package com.example.service.agora

import android.util.Log
import com.example.config.BackendConfig
import com.example.service.*
import com.example.service.supabase.SupabaseClient
import com.example.service.supabase.SupabaseResult
import io.agora.rtc2.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.*
import kotlin.math.abs

class AgoraLiveStreamService(
    private val rtcManager: AgoraRtcEngineManager,
    private val supabaseClient: SupabaseClient,
    private val scope: CoroutineScope
) : LiveStreamService {

    companion object {
        private const val TAG = "AgoraLiveStreamService"
    }

    private val _activeStreams = MutableStateFlow<List<LiveStreamItem>>(emptyList())
    override val activeStreams: StateFlow<List<LiveStreamItem>> = _activeStreams.asStateFlow()

    private val _currentStreamState = MutableStateFlow(CurrentLiveStreamState())
    override val currentStreamState: StateFlow<CurrentLiveStreamState> = _currentStreamState.asStateFlow()

    private var durationJob: Job? = null
    private var commentSyncJob: Job? = null

    val rtcEngineManager: AgoraRtcEngineManager
        get() = rtcManager

    init {
        // Initial fetch of active streams
        scope.launch {
            fetchActiveStreams()
        }
    }

    override suspend fun fetchActiveStreams(): List<LiveStreamItem> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSupabaseConfigured) {
            // Provide curated default broadcast channels when database is fresh
            val defaults = listOf(
                LiveStreamItem(
                    id = "stream_default_1",
                    hostId = "host_1",
                    channelName = "trigger_tech_qa",
                    title = "⚡ Trigger App Dev & Architecture Q&A",
                    description = "Real-time WebRTC and Supabase backend architecture discussion.",
                    category = "Tech & Dev",
                    streamerName = "Akhil Canara Bank",
                    viewerCount = 142,
                    totalLikes = 890,
                    isLive = true
                ),
                LiveStreamItem(
                    id = "stream_default_2",
                    hostId = "host_2",
                    channelName = "trigger_music_chill",
                    title = "🎧 Coding Lofi Radio & Chill Vibes",
                    description = "Focus music for developers coding all night.",
                    category = "Music",
                    streamerName = "Sarah Jenkins",
                    viewerCount = 89,
                    totalLikes = 430,
                    isLive = true
                ),
                LiveStreamItem(
                    id = "stream_default_3",
                    hostId = "host_3",
                    channelName = "trigger_global_talk",
                    title = "🚀 Global Tech Talk: Realtime WebRTC & Supabase",
                    description = "Interactive livestream with video broadcasting.",
                    category = "Live Talk",
                    streamerName = "Alex Rivera",
                    viewerCount = 310,
                    totalLikes = 1200,
                    isLive = true
                )
            )
            _activeStreams.value = defaults
            return@withContext defaults
        }

        try {
            val result = supabaseClient.getTable("live_streams", "status=eq.live&order=created_at.desc&limit=30")
            if (result is SupabaseResult.Success) {
                val array = result.data
                val items = mutableListOf<LiveStreamItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items.add(
                        LiveStreamItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            hostId = obj.optString("host_id", ""),
                            channelName = obj.optString("channel_name", "channel_$i"),
                            title = obj.optString("title", "Live Broadcast"),
                            description = obj.optString("description", ""),
                            category = obj.optString("category", "General"),
                            streamerName = obj.optString("streamer_name", "Broadcaster"),
                            viewerCount = obj.optInt("viewer_count", 1),
                            totalLikes = obj.optInt("total_likes", 0),
                            isLive = obj.optString("status", "live") == "live"
                        )
                    )
                }
                _activeStreams.value = items
                return@withContext items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching live streams from Supabase", e)
        }
        emptyList()
    }

    override suspend fun startLiveStream(
        title: String,
        description: String,
        category: String
    ): Result<LiveStreamItem> = withContext(Dispatchers.IO) {
        val streamId = UUID.randomUUID().toString()
        val channelName = "stream_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val hostUid = abs((supabaseClient.currentUser?.id?.hashCode() ?: System.currentTimeMillis().hashCode()) % 1000000).toLong() + 2000
        val hostName = supabaseClient.currentUser?.fullName ?: "Host"

        val item = LiveStreamItem(
            id = streamId,
            hostId = supabaseClient.currentUser?.id ?: "host_${System.currentTimeMillis()}",
            channelName = channelName,
            title = title,
            description = description,
            category = category,
            streamerName = hostName,
            viewerCount = 1,
            totalLikes = 0,
            isLive = true
        )

        // 1. Insert into Supabase
        if (BackendConfig.isSupabaseConfigured) {
            try {
                val record = JSONObject().apply {
                    put("id", streamId)
                    put("host_id", item.hostId)
                    put("channel_name", channelName)
                    put("title", title)
                    put("description", description)
                    put("category", category)
                    put("status", "live")
                    put("viewer_count", 1)
                }
                supabaseClient.insertRecord("live_streams", record)
            } catch (e: Exception) {
                Log.w(TAG, "Failed inserting live stream to Supabase", e)
            }
        }

        // 2. Join Agora as Broadcaster
        val joined = rtcManager.joinChannel(
            channelName = channelName,
            uid = hostUid,
            isVideo = true,
            isBroadcaster = true,
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        )

        if (!joined) {
            return@withContext Result.failure(IllegalStateException("Failed to join Agora channel as Host"))
        }

        _currentStreamState.value = CurrentLiveStreamState(
            stream = item,
            role = LiveStreamRole.HOST,
            isJoined = true,
            isVideoEnabled = true,
            isMuted = false,
            isFrontCamera = true,
            durationSeconds = 0,
            comments = emptyList(),
            reactionCount = 0
        )

        _activeStreams.update { listOf(item) + it }
        startDurationTimer()
        startCommentSync(streamId)

        Result.success(item)
    }

    override suspend fun joinLiveStream(stream: LiveStreamItem): Result<Unit> = withContext(Dispatchers.IO) {
        val audienceUid = abs((supabaseClient.currentUser?.id?.hashCode() ?: System.currentTimeMillis().hashCode()) % 1000000).toLong() + 5000

        // 1. Join Agora as Audience
        val joined = rtcManager.joinChannel(
            channelName = stream.channelName,
            uid = audienceUid,
            isVideo = true,
            isBroadcaster = false,
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        )

        if (!joined) {
            return@withContext Result.failure(IllegalStateException("Failed to connect to Agora stream channel"))
        }

        _currentStreamState.value = CurrentLiveStreamState(
            stream = stream.copy(viewerCount = stream.viewerCount + 1),
            role = LiveStreamRole.AUDIENCE,
            isJoined = true,
            isVideoEnabled = true,
            isMuted = true,
            durationSeconds = 0,
            comments = emptyList(),
            reactionCount = stream.totalLikes
        )

        startDurationTimer()
        startCommentSync(stream.id)

        // Increment viewer count in Supabase
        if (BackendConfig.isSupabaseConfigured) {
            try {
                val update = JSONObject().apply {
                    put("id", stream.id)
                    put("viewer_count", stream.viewerCount + 1)
                }
                supabaseClient.upsertRecord("live_streams", update)
            } catch (_: Exception) {}
        }

        Result.success(Unit)
    }

    override fun leaveLiveStream() {
        val currentState = _currentStreamState.value
        val stream = currentState.stream

        durationJob?.cancel()
        durationJob = null
        commentSyncJob?.cancel()
        commentSyncJob = null

        rtcManager.leaveChannel()

        if (stream != null && BackendConfig.isSupabaseConfigured) {
            scope.launch(Dispatchers.IO) {
                try {
                    if (currentState.role == LiveStreamRole.HOST) {
                        val update = JSONObject().apply {
                            put("id", stream.id)
                            put("status", "ended")
                            put("ended_at", "NOW()")
                        }
                        supabaseClient.upsertRecord("live_streams", update)
                    } else {
                        val newCount = (stream.viewerCount - 1).coerceAtLeast(0)
                        val update = JSONObject().apply {
                            put("id", stream.id)
                            put("viewer_count", newCount)
                        }
                        supabaseClient.upsertRecord("live_streams", update)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating stream status on leave", e)
                }
            }
        }

        _currentStreamState.value = CurrentLiveStreamState()
    }

    override fun toggleMute() {
        val current = _currentStreamState.value
        if (current.role != LiveStreamRole.HOST) return
        val newMute = !current.isMuted
        rtcManager.muteLocalAudio(newMute)
        _currentStreamState.update { it.copy(isMuted = newMute) }
    }

    override fun toggleVideo() {
        val current = _currentStreamState.value
        if (current.role != LiveStreamRole.HOST) return
        val newVideo = !current.isVideoEnabled
        rtcManager.muteLocalVideo(!newVideo)
        _currentStreamState.update { it.copy(isVideoEnabled = newVideo) }
    }

    override fun switchCamera() {
        val current = _currentStreamState.value
        if (current.role != LiveStreamRole.HOST) return
        rtcManager.switchCamera()
        _currentStreamState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    override suspend fun sendComment(message: String): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _currentStreamState.value
        val stream = current.stream ?: return@withContext Result.failure(IllegalStateException("No active stream"))

        val trimmed = message.trim().take(300)
        if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty comment"))

        val comment = LiveStreamComment(
            id = UUID.randomUUID().toString(),
            streamId = stream.id,
            userId = supabaseClient.currentUser?.id ?: "user_me",
            userName = supabaseClient.currentUser?.fullName ?: "You",
            avatarUrl = supabaseClient.currentUser?.avatarUrl,
            message = trimmed,
            timestamp = System.currentTimeMillis()
        )

        // Optimistically add to UI
        _currentStreamState.update {
            it.copy(comments = it.comments + comment)
        }

        if (BackendConfig.isSupabaseConfigured) {
            try {
                val record = JSONObject().apply {
                    put("id", comment.id)
                    put("stream_id", stream.id)
                    put("user_id", comment.userId)
                    put("user_name", comment.userName)
                    put("message", comment.message)
                }
                supabaseClient.insertRecord("live_stream_comments", record)
            } catch (e: Exception) {
                Log.w(TAG, "Error posting comment to Supabase", e)
            }
        }

        Result.success(Unit)
    }

    override suspend fun sendReaction(): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _currentStreamState.value
        val stream = current.stream ?: return@withContext Result.failure(IllegalStateException("No active stream"))

        _currentStreamState.update {
            it.copy(reactionCount = it.reactionCount + 1)
        }

        if (BackendConfig.isSupabaseConfigured) {
            try {
                val record = JSONObject().apply {
                    put("stream_id", stream.id)
                    put("user_id", supabaseClient.currentUser?.id ?: "user_me")
                    put("reaction_type", "heart")
                }
                supabaseClient.insertRecord("live_stream_reactions", record)
            } catch (_: Exception) {}
        }

        Result.success(Unit)
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive && _currentStreamState.value.isJoined) {
                delay(1000)
                _currentStreamState.update {
                    it.copy(durationSeconds = it.durationSeconds + 1)
                }
            }
        }
    }

    private fun startCommentSync(streamId: String) {
        commentSyncJob?.cancel()
        commentSyncJob = scope.launch(Dispatchers.IO) {
            while (isActive && _currentStreamState.value.isJoined) {
                if (BackendConfig.isSupabaseConfigured) {
                    try {
                        val result = supabaseClient.getTable(
                            "live_stream_comments",
                            "stream_id=eq.$streamId&order=created_at.asc&limit=60"
                        )
                        if (result is SupabaseResult.Success) {
                            val array = result.data
                            val comments = mutableListOf<LiveStreamComment>()
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                comments.add(
                                    LiveStreamComment(
                                        id = obj.optString("id", UUID.randomUUID().toString()),
                                        streamId = streamId,
                                        userId = obj.optString("user_id", ""),
                                        userName = obj.optString("user_name", "Viewer"),
                                        avatarUrl = obj.optString("avatar_url", null),
                                        message = obj.optString("message", "")
                                    )
                                )
                            }
                            if (comments.isNotEmpty()) {
                                _currentStreamState.update { it.copy(comments = comments) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Error syncing comments", e)
                    }
                }
                delay(3000) // Poll comments every 3 seconds
            }
        }
    }
}
