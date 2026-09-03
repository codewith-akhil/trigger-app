package com.example.service.webrtc

import com.example.config.BackendConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AgoraCallStatus {
    IDLE,
    DIALING,
    RINGING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}

enum class AgoraCallMode {
    AUDIO_CALL,
    VIDEO_CALL,
    LIVE_STREAM
}

enum class AgoraUserRole {
    BROADCASTER,
    AUDIENCE
}

data class AgoraLiveStream(
    val id: String,
    val title: String,
    val streamerName: String,
    val streamerAvatarRes: Int? = null,
    val viewerCount: Int = 0,
    val category: String = "Live Talk",
    val channelName: String,
    val isLive: Boolean = true
)

data class AgoraState(
    val status: AgoraCallStatus = AgoraCallStatus.IDLE,
    val channelName: String = "",
    val mode: AgoraCallMode = AgoraCallMode.AUDIO_CALL,
    val role: AgoraUserRole = AgoraUserRole.BROADCASTER,
    val remoteUserName: String = "",
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val isFrontCamera: Boolean = true,
    val durationSeconds: Long = 0,
    val activeStreams: List<AgoraLiveStream> = emptyList(),
    val errorMessage: String? = null
)

class AgoraWebRtcService(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _agoraState = MutableStateFlow(
        AgoraState(
            activeStreams = listOf(
                AgoraLiveStream(
                    id = "stream_1",
                    title = "⚡ Trigger App Dev & Architecture Q&A",
                    streamerName = "Akhil Canara Bank",
                    viewerCount = 142,
                    category = "Tech & Dev",
                    channelName = "trigger_dev_qa"
                ),
                AgoraLiveStream(
                    id = "stream_2",
                    title = "🎧 Coding Lofi Radio & Chill Vibes",
                    streamerName = "Sarah Jenkins",
                    viewerCount = 89,
                    category = "Music",
                    channelName = "trigger_lofi"
                ),
                AgoraLiveStream(
                    id = "stream_3",
                    title = "🚀 Global Tech Talk: Realtime WebRTC & Supabase",
                    streamerName = "Alex Rivera",
                    viewerCount = 310,
                    category = "WebRTC",
                    channelName = "agora_webrtc_global"
                )
            )
        )
    )
    val agoraState: StateFlow<AgoraState> = _agoraState.asStateFlow()

    private var durationTimerJob: Job? = null

    fun startCall(
        channelName: String,
        contactName: String,
        isVideo: Boolean
    ) {
        val mode = if (isVideo) AgoraCallMode.VIDEO_CALL else AgoraCallMode.AUDIO_CALL
        _agoraState.update {
            it.copy(
                status = AgoraCallStatus.DIALING,
                channelName = channelName,
                mode = mode,
                role = AgoraUserRole.BROADCASTER,
                remoteUserName = contactName,
                isMuted = false,
                isVideoEnabled = isVideo,
                durationSeconds = 0,
                errorMessage = null
            )
        }

        // WebRTC Connection handshake with Agora Console credentials
        scope.launch {
            delay(1200) // Simulating network signaling handshake
            if (_agoraState.value.status == AgoraCallStatus.DIALING) {
                _agoraState.update { it.copy(status = AgoraCallStatus.RINGING) }
            }
            delay(1500) // Simulating remote peer acceptance
            if (_agoraState.value.status == AgoraCallStatus.RINGING) {
                _agoraState.update { it.copy(status = AgoraCallStatus.CONNECTED) }
                startDurationTimer()
            }
        }
    }

    fun startLiveStream(
        streamTitle: String,
        channelName: String
    ) {
        _agoraState.update {
            val newStream = AgoraLiveStream(
                id = "stream_" + System.currentTimeMillis(),
                title = streamTitle,
                streamerName = "You (Broadcaster)",
                viewerCount = 1,
                category = "Broadcasting",
                channelName = channelName,
                isLive = true
            )
            it.copy(
                status = AgoraCallStatus.CONNECTED,
                channelName = channelName,
                mode = AgoraCallMode.LIVE_STREAM,
                role = AgoraUserRole.BROADCASTER,
                remoteUserName = streamTitle,
                isMuted = false,
                isVideoEnabled = true,
                durationSeconds = 0,
                activeStreams = listOf(newStream) + it.activeStreams
            )
        }
        startDurationTimer()
    }

    fun joinLiveStream(stream: AgoraLiveStream) {
        _agoraState.update {
            it.copy(
                status = AgoraCallStatus.CONNECTED,
                channelName = stream.channelName,
                mode = AgoraCallMode.LIVE_STREAM,
                role = AgoraUserRole.AUDIENCE,
                remoteUserName = stream.title,
                isMuted = true,
                isVideoEnabled = true,
                durationSeconds = 0
            )
        }
        startDurationTimer()
    }

    fun endCall() {
        durationTimerJob?.cancel()
        durationTimerJob = null
        _agoraState.update {
            it.copy(
                status = AgoraCallStatus.DISCONNECTED,
                durationSeconds = 0
            )
        }
        scope.launch {
            delay(400)
            _agoraState.update { it.copy(status = AgoraCallStatus.IDLE) }
        }
    }

    fun toggleMute() {
        _agoraState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun toggleVideo() {
        _agoraState.update { it.copy(isVideoEnabled = !it.isVideoEnabled) }
    }

    fun toggleSpeaker() {
        _agoraState.update { it.copy(isSpeakerOn = !it.isSpeakerOn) }
    }

    fun switchCamera() {
        _agoraState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _agoraState.update { it.copy(durationSeconds = it.durationSeconds + 1) }
            }
        }
    }
}
