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
    val isPoorConnection: Boolean = false
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
