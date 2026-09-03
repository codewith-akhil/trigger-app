package com.example.service

import com.example.model.CallState
import com.example.model.CallType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

class CallServiceImpl(
    private val scope: CoroutineScope,
    private val onCallEnded: suspend (contactId: String, type: CallType, durationSec: Int, isMissed: Boolean) -> Unit = { _, _, _, _ -> }
) : CallService {

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall = _currentCall.asStateFlow()

    private var callJob: Job? = null
    private var timerJob: Job? = null

    override fun startCall(contactId: String, contactName: String, avatarRes: Int?, type: CallType) {
        callJob?.cancel()
        timerJob?.cancel()

        val newSession = CallSession(
            callId = UUID.randomUUID().toString(),
            contactId = contactId,
            contactName = contactName,
            avatarRes = avatarRes,
            type = type,
            state = CallState.CALLING
        )
        _currentCall.value = newSession

        callJob = scope.launch {
            // CALLING for 1.2s -> RINGING for 2.2s -> CONNECTING for 0.8s -> CONNECTED
            delay(1200)
            _currentCall.value = _currentCall.value?.copy(state = CallState.RINGING)

            delay(2200)
            _currentCall.value = _currentCall.value?.copy(state = CallState.CONNECTING)

            delay(800)
            _currentCall.value = _currentCall.value?.copy(state = CallState.CONNECTED)

            // Start duration timer
            timerJob = launch {
                while (isActive && _currentCall.value?.state == CallState.CONNECTED) {
                    delay(1000)
                    val current = _currentCall.value ?: break
                    _currentCall.value = current.copy(durationSeconds = current.durationSeconds + 1)
                }
            }
        }
    }

    override fun acceptIncomingCall() {
        _currentCall.value = _currentCall.value?.copy(state = CallState.CONNECTED)
        timerJob = scope.launch {
            while (isActive && _currentCall.value?.state == CallState.CONNECTED) {
                delay(1000)
                val current = _currentCall.value ?: break
                _currentCall.value = current.copy(durationSeconds = current.durationSeconds + 1)
            }
        }
    }

    override fun declineCall() {
        val session = _currentCall.value ?: return
        callJob?.cancel()
        timerJob?.cancel()
        _currentCall.value = session.copy(state = CallState.DECLINED)
        scope.launch {
            onCallEnded(session.contactId, session.type, 0, true)
            delay(600)
            _currentCall.value = null
        }
    }

    override fun endCall() {
        val session = _currentCall.value ?: return
        callJob?.cancel()
        timerJob?.cancel()
        val finalDuration = session.durationSeconds
        _currentCall.value = session.copy(state = CallState.ENDED)
        scope.launch {
            onCallEnded(session.contactId, session.type, finalDuration, false)
            delay(800)
            _currentCall.value = null
        }
    }

    override fun toggleMute() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isMuted = !current.isMuted)
    }

    override fun toggleSpeaker() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isSpeakerOn = !current.isSpeakerOn)
    }

    override fun toggleVideo() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isVideoEnabled = !current.isVideoEnabled)
    }

    override fun switchCamera() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isFrontCamera = !current.isFrontCamera)
    }
}
