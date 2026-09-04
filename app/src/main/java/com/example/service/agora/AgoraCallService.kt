package com.example.service.agora

import android.util.Log
import com.example.config.BackendConfig
import com.example.model.CallState
import com.example.model.CallType
import com.example.service.CallService
import com.example.service.CallSession
import com.example.service.supabase.SupabaseClient
import io.agora.rtc2.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.*
import kotlin.math.abs

class AgoraCallService(
    private val rtcManager: AgoraRtcEngineManager,
    private val supabaseClient: SupabaseClient,
    private val scope: CoroutineScope,
    private val onCallEndedCallback: suspend (contactId: String, type: CallType, durationSec: Int, isMissed: Boolean) -> Unit = { _, _, _, _ -> }
) : CallService {

    companion object {
        private const val TAG = "AgoraCallService"
    }

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private var durationJob: Job? = null
    private var engineStateCollectorJob: Job? = null

    val rtcEngineManager: AgoraRtcEngineManager
        get() = rtcManager

    override fun startCall(contactId: String, contactName: String, avatarRes: Int?, type: CallType) {
        // Clean up any ongoing session
        endCallInternal(saveRecord = false)

        val callId = UUID.randomUUID().toString()
        val channelName = "call_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val isVideo = type == CallType.VIDEO
        val localUid = abs((supabaseClient.currentUser?.id?.hashCode() ?: System.currentTimeMillis().hashCode()) % 1000000).toLong() + 1000

        val session = CallSession(
            callId = callId,
            contactId = contactId,
            contactName = contactName,
            avatarRes = avatarRes,
            type = type,
            state = CallState.CALLING,
            durationSeconds = 0,
            isMuted = false,
            isSpeakerOn = isVideo,
            isVideoEnabled = isVideo,
            isFrontCamera = true,
            isPoorConnection = false
        )
        _currentCall.value = session

        // 1. Record call initiation in Supabase
        scope.launch(Dispatchers.IO) {
            try {
                if (BackendConfig.isSupabaseConfigured) {
                    val record = JSONObject().apply {
                        put("id", callId)
                        put("caller_id", supabaseClient.currentUser?.id ?: "00000000-0000-0000-0000-000000000000")
                        put("receiver_id", contactId)
                        put("call_type", if (isVideo) "video" else "audio")
                        put("channel_name", channelName)
                        put("status", "calling")
                    }
                    supabaseClient.insertRecord("call_sessions", record)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error logging call to Supabase", e)
            }
        }

        // 2. Start monitoring engine state
        listenToEngineState()

        // 3. Connect to real Agora RTC channel
        scope.launch {
            val joined = rtcManager.joinChannel(
                channelName = channelName,
                uid = localUid,
                isVideo = isVideo,
                isBroadcaster = true,
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            )

            if (!joined) {
                Log.e(TAG, "Failed to join Agora channel: $channelName")
                _currentCall.update { it?.copy(state = CallState.FAILED) }
                delay(2000)
                endCallInternal(saveRecord = true, isMissed = true)
            } else {
                _currentCall.update { it?.copy(state = CallState.RINGING) }
            }
        }
    }

    override fun acceptIncomingCall() {
        val session = _currentCall.value ?: return
        listenToEngineState()

        scope.launch {
            val localUid = abs((supabaseClient.currentUser?.id?.hashCode() ?: System.currentTimeMillis().hashCode()) % 1000000).toLong() + 1000
            val channelName = "call_" + session.callId.replace("-", "").take(12)
            val isVideo = session.type == CallType.VIDEO

            val joined = rtcManager.joinChannel(
                channelName = channelName,
                uid = localUid,
                isVideo = isVideo,
                isBroadcaster = true,
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            )

            if (joined) {
                _currentCall.update { it?.copy(state = CallState.CONNECTED) }
                startDurationTimer()
                updateSupabaseCallStatus(session.callId, "connected")
            } else {
                _currentCall.update { it?.copy(state = CallState.FAILED) }
            }
        }
    }

    override fun declineCall() {
        val session = _currentCall.value ?: return
        _currentCall.update { it?.copy(state = CallState.DECLINED) }
        updateSupabaseCallStatus(session.callId, "rejected")
        scope.launch {
            onCallEndedCallback(session.contactId, session.type, 0, true)
            delay(500)
            endCallInternal(saveRecord = false)
        }
    }

    override fun endCall() {
        val session = _currentCall.value ?: return
        val finalDuration = session.durationSeconds
        val wasMissed = session.state != CallState.CONNECTED && finalDuration == 0

        _currentCall.update { it?.copy(state = CallState.ENDED) }
        updateSupabaseCallStatus(session.callId, "ended", finalDuration)

        scope.launch {
            onCallEndedCallback(session.contactId, session.type, finalDuration, wasMissed)
            delay(600)
            endCallInternal(saveRecord = false)
        }
    }

    override fun toggleMute() {
        val current = _currentCall.value ?: return
        val newMute = !current.isMuted
        rtcManager.muteLocalAudio(newMute)
        _currentCall.update { it?.copy(isMuted = newMute) }
    }

    override fun toggleSpeaker() {
        val current = _currentCall.value ?: return
        val newSpeaker = !current.isSpeakerOn
        rtcManager.setSpeakerphoneOn(newSpeaker)
        _currentCall.update { it?.copy(isSpeakerOn = newSpeaker) }
    }

    override fun toggleVideo() {
        val current = _currentCall.value ?: return
        val newVideo = !current.isVideoEnabled
        rtcManager.muteLocalVideo(!newVideo)
        _currentCall.update { it?.copy(isVideoEnabled = newVideo) }
    }

    override fun switchCamera() {
        val current = _currentCall.value ?: return
        rtcManager.switchCamera()
        _currentCall.update { it?.copy(isFrontCamera = !current.isFrontCamera) }
    }

    private fun listenToEngineState() {
        engineStateCollectorJob?.cancel()
        engineStateCollectorJob = scope.launch {
            rtcManager.engineState.collect { status ->
                val current = _currentCall.value ?: return@collect

                // Remote user joined -> connected!
                if (status.remoteUid != null && current.state != CallState.CONNECTED) {
                    _currentCall.update { it?.copy(state = CallState.CONNECTED) }
                    startDurationTimer()
                    updateSupabaseCallStatus(current.callId, "connected")
                }

                // Poor connection update
                if (current.isPoorConnection != status.isPoorConnection) {
                    _currentCall.update { it?.copy(isPoorConnection = status.isPoorConnection) }
                }

                // Error handling
                if (status.lastError != null && current.state != CallState.ENDED && current.state != CallState.DECLINED) {
                    Log.w(TAG, "Call error observed: ${status.lastError}")
                }
            }
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive && _currentCall.value?.state == CallState.CONNECTED) {
                delay(1000)
                _currentCall.update { current ->
                    current?.copy(durationSeconds = current.durationSeconds + 1)
                }
            }
        }
    }

    private fun endCallInternal(saveRecord: Boolean = true, isMissed: Boolean = false) {
        durationJob?.cancel()
        durationJob = null
        engineStateCollectorJob?.cancel()
        engineStateCollectorJob = null

        rtcManager.leaveChannel()

        val session = _currentCall.value
        if (session != null && saveRecord) {
            scope.launch {
                onCallEndedCallback(session.contactId, session.type, session.durationSeconds, isMissed)
            }
        }

        _currentCall.value = null
    }

    private fun updateSupabaseCallStatus(callId: String, status: String, duration: Int = 0) {
        if (!BackendConfig.isSupabaseConfigured) return
        scope.launch(Dispatchers.IO) {
            try {
                val updateJson = JSONObject().apply {
                    put("id", callId)
                    put("status", status)
                    if (status == "connected") {
                        put("answered_at", java.time.Instant.now().toString())
                    } else if (status == "ended") {
                        put("ended_at", java.time.Instant.now().toString())
                        put("duration_seconds", duration)
                    }
                }
                supabaseClient.upsertRecord("call_sessions", updateJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating call status in Supabase", e)
            }
        }
    }
}
