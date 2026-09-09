package com.example.service.webrtc

import com.example.model.CallState
import com.example.model.CallType
import com.example.service.CallService
import com.example.service.LiveStreamItem
import com.example.service.LiveStreamRole
import com.example.service.LiveStreamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
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

/**
 * Production WebRTC communication service backed by real Agora RTC Engine and Supabase.
 * Delegates 1-to-1 calling to [CallService] and live streaming to [LiveStreamService].
 */
class AgoraWebRtcService(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val callService: CallService? = null,
    private val liveStreamService: LiveStreamService? = null
) {
    private val _agoraState = MutableStateFlow(AgoraState())
    val agoraState: StateFlow<AgoraState> = _agoraState.asStateFlow()
    val callState: StateFlow<AgoraState> = _agoraState.asStateFlow()

    init {
        // Observe real CallService session if provided
        callService?.let { cs ->
            scope.launch {
                cs.currentCall.collect { session ->
                    if (session == null) {
                        if (_agoraState.value.mode != AgoraCallMode.LIVE_STREAM) {
                            _agoraState.update {
                                it.copy(
                                    status = AgoraCallStatus.IDLE,
                                    durationSeconds = 0
                                )
                            }
                        }
                    } else {
                        val status = when (session.state) {
                            CallState.CALLING -> AgoraCallStatus.DIALING
                            CallState.RINGING -> AgoraCallStatus.RINGING
                            CallState.CONNECTING -> AgoraCallStatus.RINGING
                            CallState.CONNECTED -> AgoraCallStatus.CONNECTED
                            CallState.RECONNECTING -> AgoraCallStatus.CONNECTED
                            CallState.ENDED, CallState.DECLINED, CallState.MISSED -> AgoraCallStatus.DISCONNECTED
                            CallState.FAILED -> AgoraCallStatus.FAILED
                            CallState.IDLE -> AgoraCallStatus.IDLE
                        }
                        _agoraState.update {
                            it.copy(
                                status = status,
                                channelName = session.channelName.ifBlank { "call_" + session.callId.take(8) },
                                mode = if (session.type == CallType.VIDEO) AgoraCallMode.VIDEO_CALL else AgoraCallMode.AUDIO_CALL,
                                role = AgoraUserRole.BROADCASTER,
                                remoteUserName = session.contactName,
                                isMuted = session.isMuted,
                                isVideoEnabled = session.isVideoEnabled,
                                isSpeakerOn = session.isSpeakerOn,
                                isFrontCamera = session.isFrontCamera,
                                durationSeconds = session.durationSeconds.toLong()
                            )
                        }
                    }
                }
            }
        }

        // Observe real LiveStreamService if provided
        liveStreamService?.let { ls ->
            scope.launch {
                ls.activeStreams.collect { streams ->
                    val mapped = streams.map { item ->
                        AgoraLiveStream(
                            id = item.id,
                            title = item.title,
                            streamerName = item.streamerName,
                            viewerCount = item.viewerCount,
                            category = item.category,
                            channelName = item.channelName,
                            isLive = item.isLive
                        )
                    }
                    _agoraState.update { it.copy(activeStreams = mapped) }
                }
            }

            scope.launch {
                ls.currentStreamState.collect { streamState ->
                    val stream = streamState.stream
                    if (stream != null && streamState.isJoined) {
                        _agoraState.update {
                            it.copy(
                                status = AgoraCallStatus.CONNECTED,
                                channelName = stream.channelName,
                                mode = AgoraCallMode.LIVE_STREAM,
                                role = if (streamState.role == LiveStreamRole.HOST) AgoraUserRole.BROADCASTER else AgoraUserRole.AUDIENCE,
                                remoteUserName = stream.title,
                                isMuted = streamState.isMuted,
                                isVideoEnabled = streamState.isVideoEnabled,
                                isFrontCamera = streamState.isFrontCamera,
                                durationSeconds = streamState.durationSeconds
                            )
                        }
                    }
                }
            }
        }
    }

    fun startCall(
        channelName: String,
        contactName: String = "Contact",
        isVideo: Boolean = false
    ) {
        val type = if (isVideo) CallType.VIDEO else CallType.AUDIO
        if (callService != null) {
            callService.startCall(
                contactId = channelName,
                contactName = contactName,
                avatarRes = null,
                type = type
            )
        } else {
            _agoraState.update {
                it.copy(
                    status = AgoraCallStatus.DIALING,
                    channelName = channelName,
                    mode = if (isVideo) AgoraCallMode.VIDEO_CALL else AgoraCallMode.AUDIO_CALL,
                    remoteUserName = contactName,
                    isVideoEnabled = isVideo
                )
            }
        }
    }

    fun startLiveStream(
        streamTitle: String,
        channelName: String
    ) {
        if (liveStreamService != null) {
            scope.launch {
                liveStreamService.startLiveStream(title = streamTitle)
            }
        } else {
            _agoraState.update {
                it.copy(
                    status = AgoraCallStatus.CONNECTED,
                    channelName = channelName,
                    mode = AgoraCallMode.LIVE_STREAM,
                    role = AgoraUserRole.BROADCASTER,
                    remoteUserName = streamTitle
                )
            }
        }
    }

    fun joinLiveStream(stream: AgoraLiveStream) {
        if (liveStreamService != null) {
            scope.launch {
                val item = LiveStreamItem(
                    id = stream.id,
                    hostId = "host",
                    channelName = stream.channelName,
                    title = stream.title,
                    streamerName = stream.streamerName,
                    viewerCount = stream.viewerCount,
                    category = stream.category
                )
                liveStreamService.joinLiveStream(item)
            }
        } else {
            _agoraState.update {
                it.copy(
                    status = AgoraCallStatus.CONNECTED,
                    channelName = stream.channelName,
                    mode = AgoraCallMode.LIVE_STREAM,
                    role = AgoraUserRole.AUDIENCE,
                    remoteUserName = stream.title
                )
            }
        }
    }

    fun endCall() {
        if (_agoraState.value.mode == AgoraCallMode.LIVE_STREAM) {
            liveStreamService?.leaveLiveStream()
        } else {
            callService?.endCall()
        }
        _agoraState.update {
            it.copy(
                status = AgoraCallStatus.DISCONNECTED,
                durationSeconds = 0
            )
        }
    }

    fun toggleMute() {
        if (_agoraState.value.mode == AgoraCallMode.LIVE_STREAM) {
            liveStreamService?.toggleMute()
        } else {
            callService?.toggleMute()
        }
        _agoraState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun toggleVideo() {
        if (_agoraState.value.mode == AgoraCallMode.LIVE_STREAM) {
            liveStreamService?.toggleVideo()
        } else {
            callService?.toggleVideo()
        }
        _agoraState.update { it.copy(isVideoEnabled = !it.isVideoEnabled) }
    }

    fun toggleSpeaker() {
        callService?.toggleSpeaker()
        _agoraState.update { it.copy(isSpeakerOn = !it.isSpeakerOn) }
    }

    fun switchCamera() {
        if (_agoraState.value.mode == AgoraCallMode.LIVE_STREAM) {
            liveStreamService?.switchCamera()
        } else {
            callService?.switchCamera()
        }
        _agoraState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }
}
