package com.example.service.agora

import android.util.Log
import com.example.config.BackendConfig
import com.example.model.CallState
import com.example.model.CallType
import com.example.service.CallService
import com.example.service.CallSession
import com.example.service.IncomingCallNotificationHelper
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
    private val onCallEndedCallback: suspend (contactId: String, type: CallType, durationSec: Int, isMissed: Boolean, isOutgoing: Boolean) -> Unit = { _, _, _, _, _ -> }
) : CallService {

    companion object {
        private const val TAG = "AgoraCallService"
        /** Unanswered-call timeout (ringing forever previously). */
        private const val RING_TIMEOUT_MS = 45_000L
        /** Incoming-call polling interval (fallback — FCM push is the primary wake-up). */
        private const val INCOMING_POLL_MS = 4_000L
        /** A ring older than this is stale (caller already gave up). */
        private const val RING_STALE_MS = 60_000L
        /** How often the CALLER re-reads the call row while ringing so a callee
         *  decline/answer is reflected immediately (was: 45 s MISSED always). */
        private const val OUTGOING_STATUS_POLL_MS = 2_500L
        /** Agora USER_OFFLINE reason: normal hang-up. */
        private const val USER_OFFLINE_QUIT = 0
        /** Grace period before ending a call whose remote DROPPED (network loss
         *  can recover — Agora auto-rejoins). */
        private const val RECONNECT_GRACE_MS = 20_000L
        /** Callee joined the channel but the caller never appeared — end honestly
         *  as MISSED instead of sitting in CONNECTING forever. */
        private const val CONNECTING_TIMEOUT_MS = 15_000L
    }

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private var durationJob: Job? = null
    private var engineStateCollectorJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var incomingMonitorJob: Job? = null
    private var outgoingStatusPollJob: Job? = null
    private var reconnectGraceJob: Job? = null
    private var connectingTimeoutJob: Job? = null

    private val incomingCallNotificationHelper by lazy {
        IncomingCallNotificationHelper(com.example.di.AppServiceContainer.context)
    }

    val rtcEngineManager: AgoraRtcEngineManager
        get() = rtcManager

    init {
        // Ongoing-call foreground service: keeps mic/camera access alive while
        // an active call is backgrounded (Android 12+/14+ would otherwise cut
        // off the audio/video). Bound to the session lifecycle.
        // Hardened: only starts for a session that actually carries media
        // (CONNECTED / RECONNECTING) — never for a mere RINGING session, and
        // every start/stop is exception-guarded. An unguarded background start
        // from the 4 s poll path crashed on Android 12+ (ForegroundService-
        // StartNotAllowedException) because appScope had no exception handler.
        scope.launch {
            _currentCall.collect { session ->
                val ctx = try { com.example.di.AppServiceContainer.context } catch (_: Exception) { null }
                if (ctx == null) return@collect
                try {
                    when {
                        session == null ->
                            com.example.service.OngoingCallService.stop(ctx)
                        session.state == CallState.CONNECTED ||
                            session.state == CallState.RECONNECTING ->
                            com.example.service.OngoingCallService.start(ctx)
                        // RINGING/CONNECTING/FAILED sessions: leave the FGS as-is
                        // (it is stopped when the session ends).
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OngoingCallService toggle failed (state=${session?.state}): ${e.message}")
                }
            }
        }
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
        val localUid = stableAgoraUid(supabaseClient.currentUser?.id)

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

        // 1. Record call initiation in Supabase, then WAKE the receiver with a
        //    data-only FCM push (send-call-invite). This is the real signaling
        //    path — polling remains as a fallback but is no longer the primary
        //    way a callee learns about the call.
        scope.launch(Dispatchers.IO) {
            var rowInserted = false
            try {
                val callerId = supabaseClient.currentUser?.id
                if (BackendConfig.isSupabaseConfigured && callerId != null) {
                    val record = JSONObject().apply {
                        put("id", callId)
                        // Real auth UUID only — the placeholder uuid previously
                        // violated the caller_id FK and the row was silently
                        // dropped, so the callee's poll never saw the ring.
                        put("caller_id", callerId)
                        put("receiver_id", contactId)
                        put("call_type", if (isVideo) "video" else "audio")
                        put("channel_name", channelName)
                        put("status", "calling")
                    }
                    supabaseClient.insertRecord("call_sessions", record)
                    rowInserted = true
                    // Push the ring to the receiver's devices (foreground AND
                    // background/killed — data-only FCM wakes the FCM service).
                    supabaseClient.invokeFunction(
                        "send-call-invite",
                        JSONObject().apply {
                            put("callId", callId)
                            put("action", "invite")
                        }
                    )
                } else {
                    Log.w(TAG, "Call not logged: ${if (callerId == null) "no signed-in user" else "supabase not configured"}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error logging call / sending invite to Supabase", e)
            }
            if (rowInserted) startOutgoingStatusPoll()
        }

        // 2. Start monitoring engine state
        listenToEngineState()

        // 3. Connect to real Agora RTC channel
        scope.launch {
            rtcManager.activeCallId = callId
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
                // Same speaker parity as the callee path (voice=earpiece, video=speaker).
                rtcManager.setSpeakerphoneOn(isVideo)
                _currentCall.update { it?.copy(state = CallState.RINGING) }
                startRingTimeout()
            }
        }
    }

    override fun acceptIncomingCall() {
        val session = _currentCall.value ?: return
        if (!session.isIncoming) return
        // Callee-side permission gate — previously the accept path never checked
        // RECORD_AUDIO/CAMERA: a fresh callee who answered first joined the
        // channel with a dead microphone (one-way audio, no prompt), and on
        // Android 14+ starting the microphone|camera FGS without the permission
        // threw SecurityException.
        val missingPermission = missingCallPermissions(session.type)
        if (missingPermission != null) {
            Log.w(TAG, "Accept rejected: $missingPermission")
            ringTimeoutJob?.cancel()
            incomingCallNotificationHelper.cancelCallNotification()
            _currentCall.update {
                it?.copy(state = CallState.FAILED, errorMessage = "$missingPermission. Open Trigger, grant it, then call back.")
            }
            updateSupabaseCallStatus(session.callId, "missed")
            scope.launch {
                delay(4000)
                if (_currentCall.value?.state == CallState.FAILED) {
                    endCallInternal(saveRecord = false)
                }
            }
            return
        }
        ringTimeoutJob?.cancel()
        listenToEngineState()

        // Join the EXACT channel the caller stored in call_sessions. The old
        // code derived a channel from callId — a channel the caller never joined.
        val channelName = session.channelName.ifBlank { generateChannelName() }
        val isVideo = session.type == CallType.VIDEO

        _currentCall.update { it?.copy(state = CallState.CONNECTING) }
        incomingCallNotificationHelper.cancelCallNotification()
        updateSupabaseCallStatus(session.callId, "connecting")

        scope.launch {
            val localUid = stableAgoraUid(supabaseClient.currentUser?.id)

            rtcManager.activeCallId = session.callId
            val joined = rtcManager.joinChannel(
                channelName = channelName,
                uid = localUid,
                isVideo = isVideo,
                isBroadcaster = true,
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            )

            if (joined) {
                // Speaker parity with the UI default: voice calls start on the
                // earpiece, video calls on the loudspeaker (the engine enabled
                // speakerphone unconditionally at init, so an audio call actually
                // played on the loudspeaker while the UI claimed earpiece).
                rtcManager.setSpeakerphoneOn(isVideo)
                // engineState collector flips to CONNECTED when the remote user joins
                updateSupabaseCallStatus(session.callId, "connected")
                startConnectingTimeout()
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
        outgoingStatusPollJob?.cancel()
        _currentCall.update { it?.copy(state = CallState.DECLINED) }
        updateSupabaseCallStatus(session.callId, "rejected")
        incomingCallNotificationHelper.cancelCallNotification()
        scope.launch {
            if (!session.isIncoming) {
                // The CALLER cancelled their own outgoing ring — tell the
                // receiver's devices to remove the incoming-call UI.
                try {
                    supabaseClient.invokeFunction(
                        "send-call-invite",
                        JSONObject().apply {
                            put("callId", session.callId)
                            put("action", "cancel")
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send call-cancel push", e)
                }
                onCallEndedCallback(session.contactId, session.type, 0, true, true)
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
        incomingCallNotificationHelper.cancelCallNotification()
        // If the receiver never picked up, remove the ring from their devices.
        if (session.state != CallState.CONNECTED) {
            scope.launch(Dispatchers.IO) {
                try {
                    supabaseClient.invokeFunction(
                        "send-call-invite",
                        JSONObject().apply {
                            put("callId", session.callId)
                            put("action", "cancel")
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send call-cancel push", e)
                }
            }
        }

        scope.launch {
            onCallEndedCallback(session.contactId, session.type, finalDuration, wasMissed, !session.isIncoming)
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

    override fun dismissIncomingRing() {
        val current = _currentCall.value ?: return
        if (!current.isIncoming || current.state != CallState.RINGING) return
        ringTimeoutJob?.cancel()
        incomingCallNotificationHelper.cancelCallNotification()
        _currentCall.value = null
    }

    // ---------------------------------------------------------------
    // Notification-intent entry points (Accept/Decline from the heads-up
    // notification while the app is backgrounded or the process was dead).
    // ---------------------------------------------------------------

    /**
     * Called from MainActivity for ACTION_ACCEPT_CALL. Handles both the
     * in-process case (ringing session present) and the cold-start case
     * (process was killed — hydrate the session from call_sessions first).
     */
    override fun handleNotificationAccept(callId: String) {
        val current = _currentCall.value
        if (current != null && current.callId == callId && current.isIncoming) {
            acceptIncomingCall()
            return
        }
        scope.launch(Dispatchers.IO) {
            val session = hydrateIncomingSession(callId)
            if (session != null) {
                _currentCall.value = session
                incomingCallNotificationHelper.cancelCallNotification()
                withContext(Dispatchers.Main) { acceptIncomingCall() }
            } else {
                Log.w(TAG, "Notification accept: call $callId not found / not ringing")
            }
        }
    }

    /** Called from MainActivity for ACTION_DECLINE_CALL. */
    override fun handleNotificationDecline(callId: String) {
        val current = _currentCall.value
        if (current != null && current.callId == callId && current.isIncoming) {
            declineCall()
            return
        }
        scope.launch(Dispatchers.IO) {
            val session = hydrateIncomingSession(callId)
            if (session != null) {
                // Persist the rejection even without joining the channel.
                updateSupabaseCallStatus(callId, "rejected")
                incomingCallNotificationHelper.cancelCallNotification()
            }
        }
    }

    private suspend fun hydrateIncomingSession(callId: String): CallSession? {
        return try {
            val myId = supabaseClient.currentUser?.id ?: return null
            val res = supabaseClient.getTable(
                "call_sessions",
                "select=*&id=eq.$callId&receiver_id=eq.$myId&status=in.(calling,ringing)&limit=1"
            )
            if (res is SupabaseResult.Success && res.data.length() > 0) {
                val row = res.data.getJSONObject(0)
                val callerId = row.optString("caller_id", "")
                CallSession(
                    callId = row.optString("id", ""),
                    contactId = callerId,
                    contactName = fetchCallerName(callerId),
                    avatarRes = null,
                    type = if (row.optString("call_type") == "video") CallType.VIDEO else CallType.AUDIO,
                    state = CallState.RINGING,
                    isIncoming = true,
                    channelName = row.optString("channel_name", "")
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "hydrateIncomingSession failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------
    // Engine-state driven state machine
    // ---------------------------------------------------------------

    private fun listenToEngineState() {
        engineStateCollectorJob?.cancel()
        engineStateCollectorJob = scope.launch {
            rtcManager.engineState.collect { status ->
                val current = _currentCall.value ?: return@collect

                // Remote user joined -> connected! CONNECTED is driven ONLY by
                // the real Agora onUserJoined callback — never by a timer.
                if (status.remoteUid != null && current.state != CallState.CONNECTED) {
                    _currentCall.update {
                        it?.copy(
                            state = CallState.CONNECTED,
                            durationSeconds = it.durationSeconds
                        )
                    }
                    startDurationTimer()
                    updateSupabaseCallStatus(current.callId, "connected", startedAt = true)
                    reconnectGraceJob?.cancel()
                }

                // Engine reconnecting after an established connection — surface
                // an honest RECONNECTING state instead of pretending all is well.
                if (current.state == CallState.CONNECTED &&
                    status.connectionState == Constants.CONNECTION_STATE_RECONNECTING
                ) {
                    _currentCall.update { it?.copy(state = CallState.RECONNECTING) }
                }

                // Connection recovered → back to CONNECTED.
                if (current.state == CallState.RECONNECTING &&
                    status.connectionState == Constants.CONNECTION_STATE_CONNECTED &&
                    status.remoteUid != null
                ) {
                    _currentCall.update { it?.copy(state = CallState.CONNECTED) }
                    // The duration loop exits while not CONNECTED — restart it so
                    // duration_seconds keeps counting after a reconnect.
                    startDurationTimer()
                    reconnectGraceJob?.cancel()
                }

                // Remote user left (hung up) -> auto-end the call. Distinguish
                // a real hang-up (QUIT) from a network drop (DROPPED): a drop
                // gets a grace window to rejoin before we end the call.
                if (status.remoteUid == null && current.state.isInConnectedFamily()) {
                    val reason = status.remoteLeftReason
                    if (current.state == CallState.RECONNECTING || reason == USER_OFFLINE_QUIT || reason == null) {
                        Log.i(TAG, "Remote user left the call (reason=$reason), auto-ending")
                        endCall()
                    } else {
                        Log.i(TAG, "Remote dropped (reason=$reason) — grace window before ending")
                        startReconnectGrace()
                    }
                }

                // Poor connection update
                if (current.isPoorConnection != status.isPoorConnection) {
                    _currentCall.update { it?.copy(isPoorConnection = status.isPoorConnection) }
                }

                // Error handling — surface pre-join failures instead of only
                // logging them. A token/engine error while CALLING/RINGING left
                // the call hanging until the 45 s timeout looked like "no
                // answer"; now it becomes a visible, honest FAILED state.
                if (status.lastError != null && current.state != CallState.ENDED && current.state != CallState.DECLINED) {
                    Log.w(TAG, "Call error observed: ${status.lastError}")
                    val preJoin = current.state == CallState.CALLING ||
                        current.state == CallState.RINGING || current.state == CallState.CONNECTING
                    if (preJoin) {
                        _currentCall.update {
                            it?.copy(state = CallState.FAILED, errorMessage = status.lastError)
                        }
                        endCallInternal(saveRecord = true, isMissed = true)
                    }
                }
            }
        }
    }

    private fun CallState.isInConnectedFamily(): Boolean =
        this == CallState.CONNECTED || this == CallState.RECONNECTING

    /** Remote dropped — end the call if they do not return within the grace window. */
    private fun startReconnectGrace() {
        reconnectGraceJob?.cancel()
        reconnectGraceJob = scope.launch {
            delay(RECONNECT_GRACE_MS)
            val current = _currentCall.value
            if (current != null && current.state == CallState.RECONNECTING) {
                Log.i(TAG, "Reconnect grace expired — ending call")
                _currentCall.update { it?.copy(state = CallState.FAILED, errorMessage = "Connection lost") }
                delay(800)
                endCallInternal(saveRecord = true, isMissed = false)
            }
        }
    }

    /**
     * While the CALLER is ringing, re-read the call row so a callee DECLINE
     * (status=rejected) or an answered-elsewhere is reflected immediately —
     * previously a decline made the caller ring the full 45 s and end as
     * MISSED, and the call log said "Missed" for an actively declined call.
     */
    private fun startOutgoingStatusPoll() {
        outgoingStatusPollJob?.cancel()
        outgoingStatusPollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(OUTGOING_STATUS_POLL_MS)
                val current = _currentCall.value ?: break
                if (current.isIncoming) break
                if (current.state != CallState.CALLING && current.state != CallState.RINGING) {
                    if (current.state != CallState.CONNECTED) break
                    // Once CONNECTED keep watching for callee-initiated end.
                } else {
                    try {
                        val res = supabaseClient.getTable(
                            "call_sessions",
                            "select=status&id=eq.${current.callId}"
                        )
                        if (res is SupabaseResult.Success && res.data.length() > 0) {
                            val status = res.data.getJSONObject(0).optString("status", "calling")
                            when (status) {
                                "rejected" -> {
                                    Log.i(TAG, "Callee declined — surfacing DECLINED")
                                    withContext(Dispatchers.Main) {
                                        _currentCall.update { it?.copy(state = CallState.DECLINED) }
                                    }
                                    scope.launch {
                                        onCallEndedCallback(current.contactId, current.type, 0, false, true)
                                    }
                                    delay(1500)
                                    endCallInternal(saveRecord = false)
                                    break
                                }
                                "ended", "missed" -> {
                                    // Callee side already closed the call.
                                    withContext(Dispatchers.Main) { endCallInternal(saveRecord = false) }
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Outgoing status poll error: ${e.message}")
                    }
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
        outgoingStatusPollJob?.cancel()
        outgoingStatusPollJob = null
        reconnectGraceJob?.cancel()
        reconnectGraceJob = null
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null

        rtcManager.activeCallId = null
        rtcManager.leaveChannel()
        incomingCallNotificationHelper.cancelCallNotification()

        val session = _currentCall.value
        if (session != null && saveRecord) {
            scope.launch {
                onCallEndedCallback(session.contactId, session.type, session.durationSeconds, isMissed, !session.isIncoming)
            }
        }

        _currentCall.value = null
    }

    // ---------------------------------------------------------------
    // Incoming calls
    // ---------------------------------------------------------------

    /**
     * Polls call_sessions for ringing calls addressed to this user. This is
     * the FALLBACK discovery path — the primary wake-up is the data-only FCM
     * push sent by the send-call-invite edge function (fires even when the
     * app is killed, where Android allows it). Polling (4s) covers pushes
     * being unavailable (no token yet, FCM outage).
     */
    /**
     * Collision-safe Agora uid: takes the last 6 hex chars of the user uuid
     * (uniform 24 bits + 1000 offset). hashCode()%1e6 collided for some user
     * pairs → both joined the channel as the SAME uid and kicked each other.
     */
    private fun stableAgoraUid(userId: String?): Long {
        val id = userId ?: return (System.currentTimeMillis() % 1_000_000L) + 1000L
        val hex = id.replace("-", "").takeLast(6)
        return (hex.toLongOrNull(16) ?: (id.hashCode().toLong() and 0xFFFFFFL)) + 1000L
    }

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
                                    // Acknowledge the ring so the caller's
                                    // status poll can tell "delivered" from
                                    // "device unreachable".
                                    updateSupabaseCallStatus(session.callId, "ringing")
                                    // Heads-up/full-screen notification so an
                                    // incoming call is visible (and answerable)
                                    // while the app is BACKGROUND — previously
                                    // the ring only existed in-app and a
                                    // backgrounded user missed every call.
                                    incomingCallNotificationHelper.showIncomingCallNotification(
                                        callId = session.callId,
                                        callerName = session.contactName ?: "Incoming call",
                                        isVideo = session.type == CallType.VIDEO
                                    )
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
                                incomingCallNotificationHelper.cancelCallNotification()
                            } else {
                                val status = res.data.getJSONObject(0).optString("status", "calling")
                                if (status !in listOf("calling", "ringing")) {
                                    _currentCall.value = null
                                    incomingCallNotificationHelper.cancelCallNotification()
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
            if (current != null && !current.isIncoming &&
                (current.state == CallState.RINGING || current.state == CallState.CALLING)
            ) {
                Log.i(TAG, "Outgoing call timed out unanswered — marking missed")
                updateSupabaseCallStatus(current.callId, "missed")
                _currentCall.update { it?.copy(state = CallState.MISSED) }
                // Remove the ring from the receiver's devices.
                scope.launch(Dispatchers.IO) {
                    try {
                        supabaseClient.invokeFunction(
                            "send-call-invite",
                            JSONObject().apply {
                                put("callId", current.callId)
                                put("action", "cancel")
                            }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send call-cancel push", e)
                    }
                }
                scope.launch { onCallEndedCallback(current.contactId, current.type, 0, true, true) }
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
                incomingCallNotificationHelper.cancelCallNotification()
                endCallInternal(saveRecord = false)
            }
        }
    }

    /**
     * Callee accepted + joined but the caller never appeared within 15 s
     * (caller crashed / network died mid-ring) — end honestly as MISSED
     * instead of parking the callee in CONNECTING forever. The caller's
     * status poll sees "missed" and tears down on its side too.
     */
    private fun startConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            val current = _currentCall.value
            if (current != null && current.state == CallState.CONNECTING) {
                Log.i(TAG, "CONNECTING timed out — marking missed")
                updateSupabaseCallStatus(current.callId, "missed")
                _currentCall.update { it?.copy(state = CallState.MISSED, errorMessage = "Couldn't connect the call") }
                scope.launch(Dispatchers.IO) {
                    try {
                        supabaseClient.invokeFunction(
                            "send-call-invite",
                            JSONObject().apply {
                                put("callId", current.callId)
                                put("action", "cancel")
                            }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send call-cancel push", e)
                    }
                }
                delay(1500)
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

    private fun updateSupabaseCallStatus(
        callId: String,
        status: String,
        duration: Int = 0,
        startedAt: Boolean = false
    ) {
        if (!BackendConfig.isSupabaseConfigured) return
        scope.launch(Dispatchers.IO) {
            try {
                val updateJson = JSONObject().apply {
                    put("id", callId)
                    put("status", status)
                    if (status == "connected") {
                        put("answered_at", java.time.Instant.now().toString())
                        // Real call start — the Calls tab previously sorted on
                        // a column nobody wrote (NULLS-first arbitrary order).
                        put("started_at", java.time.Instant.now().toString())
                    }
                    if (startedAt) {
                        put("started_at", java.time.Instant.now().toString())
                    }
                    if (status == "ended") {
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
