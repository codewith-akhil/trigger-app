package com.example.service

import com.example.model.CallState
import com.example.model.CallType
import kotlinx.coroutines.flow.StateFlow

data class CallSession(
    val callId: String,
    val contactId: String,
    val contactName: String,
    val avatarRes: Int? = null,
    val type: CallType,
    val state: CallState = CallState.CALLING,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val isPoorConnection: Boolean = false,
    /** Agora channel both peers join — alnum-only so client & token server agree. */
    val channelName: String = "",
    /** True when this device is being called (shows Accept/Decline UI). */
    val isIncoming: Boolean = false,
    /** Human-readable failure reason surfaced on the call overlay. */
    val errorMessage: String? = null
)

interface CallService {
    val currentCall: StateFlow<CallSession?>
    fun startCall(contactId: String, contactName: String, avatarRes: Int?, type: CallType)
    fun acceptIncomingCall()
    fun declineCall()
    fun endCall()
    fun toggleMute()
    fun toggleSpeaker()
    fun toggleVideo()
    fun switchCamera()
}
