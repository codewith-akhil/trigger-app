package com.example.service.agora

import android.util.Log
import com.example.config.AgoraConfig
import com.example.config.BackendConfig
import com.example.service.supabase.SupabaseClient
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SupabaseEdgeFunctionTokenService(
    private val supabaseClient: SupabaseClient
) : AgoraTokenService {

    companion object {
        private const val TAG = "AgoraTokenService"
        private const val FUNCTION_NAME = "generate-agora-token"
    }

    override suspend fun getRtcToken(
        channelName: String,
        uid: Long,
        role: AgoraConfig.AgoraRole
    ): Result<AgoraTokenResponse> = withContext(Dispatchers.IO) {
        val sanitizedChannel = AgoraConfig.sanitizeChannelName(channelName)

        // 1. Try requesting from the secure Supabase Edge Function
        if (BackendConfig.isSupabaseConfigured) {
            try {
                val payload = JSONObject().apply {
                    put("channelName", sanitizedChannel)
                    put("uid", uid)
                    put("role", role.value)
                    put("expirationSeconds", 3600)
                }

                val response = supabaseClient.invokeFunction(FUNCTION_NAME, payload)
                when (response) {
                    is SupabaseResult.Success -> {
                        val data = response.data
                        val token = data.optString("token", "")
                        val appId = data.optString("appId", AgoraConfig.AGORA_APP_ID)
                        val expiresAt = data.optLong("expiresAt", (System.currentTimeMillis() / 1000) + 3600)

                        Log.d(TAG, "Successfully fetched Agora token for channel $sanitizedChannel from Supabase")
                        return@withContext Result.success(
                            AgoraTokenResponse(
                                token = token,
                                appId = appId,
                                channelName = sanitizedChannel,
                                uid = uid,
                                role = role,
                                expiresAt = expiresAt
                            )
                        )
                    }
                    is SupabaseResult.Error -> {
                        Log.w(TAG, "Edge function error (${response.code}): ${response.message}. Falling back.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed calling Supabase Edge Function $FUNCTION_NAME", e)
            }
        }

        // 2. Fallback for testing mode (e.g. project with App ID only or STATIC_FALLBACK_TOKEN)
        val fallbackToken = AgoraConfig.STATIC_FALLBACK_TOKEN
        val appId = AgoraConfig.AGORA_APP_ID

        if (AgoraConfig.isConfigured) {
            val expiresAt = (System.currentTimeMillis() / 1000) + 3600
            Log.i(TAG, "Using direct Agora App ID configuration for channel $sanitizedChannel")
            return@withContext Result.success(
                AgoraTokenResponse(
                    token = fallbackToken,
                    appId = appId,
                    channelName = sanitizedChannel,
                    uid = uid,
                    role = role,
                    expiresAt = expiresAt
                )
            )
        }

        Result.failure(
            IllegalStateException(
                "Agora is not configured. Please set AGORA_APP_ID in your Secrets or .env file."
            )
        )
    }
}
