package com.example.config

import android.util.Log
import com.example.BuildConfig

object BackendConfig {
    /**
     * The ONLY message a user may ever see for a backend configuration
     * problem. Deliberately generic — no mention of Supabase, env files,
     * keys, or build steps. Technical diagnostics live in [technicalDiagnostic]
     * and must only ever be written to Logcat / crash reporting.
     */
    const val USER_SAFE_CONFIG_ERROR = "Connection error. Please try again later."
    // Supabase Credentials
    private const val DEFAULT_SUPABASE_URL = "https://uazkcainrajcgxecomly.supabase.co"
    private const val DEFAULT_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVhemtjYWlucmFqY2d4ZWNvbWx5Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg0NTkwMTksImV4cCI6MjEwNDAzNTAxOX0.laoOt2R3T4pdX3J_hp4_yABoeQNxrvlnt6lNDhnkh1o"
    private const val DEFAULT_AGORA_APP_ID = "b17004d7060b4ee0bf6e50cb931e1bbd"

    val SUPABASE_URL: String = run {
        val raw = try { BuildConfig.SUPABASE_URL } catch (_: Throwable) { "" }
        if (raw.isBlank() || raw.contains("placeholder") || raw.contains("your-project")) {
            DEFAULT_SUPABASE_URL
        } else {
            raw
        }
    }.trimEnd('/')

    val SUPABASE_ANON_KEY: String = run {
        val raw = try { BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "" }
        if (raw.isBlank() || raw.contains("placeholder") || raw.contains("your-supabase-anon-key") || raw.length < 50) {
            DEFAULT_SUPABASE_ANON_KEY
        } else {
            raw
        }
    }

    /**
     * True only when REAL Supabase credentials are baked into BuildConfig.
     * The .env.example file uses "placeholder" for keys the developer must
     * fill in before building. If the anon key is still "placeholder" (or any
     * of the other template sentinels), every Supabase Auth + REST call will
     * fail with "Invalid API key".
     */
    val isSupabaseConfigured: Boolean
        get() = SUPABASE_URL.isNotBlank() &&
                !SUPABASE_URL.contains("your-project") &&
                !SUPABASE_URL.contains("placeholder") &&
                SUPABASE_URL.startsWith("https://") &&
                SUPABASE_ANON_KEY.isNotBlank() &&
                !SUPABASE_ANON_KEY.contains("your-supabase-anon-key") &&
                !SUPABASE_ANON_KEY.equals("placeholder", ignoreCase = true) &&
                SUPABASE_ANON_KEY.length > 50  // real JWT anon keys are ~200+ chars

    /**
     * User-facing message when the backend is not configured. GUARANTEED to
     * contain zero internal details — safe to render in any screen/dialog.
     */
    val configurationError: String?
        get() = if (isSupabaseConfigured) null else USER_SAFE_CONFIG_ERROR

    /**
     * Internal-only diagnostic (build info, key shape). FOR LOGCAT / CRASH
     * REPORTING EYES ONLY — never return this from a ViewModel/screen/error
     * state that reaches the UI.
     */
    val technicalDiagnostic: String?
        get() = if (isSupabaseConfigured) null else
            run {
                Log.e(
                    "BackendConfig",
                    "Backend not configured: url=${SUPABASE_URL.take(24)}…, " +
                        "anonKeyLen=${SUPABASE_ANON_KEY.length}. " +
                        "Restore the repo .env before building (see secrets/repo-env.txt)."
                )
                "backend-not-configured"
            }

    // Agora WebRTC Credentials (https://console.agora.io/)
    val AGORA_APP_ID: String = run {
        val raw = try { BuildConfig.AGORA_APP_ID } catch (_: Throwable) { "" }
        if (raw.isBlank() || raw.contains("placeholder") || raw.contains("your-agora-app-id")) {
            DEFAULT_AGORA_APP_ID
        } else {
            raw
        }
    }
    val AGORA_TOKEN: String = BuildConfig.AGORA_TOKEN

    val isAgoraConfigured: Boolean
        get() = AGORA_APP_ID.isNotBlank() &&
                !AGORA_APP_ID.contains("your-agora-app-id") &&
                !AGORA_APP_ID.equals("placeholder", ignoreCase = true)
}

object GiphyConfig {
    /**
     * GIPHY API key (https://developers.giphy.com). Set GIPHY_API_KEY in the
     * .env file before building. The GIF button is hidden when no key is
     * configured — the picker never ships a fake/stub grid.
     */
    val API_KEY: String = run {
        val raw = try { BuildConfig.GIPHY_API_KEY } catch (_: Exception) { "" }
        raw.trim()
    }

    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() &&
                !API_KEY.contains("placeholder", ignoreCase = true) &&
                !API_KEY.contains("your-giphy", ignoreCase = true)
}
