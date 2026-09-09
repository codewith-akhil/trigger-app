package com.example.service.agora

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import com.example.config.AgoraConfig
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lifecycle and hardware manager for Agora RTC Engine 4.x.
 * Handles audio routing, video capture/rendering, channel joining/leaving,
 * and token renewal callbacks.
 */
class AgoraRtcEngineManager(
    private val context: Context,
    private val tokenService: AgoraTokenService
) {
    companion object {
        private const val TAG = "AgoraRtcManager"
    }

    private var rtcEngine: RtcEngine? = null

    /** The call_sessions id of the 1:1 call currently joined (null for streams).
     *  Passed to the token service so the backend can enforce participant
     *  authorization on every join AND on every mid-call token renewal. */
    @Volatile
    var activeCallId: String? = null

    // Engine connection state
    private val _engineState = MutableStateFlow(EngineStatus())
    val engineState: StateFlow<EngineStatus> = _engineState.asStateFlow()

    data class EngineStatus(
        val isInitialized: Boolean = false,
        val channelName: String? = null,
        val localUid: Int = 0,
        val remoteUid: Int? = null,
        val isJoined: Boolean = false,
        val isBroadcaster: Boolean = false,
        val isAudioMuted: Boolean = false,
        val isVideoMuted: Boolean = false,
        val isSpeakerphoneOn: Boolean = true,
        val isFrontCamera: Boolean = true,
        val isPoorConnection: Boolean = false,
        val lastError: String? = null,
        /** Raw Agora CONNECTION_STATE_* constant the engine last reported. */
        val connectionState: Int = Constants.CONNECTION_STATE_DISCONNECTED,
        /** Reason the remote user left: USER_OFFLINE_QUIT(0) = hung up,
         *  DROPPED(1) = network loss (recoverable → RECONNECTING). */
        val remoteLeftReason: Int? = null
    )

    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.i(TAG, "onJoinChannelSuccess: channel=$channel, uid=$uid")
            _engineState.update {
                it.copy(
                    isJoined = true,
                    channelName = channel,
                    localUid = uid,
                    lastError = null
                )
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.i(TAG, "onUserJoined: remote uid=$uid")
            // Remote is here/back — clear any recorded offline reason.
            _engineState.update { it.copy(remoteUid = uid, remoteLeftReason = null) }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.i(TAG, "onUserOffline: remote uid=$uid, reason=$reason")
            _engineState.update {
                if (it.remoteUid == uid) it.copy(remoteUid = null, remoteLeftReason = reason) else it
            }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            Log.d(TAG, "onConnectionStateChanged: state=$state, reason=$reason")
            when (state) {
                Constants.CONNECTION_STATE_FAILED -> {
                    _engineState.update {
                        it.copy(
                            isJoined = false,
                            connectionState = state,
                            lastError = "Connection failed (reason=$reason)"
                        )
                    }
                }
                Constants.CONNECTION_STATE_DISCONNECTED -> {
                    _engineState.update { it.copy(isJoined = false, connectionState = state) }
                }
                Constants.CONNECTION_STATE_RECONNECTING -> {
                    _engineState.update { it.copy(isPoorConnection = true, connectionState = state) }
                }
                Constants.CONNECTION_STATE_CONNECTED -> {
                    _engineState.update { it.copy(isPoorConnection = false, connectionState = state) }
                }
            }
        }

        override fun onNetworkQuality(uid: Int, txQuality: Int, rxQuality: Int) {
            val isPoor = txQuality >= Constants.QUALITY_POOR || rxQuality >= Constants.QUALITY_POOR
            if (_engineState.value.isPoorConnection != isPoor) {
                _engineState.update { it.copy(isPoorConnection = isPoor) }
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            Log.w(TAG, "Agora RTC token will expire soon. Requesting renewal.")
            renewTokenInternal()
        }

        override fun onRequestToken() {
            Log.w(TAG, "Agora RTC token expired. Requesting renewal.")
            renewTokenInternal()
        }

        override fun onError(err: Int) {
            Log.e(TAG, "Agora RTC error: $err")
            _engineState.update { it.copy(lastError = "Agora RTC error code: $err") }
        }
    }

    /**
     * Initializes the Agora RtcEngine instance if not already running.
     */
    @Synchronized
    fun ensureEngine(channelProfile: Int = Constants.CHANNEL_PROFILE_COMMUNICATION): RtcEngine? {
        if (rtcEngine != null) return rtcEngine

        val appId = AgoraConfig.AGORA_APP_ID
        if (appId.isBlank() || appId.contains("your-agora-app-id")) {
            Log.w(TAG, "Agora App ID is not configured.")
            _engineState.update { it.copy(lastError = "Agora App ID not configured") }
            return null
        }

        return try {
            val config = RtcEngineConfig().apply {
                mContext = context.applicationContext
                mAppId = appId
                mEventHandler = rtcEventHandler
                mChannelProfile = channelProfile
            }
            val engine = RtcEngine.create(config).apply {
                enableAudio()
                setEnableSpeakerphone(true)
            }
            rtcEngine = engine
            _engineState.update { it.copy(isInitialized = true, lastError = null) }
            engine
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Agora RtcEngine", e)
            _engineState.update { it.copy(lastError = e.message) }
            null
        }
    }

    /**
     * Joins an Agora RTC Channel.
     */
    suspend fun joinChannel(
        channelName: String,
        uid: Long,
        isVideo: Boolean,
        isBroadcaster: Boolean = true,
        channelProfile: Int = Constants.CHANNEL_PROFILE_COMMUNICATION
    ): Boolean {
        val engine = ensureEngine(channelProfile) ?: return false

        // Fetch short-lived token from backend. activeCallId (set by
        // AgoraCallService for 1:1 calls) lets the backend verify participants.
        val role = if (isBroadcaster) AgoraConfig.AgoraRole.PUBLISHER else AgoraConfig.AgoraRole.AUDIENCE
        val tokenResult = tokenService.getRtcToken(channelName, uid, role, activeCallId)
        val token = tokenResult.getOrNull()?.token ?: AgoraConfig.STATIC_FALLBACK_TOKEN

        val options = ChannelMediaOptions().apply {
            this.channelProfile = channelProfile
            this.clientRoleType = if (isBroadcaster) {
                Constants.CLIENT_ROLE_BROADCASTER
            } else {
                Constants.CLIENT_ROLE_AUDIENCE
            }
            this.autoSubscribeAudio = true
            this.autoSubscribeVideo = isVideo
            this.publishCameraTrack = isBroadcaster && isVideo
            this.publishMicrophoneTrack = isBroadcaster
        }

        if (isVideo) {
            engine.enableVideo()
            if (isBroadcaster) {
                engine.startPreview()
            }
        } else {
            engine.disableVideo()
        }

        val result = engine.joinChannel(token, channelName, uid.toInt(), options)
        val success = result == Constants.ERR_OK
        if (!success) {
            Log.e(TAG, "Failed to join channel $channelName: error code $result")
            _engineState.update { it.copy(lastError = "joinChannel returned $result") }
        } else {
            _engineState.update {
                it.copy(
                    isBroadcaster = isBroadcaster,
                    channelName = channelName,
                    localUid = uid.toInt()
                )
            }
        }
        return success
    }

    /**
     * Set up local video view inside a SurfaceView or TextureView.
     */
    fun setupLocalVideo(view: View) {
        rtcEngine?.let { engine ->
            engine.enableVideo()
            val canvas = VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, 0)
            engine.setupLocalVideo(canvas)
            engine.startPreview()
        }
    }

    /**
     * Set up remote video view for a specific remote user ID.
     */
    fun setupRemoteVideo(view: View, uid: Int) {
        rtcEngine?.let { engine ->
            val canvas = VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, uid)
            engine.setupRemoteVideo(canvas)
        }
    }

    fun muteLocalAudio(mute: Boolean) {
        rtcEngine?.muteLocalAudioStream(mute)
        _engineState.update { it.copy(isAudioMuted = mute) }
    }

    fun muteLocalVideo(mute: Boolean) {
        rtcEngine?.muteLocalVideoStream(mute)
        _engineState.update { it.copy(isVideoMuted = mute) }
    }

    fun setSpeakerphoneOn(speakerOn: Boolean) {
        rtcEngine?.setEnableSpeakerphone(speakerOn)
        _engineState.update { it.copy(isSpeakerphoneOn = speakerOn) }
    }

    fun switchCamera() {
        rtcEngine?.switchCamera()
        _engineState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    /**
     * Leaves current channel and stops video preview.
     */
    fun leaveChannel() {
        rtcEngine?.let { engine ->
            try {
                engine.stopPreview()
                engine.leaveChannel()
            } catch (e: Exception) {
                Log.w(TAG, "Error leaving channel", e)
            }
        }
        _engineState.update {
            it.copy(
                isJoined = false,
                remoteUid = null,
                channelName = null,
                connectionState = Constants.CONNECTION_STATE_DISCONNECTED,
                remoteLeftReason = null
            )
        }
    }

    /**
     * Completely destroys the RTC Engine instance.
     */
    @Synchronized
    fun release() {
        leaveChannel()
        try {
            RtcEngine.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying RtcEngine", e)
        }
        rtcEngine = null
        activeCallId = null
        _engineState.value = EngineStatus()
    }

    private fun renewTokenInternal() {
        val currentChannel = _engineState.value.channelName ?: return
        val currentUid = _engineState.value.localUid.toLong()
        val isBroadcaster = _engineState.value.isBroadcaster
        val role = if (isBroadcaster) AgoraConfig.AgoraRole.PUBLISHER else AgoraConfig.AgoraRole.AUDIENCE

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val res = tokenService.getRtcToken(currentChannel, currentUid, role, activeCallId)
            val newToken = res.getOrNull()?.token
            if (!newToken.isNullOrBlank()) {
                rtcEngine?.renewToken(newToken)
                Log.i(TAG, "Successfully renewed Agora RTC token")
            }
        }
    }
}
