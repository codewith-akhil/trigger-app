package com.example.service

import com.example.model.UserRepository
import com.example.service.supabase.SupabaseClient
import com.example.service.supabase.SupabaseResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * ProfileService
 * ----------------------------------------------------------------------------
 * Centralizes server-side profile operations:
 *   - refreshFromServer(): fetches the caller's profile row (via the
 *     get-my-profile edge function) and hydrates UserRepository so the UI
 *     shows correct name / email / avatar / gender / country / dob after
 *     login, OTP verification, or app launch.
 *
 * This avoids circular deps (model → di) by taking a SupabaseClient param.
 */
object ProfileService {

    private const val TAG = "ProfileService"

    /**
     * Fetches the caller's profile from the server and updates UserRepository.
     * Returns true on success, false on failure (network / auth / not found).
     *
     * Call this:
     *   - After successful login (EmailAuthScreen)
     *   - After successful OTP verification (EmailOtpVerificationScreen)
     *   - On ProfileScreen first launch if profile.name is empty
     *   - On app launch if a session exists
     */
    suspend fun refreshFromServer(client: SupabaseClient): Boolean {
        val result = client.invokeFunction("get-my-profile")
        return when (result) {
            is SupabaseResult.Success -> {
                val p: JSONObject = result.data.optJSONObject("profile") ?: return false
                val linksJson = try {
                    val linksArr = p.opt("links")
                    when (linksArr) {
                        is JSONArray -> linksArr.toString()
                        else -> "[]"
                    }
                } catch (_: Exception) { "[]" }

                UserRepository.updateAll(
                    id = p.optString("id", ""),
                    name = p.optString("full_name", ""),
                    email = p.optString("email", ""),
                    username = p.optString("username", ""),
                    about = p.optString("about", ""),
                    avatarUri = p.optString("avatar_url", "").ifEmpty { null },
                    gender = p.optString("gender", "").ifEmpty { null },
                    dob = p.optString("dob", "").ifEmpty { null },
                    countryCode = p.optString("country_code", "").ifEmpty { null },
                    countryName = p.optString("country_name", "").ifEmpty { null },
                    links = linksJson
                )
                true
            }
            is SupabaseResult.Error -> {
                android.util.Log.w(TAG, "refreshFromServer failed: ${result.message}")
                false
            }
        }
    }
}
