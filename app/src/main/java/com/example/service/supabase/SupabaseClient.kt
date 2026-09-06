package com.example.service.supabase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.config.BackendConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
    val user: SupabaseUser,
    /** Epoch millis when accessToken expires. 0 = unknown (treated as stale). */
    val expiresAt: Long = 0L
)

sealed class SupabaseResult<out T> {
    data class Success<out T>(val data: T) : SupabaseResult<T>()
    data class Error(val message: String, val code: Int? = null) : SupabaseResult<Nothing>()
}

/**
 * Represents a Realtime event received from the Supabase WebSocket.
 * eventType: INSERT | UPDATE | DELETE
 * table: messages | conversations | user_presences | etc.
 * record: the row data (for INSERT/UPDATE) or old row (for DELETE)
 */
data class RealtimeEvent(
    val eventType: String,
    val table: String,
    val schema: String,
    val record: JSONObject?,
    val oldRecord: JSONObject?
)

class SupabaseClient(
    private val appContext: Context? = null,
    private val baseUrl: String = BackendConfig.SUPABASE_URL,
    private val anonKey: String = BackendConfig.SUPABASE_ANON_KEY
) {
    companion object {
        private const val TAG = "SupabaseClient"
        private const val SESSION_PREFS = "trigger_auth_session"
        private const val SESSION_KEY = "session_json"
        /** Refresh when the access token has less than this long to live. */
        private const val EXPIRY_MARGIN_MS = 120_000L
        private const val DEFAULT_TTL_MS = 3_600_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // keep WebSocket alive
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var currentSession: SupabaseSession? = null
        private set

    // ---- Session persistence + refresh ----
    // The session is persisted to MODE_PRIVATE prefs so the user stays logged in
    // across process death, and the access token is transparently refreshed via
    // the refresh_token grant before it expires. Without this, Supabase's 1-hour
    // token TTL made every authenticated call fail with 401 / storage-RLS
    // violations roughly an hour after login ("Unauthorized", "new row violates
    // row-level security policy", "Unable to verify availability").
    private val prefs: SharedPreferences? =
        appContext?.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes token refresh so concurrent calls refresh exactly once. */
    private val authMutex = Mutex()

    init {
        restorePersistedSession()
    }

    private fun restorePersistedSession() {
        val raw = prefs?.getString(SESSION_KEY, null) ?: return
        try {
            val json = JSONObject(raw)
            val userJson = json.optJSONObject("user") ?: return
            val session = SupabaseSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", ""),
                user = SupabaseUser(
                    id = userJson.optString("id"),
                    email = userJson.optString("email"),
                    fullName = if (userJson.isNull("full_name")) null else userJson.optString("full_name"),
                    avatarUrl = if (userJson.isNull("avatar_url")) null else userJson.optString("avatar_url")
                ),
                expiresAt = json.optLong("expires_at", 0L)
            )
            if (session.accessToken.isNotBlank() && session.user.id.isNotBlank()) {
                currentSession = session
                Log.i(TAG, "Restored persisted session for user ${session.user.id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore session: ${e.message}")
            prefs.edit().remove(SESSION_KEY).apply()
        }
    }

    private fun persistSession(session: SupabaseSession?) {
        val p = prefs ?: return
        if (session == null) {
            p.edit().remove(SESSION_KEY).apply()
            return
        }
        try {
            val userJson = JSONObject().apply {
                put("id", session.user.id)
                put("email", session.user.email)
                session.user.fullName?.let { put("full_name", it) }
                session.user.avatarUrl?.let { put("avatar_url", it) }
            }
            val json = JSONObject().apply {
                put("access_token", session.accessToken)
                put("refresh_token", session.refreshToken)
                put("expires_at", session.expiresAt)
                put("user", userJson)
            }
            p.edit().putString(SESSION_KEY, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist session: ${e.message}")
        }
    }

    private fun applySession(session: SupabaseSession?) {
        currentSession = session
        persistSession(session)
    }

    private fun buildSession(json: JSONObject, user: SupabaseUser): SupabaseSession {
        val expiresInSec = json.optString("expires_in", "3600").toLongOrNull() ?: 3600L
        return SupabaseSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token", ""),
            user = user,
            expiresAt = System.currentTimeMillis() + expiresInSec * 1000
        )
    }

    /**
     * Returns a valid access token for authenticated calls, refreshing it via
     * the refresh_token grant when it is missing or about to expire. Returns
     * null when there is no session (caller may fall back to the anon key).
     */
    suspend fun ensureFreshAccessToken(): String? = authMutex.withLock {
        val session = currentSession ?: return null

        val stillFresh = session.accessToken.isNotBlank() &&
            session.expiresAt > 0 &&
            session.expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS
        if (stillFresh) return session.accessToken

        if (session.refreshToken.isBlank()) {
            Log.w(TAG, "Token stale and no refresh token available")
            return session.accessToken.ifBlank { null }
        }

        try {
            val bodyJson = JSONObject().put("refresh_token", session.refreshToken)
            val request = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val userJson = json.optJSONObject("user")
                val user = if (userJson != null) parseUser(userJson, fallbackEmail = session.user.email) else session.user
                val fresh = buildSession(json, user).copy(refreshToken = json.optString("refresh_token", session.refreshToken))
                applySession(fresh)
                Log.i(TAG, "Access token refreshed")
                return fresh.accessToken
            }

            // 400/401 = refresh token revoked/invalid -> force re-login
            if (response.code == 400 || response.code == 401) {
                Log.w(TAG, "Refresh token rejected (${response.code}) — clearing session")
                applySession(null)
                return null
            }

            // Transient server error — keep the old token as best effort
            Log.w(TAG, "Token refresh failed (${response.code}) — using existing token")
            return session.accessToken.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh network error: ${e.message}")
            return session.accessToken.ifBlank { null }
        }
    }

    /** True when a session is present (used for auto-login on app start). */
    fun hasActiveSession(): Boolean = currentSession != null

    // ---- Realtime WebSocket ----
    private var realtimeSocket: WebSocket? = null
    private val _realtimeEvents = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val realtimeEvents: SharedFlow<RealtimeEvent> = _realtimeEvents.asSharedFlow()

    // Emitted whenever the Realtime WebSocket (re)connects after a disconnect.
    // MessageServiceImpl listens to this and pulls any messages missed while
    // the socket was down.
    private val _reconnectSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val reconnectSignals: SharedFlow<Unit> = _reconnectSignals.asSharedFlow()

    // Tracks the last connectRealtime args so auto-reconnect can re-subscribe.
    private var lastRealtimeTables: List<String> = emptyList()
    private var lastRealtimeFilter: String? = null
    private var hasConnectedBefore = false

    /**
     * Connects to the Supabase Realtime WebSocket and subscribes to the given
     * tables. Automatically uses the current session JWT for auth.
     *
     * Call this once when the user logs in. The WebSocket stays open until
     * disconnectRealtime() is called (e.g. on logout).
     *
     * @param tables list of "public.table_name" to subscribe to (e.g. ["public.messages"])
     * @param filter optional Postgres changes filter (e.g. "conversation_id=eq.abc")
     */
    fun connectRealtime(tables: List<String>, filter: String? = null) {
        if (!BackendConfig.isSupabaseConfigured) return

        // Remember the args so auto-reconnect can re-subscribe.
        lastRealtimeTables = tables
        lastRealtimeFilter = filter

        // Close any existing connection
        realtimeSocket?.close(1000, "Reconnecting")

        // Refresh the token first (async), then open the socket with a live JWT
        clientScope.launch {
            val token = ensureFreshAccessToken() ?: anonKey
            openRealtimeSocket(token, tables, filter)
        }
    }

    private fun openRealtimeSocket(token: String, tables: List<String>, filter: String?) {
        val wsUrl = "${baseUrl.replace("https", "wss")}/realtime/v1/websocket" +
            "?apikey=$anonKey&vsn=1.0.0"

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        realtimeSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Realtime WebSocket connected")
                // If this is a reconnect (not the first connection), emit a signal
                // so listeners can sync any messages they missed during the gap.
                if (hasConnectedBefore) {
                    _reconnectSignals.tryEmit(Unit)
                }
                hasConnectedBefore = true

                // Send join messages for each table
                tables.forEachIndexed { idx, tableRef ->
                    val parts = tableRef.split(".")
                    val schema = parts.getOrNull(0) ?: "public"
                    val table = parts.getOrNull(1) ?: tableRef
                    val joinMsg = JSONObject().apply {
                        put("topic", "realtime:public.$table")
                        put("event", "phx_join")
                        put("payload", JSONObject().apply {
                            put("config", JSONObject().apply {
                                put("broadcast", JSONObject().put("self", false))
                                put("presence", JSONObject().put("key", ""))
                            })
                            put("postgres_changes", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("event", "*")
                                    put("schema", schema)
                                    put("table", table)
                                    // Only apply the conversation_id filter to tables
                                    // that actually HAVE that column. Applying it to
                                    // `conversations` (PK is `id`) and `user_presences`
                                    // made Realtime reject those subscriptions, so
                                    // presence and conversation events never arrived.
                                    if (filter != null && table == "messages") put("filter", filter)
                                })
                            })
                        })
                        put("ref", idx.toString())
                    }
                    webSocket.send(joinMsg.toString())
                    Log.d(TAG, "Subscribed to $tableRef")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event", "")
                    val payload = json.optJSONObject("payload") ?: return

                    // Realtime change events come as "INSERT" / "UPDATE" / "DELETE"
                    if (event == "INSERT" || event == "UPDATE" || event == "DELETE") {
                        val data = payload.optJSONObject("data") ?: payload
                        val rtEvent = RealtimeEvent(
                            eventType = event,
                            table = data.optString("table", ""),
                            schema = data.optString("schema", "public"),
                            record = data.optJSONObject("record"),
                            oldRecord = data.optJSONObject("old_record")
                        )
                        _realtimeEvents.tryEmit(rtEvent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse realtime message: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary messages not used by Supabase Realtime
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.i(TAG, "Realtime WebSocket closing: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Realtime WebSocket failure: ${t.message}")
                // Auto-reconnect after 3 seconds
                Thread {
                    Thread.sleep(3000)
                    if (currentSession != null) {
                        Log.i(TAG, "Auto-reconnecting Realtime WebSocket...")
                        connectRealtime(lastRealtimeTables, lastRealtimeFilter)
                    }
                }.start()
            }
        })
    }

    /**
     * Disconnects the Realtime WebSocket. Call on logout.
     */
    fun disconnectRealtime() {
        realtimeSocket?.close(1000, "User logged out")
        realtimeSocket = null
        Log.i(TAG, "Realtime WebSocket disconnected")
    }

    val currentUser: SupabaseUser?
        get() = currentSession?.user

    // ==========================================
    // AUTHENTICATION (/auth/v1)
    // ==========================================

    suspend fun signUp(email: String, password: String, fullName: String): SupabaseResult<SupabaseUser> =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.isSupabaseConfigured) {
                return@withContext SupabaseResult.Error(
                    BackendConfig.configurationError ?: "Supabase is not configured"
                )
            }

            try {
                // Starting a fresh signup invalidates whatever session the app
                // was browsing with. Without this, the PREVIOUS account's
                // session stays alive during the new account's signup (the
                // email-confirmation path returns no session here), so the new
                // user's dashboard would render — and write! — with the old
                // account's JWT.
                if (currentSession != null) applySession(null)

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
                        applySession(buildSession(json, user))
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
                return@withContext SupabaseResult.Error(BackendConfig.configurationError ?: "Supabase is not configured")
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
                    val session = buildSession(json, user)
                    applySession(session)
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
                return@withContext SupabaseResult.Error(BackendConfig.configurationError ?: "Supabase is not configured")
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
                    val session = buildSession(json, user)
                    applySession(session)
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
                return@withContext SupabaseResult.Error(
                    BackendConfig.configurationError ?: "Supabase is not configured"
                )
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
                return@withContext SupabaseResult.Error(
                    BackendConfig.configurationError ?: "Supabase is not configured"
                )
            }

            try {
                val bodyJson = JSONObject().apply {
                    put("password", newPassword)
                }

                val token = ensureFreshAccessToken() ?: anonKey
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
                val token = ensureFreshAccessToken()
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
            applySession(null)
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
                val token = ensureFreshAccessToken() ?: anonKey
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
                val token = ensureFreshAccessToken() ?: anonKey
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
                val token = ensureFreshAccessToken() ?: anonKey
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
        mimeType: String = "application/octet-stream",
        upsert: Boolean = false
    ): SupabaseResult<String> = withContext(Dispatchers.IO) {
        if (!BackendConfig.isSupabaseConfigured) {
            return@withContext SupabaseResult.Error(BackendConfig.configurationError ?: "Supabase is not configured")
        }

        try {
            val token = ensureFreshAccessToken() ?: anonKey
            val mediaType = mimeType.toMediaType()
            val requestBody = fileBytes.toRequestBody(mediaType)

            val requestBuilder = Request.Builder()
                .url("$baseUrl/storage/v1/object/$bucketName/$fileName")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mimeType)

            if (upsert) {
                // x-upsert: true overwrites an existing object at the same path
                requestBuilder.addHeader("x-upsert", "true")
            }

            val request = requestBuilder.post(requestBody).build()

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
            return@withContext SupabaseResult.Error(BackendConfig.configurationError ?: "Supabase is not configured")
        }

        try {
            val token = ensureFreshAccessToken() ?: anonKey
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
