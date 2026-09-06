package com.example.service.agora

import android.util.Log
import com.example.config.BackendConfig
import com.example.model.CallState
import com.example.model.CallType
import com.example.service.CallService
import com.example.service.CallSession
import com.example.service.supabase.SupabaseClient
import com.example.service.supabase.SupabaseResult
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
        /** Unanswered-call timeout (ringing forever previously). */
        private const val RING_TIMEOUT_MS = 45_000L
        /** Incoming-call polling interval. */
        private const val INCOMING_POLL_MS = 4_000L
        /** A ring older than this is stale (caller already gave up). */
        private const val RING_STALE_MS = 60_000L
    }

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private var durationJob: Job? = null
    private var engineStateCollectorJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var incomingMonitorJob: Job? = null

    val rtcEngineManager: AgoraRtcEngineManager
        get() = rtcManager

    init {
        // Watch for incoming calls so the callee can actually receive one.
        startIncomingCallMonitor()
    }

    /**
     * Alphanumeric-only channel name. The deployed generate-agora-token
     * function strips everything except [A-Za-z0-9] — previously the client
     * kept "_"/"-" so the token was signed for a DIFFERENT channel than the
     * one joined (ERR_INVALID_TOKEN on every call). With alnum-only names the
     * sanitizer is a no-op and both sides always agree.
     */
    private fun generateChannelName(): String =
        "call" + UUID.randomUUID().toString().replace("-", "").take(12)

    override fun startCall(contactId: String, contactName: String, avatarRes: Int?, type: CallType) {
        // Clean up any ongoing session
        endCallInternal(saveRecord = false)

        // Camera / mic runtime permission gate — previously a first-time video
        // call silently rendered black with no prompt.
        val missingPermission = missingCallPermissions(type)
        if (missingPermission != null) {
            _currentCall.value = CallSession(
                callId = "",
                contactId = contactId,
                contactName = contactName,
                avatarRes = avatarRes,
                type = type,
                state = CallState.FAILED,
                errorMessage = missingPermission
            )
            scope.launch {
                delay(3000)
                if (_currentCall.value?.state == CallState.FAILED) {
                    _currentCall.value = null
                }
            }
            return
        }

        val callId = UUID.randomUUID().toString()
        val channelName = generateChannelName()
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
            isPoorConnection = false,
            channelName = channelName
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
                _currentCall.update { it?.copy(state = CallState.FAILED, errorMessage = "Couldn't connect the call") }
                delay(2000)
                endCallInternal(saveRecord = true, isMissed = true)
            } else {
                _currentCall.update { it?.copy(state = CallState.RINGING) }
                startRingTimeout()
            }
        }
    }

    override fun acceptIncomingCall() {
        val session = _currentCall.value ?: return
        if (!session.isIncoming) return
        ringTimeoutJob?.cancel()
        listenToEngineState()

        // Join the EXACT channel the caller stored in call_sessions. The old
        // code derived a channel from callId — a channel the caller never joined.
        val channelName = session.channelName.ifBlank { generateChannelName() }
        val isVideo = session.type == CallType.VIDEO

        _currentCall.update { it?.copy(state = CallState.CONNECTING) }
        updateSupabaseCallStatus(session.callId, "connecting")

        scope.launch {
            val localUid = abs((supabaseClient.currentUser?.id?.hashCode() ?: System.currentTimeMillis().hashCode()) % 1000000).toLong() + 1000

            val joined = rtcManager.joinChannel(
                channelName = channelName,
                uid = localUid,
                isVideo = isVideo,
                isBroadcaster = true,
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            )

            if (joined) {
                // engineState collector flips to CONNECTED when the remote user joins
                updateSupabaseCallStatus(session.callId, "connected")
            } else {
                _currentCall.update { it?.copy(state = CallState.FAILED, errorMessage = "Couldn't connect the call") }
                delay(2000)
                endCallInternal(saveRecord = false)
            }
        }
    }

    override fun declineCall() {
        val session = _currentCall.value ?: return
        ringTimeoutJob?.cancel()
        _currentCall.update { it?.copy(state = CallState.DECLINED) }
        updateSupabaseCallStatus(session.callId, "rejected")
        scope.launch {
            if (!session.isIncoming) {
                onCallEndedCallback(session.contactId, session.type, 0, true)
            }
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

                // Remote user left (hung up) -> auto-end the call
                if (status.remoteUid == null && current.state == CallState.CONNECTED) {
                    Log.i(TAG, "Remote user left the call, auto-ending")
                    endCall()
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
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null

        rtcManager.leaveChannel()

        val session = _currentCall.value
        if (session != null && saveRecord) {
            scope.launch {
                onCallEndedCallback(session.contactId, session.type, session.durationSeconds, isMissed)
            }
        }

        _currentCall.value = null
    }

    // ---------------------------------------------------------------
    // Incoming calls
    // ---------------------------------------------------------------

    /**
     * Polls call_sessions for ringing calls addressed to this user. This is
     * the incoming-call path that previously DID NOT EXIST — the callee could
     * never even know a call was arriving. Polling (4s) is used instead of
     * Realtime because the shared realtime channel applies a conversation_id
     * filter that call_sessions rows don't satisfy.
     */
    private fun startIncomingCallMonitor() {
        if (incomingMonitorJob != null) return
        incomingMonitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (BackendConfig.isSupabaseConfigured && _currentCall.value == null) {
                        val myId = supabaseClient.currentUser?.id
                        if (!myId.isNullOrBlank()) {
                            val res = supabaseClient.getTable(
                                "call_sessions",
                                "select=*&receiver_id=eq.$myId&status=in.(calling,ringing)&order=created_at.desc&limit=1"
                            )
                            if (res is SupabaseResult.Success && res.data.length() > 0) {
                                val row = res.data.getJSONObject(0)
                                val createdMs = parseIsoMillis(row.optString("created_at"))
                                val fresh = createdMs == null ||
                                    (System.currentTimeMillis() - createdMs) < RING_STALE_MS
                                if (fresh) {
                                    val callerId = row.optString("caller_id", "")
                                    val session = CallSession(
                                        callId = row.optString("id", ""),
                                        contactId = callerId,
                                        contactName = fetchCallerName(callerId),
                                        avatarRes = null,
                                        type = if (row.optString("call_type") == "video") CallType.VIDEO else CallType.AUDIO,
                                        state = CallState.RINGING,
                                        isIncoming = true,
                                        channelName = row.optString("channel_name", "")
                                    )
                                    _currentCall.value = session
                                    startIncomingTimeout()
                                }
                            }
                        }
                    }

                    // If an incoming ring is showing and the caller gave up,
                    // dismiss it.
                    val current = _currentCall.value
                    if (current != null && current.isIncoming && current.state == CallState.RINGING) {
                        val res = supabaseClient.getTable(
                            "call_sessions",
                            "select=status&id=eq.${current.callId}"
                        )
                        if (res is SupabaseResult.Success) {
                            if (res.data.length() == 0) {
                                _currentCall.value = null
                            } else {
                                val status = res.data.getJSONObject(0).optString("status", "calling")
                                if (status !in listOf("calling", "ringing")) {
                                    _currentCall.value = null
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Incoming call poll error: ${e.message}")
                }
                delay(INCOMING_POLL_MS)
            }
        }
    }

    private suspend fun fetchCallerName(callerId: String): String {
        return try {
            val res = supabaseClient.getTable("profiles", "select=full_name&id=eq.$callerId")
            if (res is SupabaseResult.Success && res.data.length() > 0) {
                res.data.getJSONObject(0).optString("full_name", "").ifBlank { "Incoming call" }
            } else "Incoming call"
        } catch (e: Exception) {
            "Incoming call"
        }
    }

    private fun parseIsoMillis(iso: String): Long? = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------
    // Timeouts + permissions
    // ---------------------------------------------------------------

    /** Outgoing call nobody answers within 45s → mark missed, tear down. */
    private fun startRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val current = _currentCall.value
            if (current != null && (current.state == CallState.RINGING || current.state == CallState.CALLING)) {
                Log.i(TAG, "Outgoing call timed out unanswered — marking missed")
                updateSupabaseCallStatus(current.callId, "missed")
                _currentCall.update { it?.copy(state = CallState.MISSED) }
                scope.launch { onCallEndedCallback(current.contactId, current.type, 0, true) }
                delay(1500)
                endCallInternal(saveRecord = false)
            }
        }
    }

    /** Incoming ring nobody answers within 45s → dismiss + mark missed. */
    private fun startIncomingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val current = _currentCall.value
            if (current != null && current.isIncoming && current.state == CallState.RINGING) {
                Log.i(TAG, "Incoming call timed out unanswered — marking missed")
                updateSupabaseCallStatus(current.callId, "missed")
                endCallInternal(saveRecord = false)
            }
        }
    }

    /** Returns a human-readable message if call permissions are missing, else null. */
    private fun missingCallPermissions(type: CallType): String? {
        val context = try { com.example.di.AppServiceContainer.context } catch (e: Exception) { return null }
        val hasMic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMic) return "Microphone permission is required for calls"
        if (type == CallType.VIDEO) {
            val hasCam = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasCam) return "Camera permission is required for video calls"
        }
        return null
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
