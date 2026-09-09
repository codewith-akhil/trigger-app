package com.example.service.agora

import com.example.config.AgoraConfig

data class AgoraTokenResponse(
    val token: String,
    val appId: String,
    val channelName: String,
    val uid: Long,
    val role: AgoraConfig.AgoraRole,
    val expiresAt: Long
)

interface AgoraTokenService {
    /**
     * Obtains an RTC token for joining an Agora audio/video channel.
     * The token is requested from the secure backend (Supabase Edge Function).
     *
     * @param callId when joining a 1:1 CALL channel, pass the call_sessions id
     *   so the backend can verify this user is a participant of that call and
     *   mint the token for the row's channel. Null for stream channels.
     */
    suspend fun getRtcToken(
        channelName: String,
        uid: Long,
        role: AgoraConfig.AgoraRole = AgoraConfig.AgoraRole.PUBLISHER,
        callId: String? = null
    ): Result<AgoraTokenResponse>
}
