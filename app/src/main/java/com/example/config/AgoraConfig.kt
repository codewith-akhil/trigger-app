package com.example.config

import com.example.BuildConfig

object AgoraConfig {
    /**
     * Agora App ID provisioned from the Agora Console.
     * Note: Per security mandate, the Primary Certificate is NEVER placed
     * in the Android application.
     */
    val AGORA_APP_ID: String
        get() = BackendConfig.AGORA_APP_ID.ifBlank {
            BuildConfig.AGORA_APP_ID
        }

    /**
     * Optional static fallback token for App ID-only testing projects.
     * In production, tokens are obtained securely via Supabase Edge Function.
     */
    val STATIC_FALLBACK_TOKEN: String
        get() = BackendConfig.AGORA_TOKEN.ifBlank {
            BuildConfig.AGORA_TOKEN
        }

    val isConfigured: Boolean
        get() = AGORA_APP_ID.isNotBlank() && !AGORA_APP_ID.contains("your-agora-app-id")

    enum class AgoraRole(val value: String) {
        PUBLISHER("publisher"),
        SUBSCRIBER("subscriber"),
        HOST("host"),
        CO_HOST("co_host"),
        AUDIENCE("audience")
    }

    /**
     * Normalizes a channel name into safe alphanumeric characters (1-64 chars)
     */
    fun sanitizeChannelName(raw: String): String {
        val sanitized = raw.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return sanitized.take(64).ifEmpty { "channel_${System.currentTimeMillis()}" }
    }
}
