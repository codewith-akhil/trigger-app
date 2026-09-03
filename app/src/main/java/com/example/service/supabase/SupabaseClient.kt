package com.example.service.supabase

import com.example.config.BackendConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SupabaseUser(
    val id: String,
    val email: String,
    val fullName: String? = null,
    val avatarUrl: String? = null
)

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val user: SupabaseUser
)

sealed class SupabaseResult<out T> {
    data class Success<out T>(val data: T) : SupabaseResult<T>()
    data class Error(val message: String, val code: Int? = null) : SupabaseResult<Nothing>()
}

class SupabaseClient(
    private val baseUrl: String = BackendConfig.SUPABASE_URL,
    private val anonKey: String = BackendConfig.SUPABASE_ANON_KEY
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var currentSession: SupabaseSession? = null
        private set

    val currentUser: SupabaseUser?
        get() = currentSession?.user

    // ==========================================
    // AUTHENTICATION (/auth/v1)
    // ==========================================

    suspend fun signUp(email: String, password: String, fullName: String): SupabaseResult<SupabaseUser> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                // Mock fallback when keys are not configured yet
                val mockUser = SupabaseUser(id = "user_" + System.currentTimeMillis(), email = email, fullName = fullName)
                currentSession = SupabaseSession(accessToken = "mock_token", refreshToken = "mock_refresh", user = mockUser)
                return@withContext SupabaseResult.Success(mockUser)
            }

            try {
                val bodyJson = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("data", JSONObject().apply {
                        put("full_name", fullName)
                    })
                }

                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/signup")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val userJson = if (json.has("user")) json.getJSONObject("user") else json
                    val user = parseUser(userJson, fallbackEmail = email, fallbackName = fullName)
                    if (json.has("access_token")) {
                        currentSession = SupabaseSession(
                            accessToken = json.getString("access_token"),
                            refreshToken = json.optString("refresh_token", ""),
                            user = user
                        )
                    }
                    SupabaseResult.Success(user)
                } else {
                    val errorMsg = parseErrorMessage(responseBody, "Sign up failed (${response.code})")
                    SupabaseResult.Error(errorMsg, response.code)
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Network error during sign up")
            }
        }

    suspend fun signInWithPassword(email: String, password: String): SupabaseResult<SupabaseSession> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                val mockUser = SupabaseUser(id = "user_me", email = email, fullName = email.substringBefore("@"))
                val session = SupabaseSession(accessToken = "mock_session_token", refreshToken = "mock_refresh", user = mockUser)
                currentSession = session
                return@withContext SupabaseResult.Success(session)
            }

            try {
                val bodyJson = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }

                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/token?grant_type=password")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val userJson = json.getJSONObject("user")
                    val user = parseUser(userJson, fallbackEmail = email)
                    val session = SupabaseSession(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optString("refresh_token", ""),
                        user = user
                    )
                    currentSession = session
                    SupabaseResult.Success(session)
                } else {
                    val errorMsg = parseErrorMessage(responseBody, "Invalid email or password")
                    SupabaseResult.Error(errorMsg, response.code)
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Network error during sign in")
            }
        }

    suspend fun verifyOtp(email: String, token: String, type: String = "signup"): SupabaseResult<SupabaseSession> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                val mockUser = SupabaseUser(id = "verified_user", email = email)
                val session = SupabaseSession(accessToken = "mock_token", refreshToken = "mock_refresh", user = mockUser)
                currentSession = session
                return@withContext SupabaseResult.Success(session)
            }

            try {
                val bodyJson = JSONObject().apply {
                    put("type", type)
                    put("email", email)
                    put("token", token)
                }

                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/verify")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val userJson = if (json.has("user")) json.getJSONObject("user") else json
                    val user = parseUser(userJson, fallbackEmail = email)
                    val session = SupabaseSession(
                        accessToken = json.optString("access_token", ""),
                        refreshToken = json.optString("refresh_token", ""),
                        user = user
                    )
                    currentSession = session
                    SupabaseResult.Success(session)
                } else {
                    val errorMsg = parseErrorMessage(responseBody, "Invalid verification code")
                    SupabaseResult.Error(errorMsg, response.code)
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Verification failed")
            }
        }

    suspend fun recoverPassword(email: String): SupabaseResult<Boolean> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                return@withContext SupabaseResult.Success(true)
            }

            try {
                val bodyJson = JSONObject().apply {
                    put("email", email)
                }

                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/recover")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    SupabaseResult.Success(true)
                } else {
                    val responseBody = response.body?.string() ?: ""
                    SupabaseResult.Error(parseErrorMessage(responseBody, "Password reset request failed"))
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Network error during password recovery")
            }
        }

    suspend fun updatePassword(newPassword: String): SupabaseResult<Boolean> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                return@withContext SupabaseResult.Success(true)
            }

            try {
                val bodyJson = JSONObject().apply {
                    put("password", newPassword)
                }

                val token = currentSession?.accessToken ?: anonKey
                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/user")
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .put(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    SupabaseResult.Success(true)
                } else {
                    val responseBody = response.body?.string() ?: ""
                    SupabaseResult.Error(parseErrorMessage(responseBody, "Failed to update password"))
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Network error updating password")
            }
        }

    suspend fun signOut(): SupabaseResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val token = currentSession?.accessToken
                if (token != null && BackendConfig.isSupabaseConfigured) {
                    val request = Request.Builder()
                        .url("$baseUrl/auth/v1/logout")
                        .addHeader("apikey", anonKey)
                        .addHeader("Authorization", "Bearer $token")
                        .post("{}".toRequestBody(jsonMediaType))
                        .build()
                    httpClient.newCall(request).execute()
                }
            } catch (_: Exception) {}
            currentSession = null
            SupabaseResult.Success(true)
        }

    // ==========================================
    // DATABASE (PostgREST /rest/v1)
    // ==========================================

    suspend fun getTable(tableName: String, queryParams: String = "select=*"): SupabaseResult<JSONArray> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                return@withContext SupabaseResult.Success(JSONArray())
            }

            try {
                val token = currentSession?.accessToken ?: anonKey
                val url = "$baseUrl/rest/v1/$tableName?$queryParams"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonArray = if (responseBody.trim().startsWith("[")) {
                        JSONArray(responseBody)
                    } else {
                        JSONArray().put(JSONObject(responseBody))
                    }
                    SupabaseResult.Success(jsonArray)
                } else {
                    SupabaseResult.Error("Database query failed: ${response.code}", response.code)
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Database query error")
            }
        }

    suspend fun insertRecord(tableName: String, recordJson: JSONObject): SupabaseResult<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                return@withContext SupabaseResult.Success(recordJson)
            }

            try {
                val token = currentSession?.accessToken ?: anonKey
                val request = Request.Builder()
                    .url("$baseUrl/rest/v1/$tableName")
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .post(recordJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonResult = if (responseBody.trim().startsWith("[")) {
                        val arr = JSONArray(responseBody)
                        if (arr.length() > 0) arr.getJSONObject(0) else recordJson
                    } else if (responseBody.trim().startsWith("{")) {
                        JSONObject(responseBody)
                    } else {
                        recordJson
                    }
                    SupabaseResult.Success(jsonResult)
                } else {
                    SupabaseResult.Error(parseErrorMessage(responseBody, "Failed to insert into $tableName"))
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Insert record error")
            }
        }

    suspend fun upsertRecord(tableName: String, recordJson: JSONObject, onConflict: String = "id"): SupabaseResult<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                return@withContext SupabaseResult.Success(recordJson)
            }

            try {
                val token = currentSession?.accessToken ?: anonKey
                val request = Request.Builder()
                    .url("$baseUrl/rest/v1/$tableName?on_conflict=$onConflict")
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "resolution=merge-duplicates,return=representation")
                    .post(recordJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonResult = if (responseBody.trim().startsWith("[")) {
                        val arr = JSONArray(responseBody)
                        if (arr.length() > 0) arr.getJSONObject(0) else recordJson
                    } else if (responseBody.trim().startsWith("{")) {
                        JSONObject(responseBody)
                    } else {
                        recordJson
                    }
                    SupabaseResult.Success(jsonResult)
                } else {
                    SupabaseResult.Error(parseErrorMessage(responseBody, "Failed to upsert $tableName"))
                }
            } catch (e: Exception) {
                SupabaseResult.Error(e.message ?: "Upsert error")
            }
        }

    // ==========================================
    // STORAGE (/storage/v1)
    // ==========================================

    suspend fun uploadFile(
        bucketName: String,
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "application/octet-stream"
    ): SupabaseResult<String> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSupabaseConfigured) {
            // Return a simulated media URL
            return@withContext SupabaseResult.Success("https://trigger-app.mock/storage/$bucketName/$fileName")
        }

        try {
            val token = currentSession?.accessToken ?: anonKey
            val mediaType = mimeType.toMediaType()
            val requestBody = fileBytes.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl/storage/v1/object/$bucketName/$fileName")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mimeType)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val publicUrl = "$baseUrl/storage/v1/object/public/$bucketName/$fileName"
                SupabaseResult.Success(publicUrl)
            } else {
                SupabaseResult.Error(parseErrorMessage(responseBody, "Storage upload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            SupabaseResult.Error(e.message ?: "Storage upload error")
        }
    }

    // ==========================================
    // FUNCTIONS (/functions/v1)
    // ==========================================

    suspend fun invokeFunction(
        functionName: String,
        payload: JSONObject = JSONObject()
    ): SupabaseResult<JSONObject> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSupabaseConfigured) {
            return@withContext SupabaseResult.Success(JSONObject().put("status", "ok").put("mock", true))
        }

        try {
            val token = currentSession?.accessToken ?: anonKey
            val request = Request.Builder()
                .url("$baseUrl/functions/v1/$functionName")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (response.isSuccessful) {
                val json = if (responseBody.trim().startsWith("{")) JSONObject(responseBody) else JSONObject().put("data", responseBody)
                SupabaseResult.Success(json)
            } else {
                SupabaseResult.Error(parseErrorMessage(responseBody, "Function $functionName failed"))
            }
        } catch (e: Exception) {
            SupabaseResult.Error(e.message ?: "Function invocation error")
        }
    }

    private fun parseUser(userJson: JSONObject, fallbackEmail: String = "", fallbackName: String? = null): SupabaseUser {
        val id = userJson.optString("id", System.currentTimeMillis().toString())
        val email = userJson.optString("email", fallbackEmail)
        val userMetadata = userJson.optJSONObject("user_metadata")
        val fullName = userMetadata?.optString("full_name") ?: fallbackName ?: email.substringBefore("@")
        val avatarUrl = userMetadata?.optString("avatar_url")
        return SupabaseUser(id = id, email = email, fullName = fullName, avatarUrl = avatarUrl)
    }

    private fun parseErrorMessage(responseBody: String, fallback: String): String {
        return try {
            val json = JSONObject(responseBody)
            when {
                json.has("error_description") -> json.getString("error_description")
                json.has("message") -> json.getString("message")
                json.has("msg") -> json.getString("msg")
                json.has("error") -> json.getString("error")
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
