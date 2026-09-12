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
        role: AgoraConfig.AgoraRole,
        callId: String?
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
                    // Participant authorization: the backend verifies this user
                    // belongs to the call and signs the token for the ROW's
                    // channel (prevents minting tokens for arbitrary channels).
                    if (!callId.isNullOrBlank()) put("callId", callId)
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

        // 2. Fallback for testing mode (e.g. project with App ID only)
        // AGORA_TOKEN="none" is a PLACEHOLDER baked into BuildConfig — it is
        // not a real Agora token and can never authenticate a join. Previously
        // this returned Result.success("none") and the UI showed "Ringing..."
        // forever. Treat placeholder as NO fallback.
        val rawFallback = AgoraConfig.STATIC_FALLBACK_TOKEN.trim()
        val fallbackToken = if (rawFallback.equals("none", true) ||
            rawFallback.equals("null", true) || rawFallback == "0" || rawFallback.isEmpty()
        ) "" else rawFallback
        val appId = AgoraConfig.AGORA_APP_ID

        if (AgoraConfig.isConfigured && fallbackToken.isNotEmpty()) {
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

        if (AgoraConfig.isConfigured && fallbackToken.isEmpty()) {
            // App ID present but no usable static token. If the project is
            // App-ID-only (no certificate) an EMPTY token joins fine; if it is
            // certificate-secured the join fails with ERR_INVALID_TOKEN — which
            // the engine surfaces as CallState.FAILED instead of ringing forever.
            Log.w(TAG, "Edge function unavailable and no static token — attempting join with empty token (App-ID-only mode)")
            return@withContext Result.success(
                AgoraTokenResponse(
                    token = "",
                    appId = appId,
                    channelName = sanitizedChannel,
                    uid = uid,
                    role = role,
                    expiresAt = (System.currentTimeMillis() / 1000) + 3600
                )
            )
        }

        Log.e(TAG, "Agora not configured: AGORA_APP_ID missing/placeholder in BuildConfig (.env).")
        Result.failure(
            // User-safe message only — internal details go to Logcat above.
            IllegalStateException(BackendConfig.USER_SAFE_CONFIG_ERROR)
        )
    }
}
