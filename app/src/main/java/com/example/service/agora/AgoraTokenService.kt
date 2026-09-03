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
     */
    suspend fun getRtcToken(
        channelName: String,
        uid: Long,
        role: AgoraConfig.AgoraRole = AgoraConfig.AgoraRole.PUBLISHER
    ): Result<AgoraTokenResponse>
}
