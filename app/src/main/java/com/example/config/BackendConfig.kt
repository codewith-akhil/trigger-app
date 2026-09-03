package com.example.config

import com.example.BuildConfig

object BackendConfig {
    // Supabase Credentials
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY

    val isSupabaseConfigured: Boolean
        get() = SUPABASE_URL.isNotBlank() && 
                !SUPABASE_URL.contains("your-project") && 
                SUPABASE_ANON_KEY.isNotBlank() && 
                !SUPABASE_ANON_KEY.contains("your-supabase-anon-key")

    // Agora WebRTC Credentials (https://console.agora.io/)
    val AGORA_APP_ID: String = BuildConfig.AGORA_APP_ID
    val AGORA_TOKEN: String = BuildConfig.AGORA_TOKEN

    val isAgoraConfigured: Boolean
        get() = AGORA_APP_ID.isNotBlank() && 
                !AGORA_APP_ID.contains("your-agora-app-id")
}
