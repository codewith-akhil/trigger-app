package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * A media item in the Secret Vault — either a LOCAL cache copy (offline import
 * that never reached the cloud) or a server row from the private `vault_media`
 * bucket, or both (local file + cloud row are joined via the media map).
 */
data class VaultMediaItem(
    val id: String,
    val name: String,
    val filePath: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val formattedDate: String,
    val thumbnailUri: String? = null,
    /** vault_media row id — null for local-cache-only items. */
    val mediaId: String? = null,
    /** Object path inside the private vault_media bucket ("{uid}/{uuid}.{ext}"). */
    val storagePath: String? = null,
    val mimeType: String? = null,
    /** Epoch millis — server created_at for cloud rows, file lastModified for local ones. */
    val createdAtMs: Long? = null
) {
    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> "${"%.1f".format(mb)} MB"
                kb >= 1.0 -> "${"%.0f".format(kb)} KB"
                else -> "$sizeBytes B"
            }
        }
}

/**
 * SecretVaultService — server-authoritative PIN vault + cloud media.
 *
 * PIN: lives ONLY in the `vault_pins` table (hashed server-side). Local
 * SharedPreferences hold no PIN material — just the media map that joins
 * local cache files to their vault_media rows. Lockout: 3 wrong PINs per
 * rolling 24h window; recovery via 6-digit email OTP (reset-vault-pin).
 *
 * Media: every import keeps a local cache copy AND uploads to the private
 * `vault_media` bucket + indexes a row in the `vault_media` table
 * (owner-only RLS — visible only to the owner). Display URLs are signed
 * on demand (24h) and cached in memory.
 */
class SecretVaultService(private val context: Context) {
    private val TAG = "SecretVaultService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences = context.getSharedPreferences("trigger_secret_vault_prefs", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- state

    /** null = unknown / probing (loading state); true = server has a PIN. */
    private val _serverPinExists = MutableStateFlow<Boolean?>(null)
    val serverPinExists: StateFlow<Boolean?> = _serverPinExists.asStateFlow()

    /** true iff serverPinExists == true (server-authoritative). */
    private val _isPinSet = MutableStateFlow(false)
    val isPinSet: StateFlow<Boolean> = _isPinSet.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    /** Strikes left in the current 24h window; null = unknown (generic error). */
    private val _attemptsLeft = MutableStateFlow<Int?>(null)
    val attemptsLeft: StateFlow<Int?> = _attemptsLeft.asStateFlow()

    /** ISO-8601 instant until which the PIN is locked; null = not locked. */
    private val _lockedUntil = MutableStateFlow<String?>(null)
    val lockedUntil: StateFlow<String?> = _lockedUntil.asStateFlow()

    /** Network/verification failure surfaced by [verifyPin] ("Can't verify right now"). */
    private val _verifyError = MutableStateFlow<String?>(null)
    val verifyError: StateFlow<String?> = _verifyError.asStateFlow()

    /** true when the server-state probe failed — the screen offers a retry. */
    private val _serverStateError = MutableStateFlow(false)
    val serverStateError: StateFlow<Boolean> = _serverStateError.asStateFlow()

    /** Inline error for the setup (setPin) flow. */
    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    private val _settingPin = MutableStateFlow(false)
    val settingPin: StateFlow<Boolean> = _settingPin.asStateFlow()

    /** Inline error for the OTP unlock flow. */
    private val _unlockError = MutableStateFlow<String?>(null)
    val unlockError: StateFlow<String?> = _unlockError.asStateFlow()

    private val _unlockBusy = MutableStateFlow(false)
    val unlockBusy: StateFlow<Boolean> = _unlockBusy.asStateFlow()

    /** Merged [server rows + local cache files] vault listing. */
    private val _vaultItems = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultMediaItem>> = _vaultItems.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    /** 0..1 determinate; -1 = indeterminate (uploadFile reports no byte progress). */
    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    /** Import/upload failures surfaced via the screen (toast). */
    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError.asStateFlow()

    private val _attemptsPerDay = 3
    private val lockoutWindowMs = 24 * 60 * 60 * 1000L

    private val vaultDir: File
        get() {
            val dir = File(context.filesDir, "trigger_secret_vault")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val client get() = AppServiceContainer.supabaseClient

    /** Own HTTP client for the authenticated REST DELETE (SupabaseClient exposes no deleteRecord). */
    private val restHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** In-memory signed-URL cache: storagePath → (url, expiresAtEpochMs). */
    private val signedUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    init {
        loadVaultItems()
    }

    // ================================================================ PIN

    /**
     * Probes the server for the caller's `vault_pins` row and drives
     * [serverPinExists] / [attemptsLeft] / [lockedUntil]. Called by the vault
     * screen on entry. Safe to call repeatedly.
     */
    suspend fun ensureServerState() {
        val me = client.currentSession?.user?.id ?: run {
            Log.w(TAG, "ensureServerState: no session — staying in unknown state")
            return
        }
        _serverStateError.value = false
        when (val res = client.getTable("vault_pins", "select=attempts,first_fail_at&user_id=eq.$me")) {
            is SupabaseResult.Success -> {
                val row = res.data.optJSONObject(0)
                if (row == null) {
                    _serverPinExists.value = false
                    _isPinSet.value = false
                    _attemptsLeft.value = null
                    _lockedUntil.value = null
                } else {
                    _serverPinExists.value = true
                    _isPinSet.value = true
                    applyAttemptsRow(row)
                }
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "ensureServerState failed: ${res.message}")
                _serverStateError.value = true
            }
        }
    }

    /**
     * Best-effort check whether a PIN is set server-side (owner-selectable
     * vault_pins row). Returns null when the probe fails or there is no
     * session. The screen uses [ensureServerState] instead.
     */
    suspend fun hasServerPin(): Boolean? {
        val me = client.currentSession?.user?.id ?: return null
        return when (val res = client.getTable("vault_pins", "select=attempts&user_id=eq.$me")) {
            is SupabaseResult.Success -> res.data.optJSONObject(0) != null
            is SupabaseResult.Error -> null
        }
    }

    /**
     * First-time PIN setup (server-confirmed). Calls `upsert-vault-pin` with
     * only {pin} — the edge function requires oldPin ONLY when a PIN already
     * exists; that case surfaces as an error telling the user to unlock
     * instead. Local prefs are marked only AFTER a 2xx from the server.
     *
     * Returns true iff the server accepted the PIN (serverPinExists flips to
     * true and the vault is unlocked for this session).
     */
    suspend fun setPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            _pinError.value = "PIN must be exactly 6 digits."
            return false
        }
        _pinError.value = null
        _settingPin.value = true
        try {
            when (val res = client.invokeFunction("upsert-vault-pin", JSONObject().put("pin", pin))) {
                is SupabaseResult.Success -> {
                    _serverPinExists.value = true
                    _isPinSet.value = true
                    _isUnlocked.value = true
                    _attemptsLeft.value = null
                    _lockedUntil.value = null
                    Log.i(TAG, "Vault PIN set server-side")
                    return true
                }
                is SupabaseResult.Error -> {
                    val msg = res.message.lowercase(Locale.getDefault())
                    if (msg.contains("current pin is required") || msg.contains("already")) {
                        // A PIN already exists server-side — no oldPin was sent.
                        _serverPinExists.value = true
                        _isPinSet.value = true
                        _pinError.value = "Vault already has a PIN — enter it to unlock."
                    } else {
                        _pinError.value = if (res.message.isBlank()) "Couldn't set PIN. Try again." else res.message
                    }
                    return false
                }
            }
        } finally {
            _settingPin.value = false
        }
    }

    /**
     * Verifies the PIN SERVER-AUTHORITATIVE via `verify-vault-pin`.
     * There is NO local fallback unlock — a network failure returns false and
     * surfaces [verifyError] ("Can't verify right now") so the UI can show it.
     *
     * On wrong PIN: refreshes attemptsLeft / lockedUntil from vault_pins.
     * On "locked" (429): sets [lockedUntil]. On success: unlocks the session.
     *
     * KEEP SIGNATURE: suspend fun verifyPin(pin: String): Boolean
     * (ChatScreen's PIN gate awaits this inside LaunchedEffect).
     */
    suspend fun verifyPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false
        _verifyError.value = null
        _verifying.value = true
        try {
            when (val res = client.invokeFunction("verify-vault-pin", JSONObject().put("pin", pin))) {
                is SupabaseResult.Success -> {
                    if (res.data.optBoolean("unlocked", false)) {
                        unlockInternal()
                        return true
                    }
                    // 200 but not unlocked — treat like a wrong PIN.
                    refreshLockState()
                    return false
                }
                is SupabaseResult.Error -> {
                    val msg = res.message.lowercase(Locale.getDefault())
                    when {
                        msg.contains("no vault pin") || msg.contains("no pin") -> {
                            _serverPinExists.value = false
                            _isPinSet.value = false
                        }
                        msg.contains("locked") || msg.contains("too many") -> refreshLockState()
                        msg.contains("incorrect") -> refreshLockState()
                        else -> {
                            Log.w(TAG, "verify-vault-pin network error: ${res.message}")
                            _verifyError.value = "Can't verify right now. Check your connection and try again."
                        }
                    }
                    return false
                }
            }
        } finally {
            _verifying.value = false
        }
    }

    fun lock() {
        _isUnlocked.value = false
    }

    /**
     * Sends the 6-digit unlock OTP to the registered email (reset-vault-pin
     * resolves the address from the JWT — the client never supplies it).
     * Returns true when the email went out; failures land in [unlockError].
     */
    suspend fun requestUnlockOtp(): Boolean {
        _unlockError.value = null
        _unlockBusy.value = true
        try {
            return when (val res = client.invokeFunction("reset-vault-pin", JSONObject().put("action", "send_otp"))) {
                is SupabaseResult.Success -> {
                    Log.i(TAG, "Vault unlock OTP sent")
                    true
                }
                is SupabaseResult.Error -> {
                    _unlockError.value = if (res.message.isBlank()) "Couldn't send the code. Try again." else res.message
                    false
                }
            }
        } finally {
            _unlockBusy.value = false
        }
    }

    /**
     * Verifies the OTP and replaces the server PIN, fully clearing the
     * 3-strikes/24h window (attempts + first_fail_at zeroed server-side).
     * On success the vault is unlocked for this session.
     */
    suspend fun unlockWithOtp(otp: String, newPin: String): Boolean {
        if (otp.length != 6 || !otp.all { it.isDigit() }) {
            _unlockError.value = "Enter the 6-digit code from your email."
            return false
        }
        if (newPin.length != 6 || !newPin.all { it.isDigit() }) {
            _unlockError.value = "New PIN must be exactly 6 digits."
            return false
        }
        _unlockError.value = null
        _unlockBusy.value = true
        try {
            val payload = JSONObject()
                .put("action", "reset")
                .put("otp", otp.trim())
                .put("newPin", newPin)
            return when (val res = client.invokeFunction("reset-vault-pin", payload)) {
                is SupabaseResult.Success -> {
                    if (!res.data.optBoolean("reset", true)) {
                        _unlockError.value = "Couldn't reset the PIN. Try again."
                        return false
                    }
                    _serverPinExists.value = true
                    _isPinSet.value = true
                    _isUnlocked.value = true
                    _attemptsLeft.value = null
                    _lockedUntil.value = null
                    Log.i(TAG, "Vault PIN reset via OTP — unlocked")
                    true
                }
                is SupabaseResult.Error -> {
                    _unlockError.value = if (res.message.isBlank()) "Couldn't reset the PIN. Try again." else res.message
                    false
                }
            }
        } finally {
            _unlockBusy.value = false
        }
    }

    /** Mirrors the current vault_pins row into attemptsLeft / lockedUntil. */
    private suspend fun refreshLockState() {
        val me = client.currentSession?.user?.id ?: return
        when (val res = client.getTable("vault_pins", "select=attempts,first_fail_at&user_id=eq.$me")) {
            is SupabaseResult.Success -> {
                val row = res.data.optJSONObject(0)
                if (row != null) {
                    _serverPinExists.value = true
                    _isPinSet.value = true
                    applyAttemptsRow(row)
                }
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "refreshLockState failed: ${res.message}")
                // Leave attempts/locked state as-is; the screen shows a
                // generic "Wrong PIN" until the next successful probe.
            }
        }
    }

    private fun applyAttemptsRow(row: JSONObject) {
        val attempts = row.optInt("attempts", 0)
        val firstFailIso = row.optString("first_fail_at", "").takeIf { it.isNotBlank() }
        if (attempts >= _attemptsPerDay) {
            val startMs = firstFailIso?.let { parseIsoMs(it) }
            val untilMs = startMs?.plus(lockoutWindowMs)
            if (untilMs != null && untilMs > System.currentTimeMillis()) {
                _attemptsLeft.value = 0
                _lockedUntil.value = formatIso(untilMs)
            } else {
                // Window elapsed between the server check and now.
                _attemptsLeft.value = _attemptsPerDay
                _lockedUntil.value = null
            }
        } else {
            _lockedUntil.value = null
            _attemptsLeft.value = if (attempts > 0) (_attemptsPerDay - attempts).coerceAtLeast(0) else null
        }
    }

    private fun unlockInternal() {
        _isUnlocked.value = true
        _attemptsLeft.value = null
        _lockedUntil.value = null
        _verifyError.value = null
        loadVaultItems()
    }

    /**
     * Wipes all local vault state: prefs (media map), cached items, signed-URL
     * cache and the media files themselves. Called by AccountStateManager on
     * logout / account switch so the previous account's vault can never be
     * opened by the next one. The canonical PIN lives server-side in
     * `vault_pins`; serverPinExists returns to "unknown" until re-probed.
     */
    fun resetLocalState() {
        prefs.edit().clear().apply()
        _serverPinExists.value = null
        _isPinSet.value = false
        _isUnlocked.value = false
        _verifying.value = false
        _attemptsLeft.value = null
        _lockedUntil.value = null
        _verifyError.value = null
        _serverStateError.value = false
        _pinError.value = null
        _settingPin.value = false
        _unlockError.value = null
        _unlockBusy.value = false
        _vaultItems.value = emptyList()
        _isUploading.value = false
        _uploadProgress.value = 0f
        _mediaError.value = null
        signedUrlCache.clear()
        try {
            vaultDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Vault dir wipe failed: ${e.message}")
        }
    }

    // ================================================================ MEDIA

    /**
     * Loads the merged vault listing: cloud rows (private vault_media table,
     * owner-scoped via the caller's JWT) joined with the local cache files via
     * the media map. Callable from any coroutine (ChatScreen compat).
     */
    fun loadVaultItems() {
        scope.launch {
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val me = client.currentSession?.user?.id

            var serverItems: List<VaultMediaItem> = emptyList()
            if (me != null) {
                when (val res = client.getTable(
                    "vault_media",
                    "select=*&user_id=eq.$me&order=created_at.desc"
                )) {
                    is SupabaseResult.Success -> {
                        serverItems = parseVaultRows(res.data, dateFormat)
                    }
                    is SupabaseResult.Error -> {
                        Log.w(TAG, "vault_media fetch failed: ${res.message}")
                        // serverItems stays empty; local cache still listed below.
                    }
                }
            }

            val map = mediaMap()
            val byMediaId = serverItems.associateBy { it.mediaId }
            val finalList = mutableListOf<VaultMediaItem>()
            val consumedMediaIds = mutableSetOf<String>()

            val files = vaultDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            for (file in files) {
                val entry = map[file.name]
                val target = entry?.optString("mediaId")?.takeIf { it.isNotBlank() }?.let { byMediaId[it] }
                if (target != null) {
                    // Cloud row + local cache copy — one item, local file wins for display.
                    finalList += target.copy(filePath = file.absolutePath)
                    consumedMediaIds += target.mediaId!!
                } else {
                    finalList += VaultMediaItem(
                        id = file.name,
                        name = file.name,
                        filePath = file.absolutePath,
                        isVideo = isVideoFile(file.name),
                        sizeBytes = file.length(),
                        formattedDate = dateFormat.format(Date(file.lastModified())),
                        createdAtMs = file.lastModified()
                    )
                }
            }
            // Cloud-only rows (imported from another device — no local cache).
            serverItems.forEach { item ->
                if (item.mediaId !in consumedMediaIds) finalList += item
            }
            finalList.sortByDescending { it.createdAtMs ?: 0L }

            _vaultItems.value = finalList
        }
    }

    private fun parseVaultRows(data: JSONArray, dateFormat: SimpleDateFormat): List<VaultMediaItem> {
        return (0 until data.length()).mapNotNull { i ->
            val row = data.optJSONObject(i) ?: return@mapNotNull null
            val mediaId = row.optString("id")
            val storagePath = row.optString("storage_path")
            if (mediaId.isBlank() || storagePath.isBlank()) return@mapNotNull null
            val createdAtMs = parseIsoMs(row.optString("created_at"))
            VaultMediaItem(
                id = mediaId,
                name = row.optString("file_name").takeIf { it.isNotBlank() }
                        ?: storagePath.substringAfterLast('/'),
                filePath = "",
                isVideo = row.optBoolean("is_video", false),
                sizeBytes = row.optLong("file_size", 0L),
                formattedDate = createdAtMs?.let { dateFormat.format(Date(it)) } ?: "",
                mediaId = mediaId,
                storagePath = storagePath,
                mimeType = row.optString("mime_type").takeIf { it.isNotBlank() },
                createdAtMs = createdAtMs
            )
        }
    }

    /**
     * Resolves a display URL for a vault item: null when a local cache file is
     * present (the screen uses the file directly), otherwise a freshly signed
     * 24h URL for the private vault_media object (cached in memory).
     */
    suspend fun displayUrlFor(item: VaultMediaItem): String? {
        if (item.filePath.isNotBlank() && File(item.filePath).exists()) return null
        val path = item.storagePath ?: return null
        signedUrlCache[path]?.let { (url, expiresAt) ->
            if (expiresAt > System.currentTimeMillis() + 5 * 60_000L) return url
        }
        val url = MediaUrlResolver.refreshSignedUrl(VAULT_BUCKET, path, expiresIn = 86_400)
            ?: return null
        signedUrlCache[path] = url to (System.currentTimeMillis() + 23 * 60 * 60 * 1000L)
        return url
    }

    /**
     * Imports media into the vault: keeps a local cache copy (offline) AND
     * uploads to the private vault_media bucket + indexes a vault_media row.
     * [onComplete](true) fires once the item is in the vault (cloud success,
     * or local-only when the cloud leg failed — [onError] carries the reason).
     */
    fun importMedia(
        uri: Uri,
        isVideo: Boolean,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        scope.launch {
            if (_isUploading.value) {
                onError("An upload is already in progress.")
                onComplete(false)
                return@launch
            }
            _isUploading.value = true
            _uploadProgress.value = -1f // indeterminate — uploadFile reports no byte progress
            try {
                val mimeType = context.contentResolver.getType(uri)
                        ?: (if (isVideo) "video/mp4" else "image/jpeg")
                val ext = extensionFor(mimeType, isVideo)
                val fileName = "vault_${System.currentTimeMillis()}.$ext"

                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    onError("Couldn't read the selected media.")
                    onComplete(false)
                    return@launch
                }
                if (bytes.size > VAULT_BUCKET_LIMIT_BYTES) {
                    onError("File exceeds the 100 MB vault limit.")
                    onComplete(false)
                    return@launch
                }
                // 1. Local cache copy first (offline resilience).
                File(vaultDir, fileName).writeBytes(bytes)

                val me = client.currentSession?.user?.id
                if (me == null) {
                    _mediaError.value = "Saved locally only — sign in to sync your vault."
                    loadVaultItems()
                    _uploadProgress.value = 1f
                    onComplete(true)
                    return@launch
                }

                // 2. Upload to the private vault_media bucket ({uid}/{uuid}.{ext}).
                val storagePath = "$me/${UUID.randomUUID()}.$ext"
                var uploaded = false
                when (val up = client.uploadFile(VAULT_BUCKET, storagePath, bytes, mimeType)) {
                    is SupabaseResult.Success -> uploaded = true
                    is SupabaseResult.Error -> {
                        Log.e(TAG, "vault_media upload failed: ${up.message}")
                        _mediaError.value = "Saved locally, but cloud upload failed — it will only be visible on this device."
                    }
                }

                // 3. Index the row (owner-only RLS — visible only to the owner).
                if (uploaded) {
                    val row = JSONObject()
                        .put("user_id", me)
                        .put("storage_path", storagePath)
                        .put("file_name", fileName)
                        .put("file_size", bytes.size.toLong())
                        .put("is_video", isVideo)
                        .put("mime_type", mimeType)
                    var mediaId: String? = null
                    when (val ins = client.insertRecord("vault_media", row)) {
                        is SupabaseResult.Success -> {
                            mediaId = ins.data.optString("id").takeIf { it.isNotBlank() }
                            if (mediaId == null) {
                                _mediaError.value = "Uploaded, but the vault index didn't confirm — it may not appear on other devices."
                            }
                        }
                        is SupabaseResult.Error -> {
                            Log.e(TAG, "vault_media insert failed: ${ins.message}")
                            _mediaError.value = "Uploaded, but indexing failed — it may not appear on other devices."
                            // Don't leave an orphan object behind.
                            client.removeFile(VAULT_BUCKET, storagePath)
                        }
                    }
                    if (mediaId != null) {
                        val map = mediaMap()
                        map[fileName] = JSONObject()
                            .put("mediaId", mediaId)
                            .put("storagePath", storagePath)
                        saveMediaMap(map)
                    }
                }

                loadVaultItems()
                _uploadProgress.value = 1f
                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "importMedia failed: ${e.message}")
                _mediaError.value = "Import failed: ${e.message ?: "unknown error"}"
                onComplete(false)
            } finally {
                _isUploading.value = false
                _uploadProgress.value = 0f
            }
        }
    }

    /**
     * Deletes a vault item everywhere: vault_media row (authenticated REST
     * DELETE), storage object, local cache file + media-map entry. Returns
     * false (and deletes nothing) when the row delete fails, so local and
     * cloud state never diverge.
     */
    suspend fun deleteMedia(item: VaultMediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            if (item.mediaId != null && !deleteVaultMediaRow(item.mediaId)) {
                return@withContext false
            }
            item.storagePath?.let { path ->
                val removed = client.removeFile(VAULT_BUCKET, path)
                if (!removed) Log.w(TAG, "vault_media object delete failed for $path (row already removed)")
            }
            val map = mediaMap()
            val doomedKeys = map.keys.filter { key ->
                map[key]?.optString("mediaId") == item.mediaId || key == item.name
            }
            doomedKeys.forEach { key ->
                map.remove(key)
                val cache = File(vaultDir, key)
                if (cache.exists()) cache.delete()
            }
            saveMediaMap(map)
            if (item.filePath.isNotBlank()) {
                val f = File(item.filePath)
                if (f.exists()) f.delete()
            }
            _vaultItems.value = _vaultItems.value.filterNot { it.id == item.id }
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteMedia failed: ${e.message}")
            false
        }
    }

    /**
     * Authenticated owner-scoped DELETE on vault_media (SupabaseClient has no
     * deleteRecord helper) — DELETE /rest/v1/vault_media?id=eq.{mediaId} with
     * apikey + Bearer, same pattern as getTable.
     */
    private suspend fun deleteVaultMediaRow(mediaId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = client.ensureFreshAccessToken() ?: return@withContext false
            val request = Request.Builder()
                .url("${com.example.config.BackendConfig.SUPABASE_URL}/rest/v1/vault_media?id=eq.$mediaId")
                .addHeader("apikey", com.example.config.BackendConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Prefer", "return=minimal")
                .delete()
                .build()
            restHttp.newCall(request).execute().use { response ->
                if (response.isSuccessful) true
                else {
                    Log.w(TAG, "vault_media row delete failed: HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "vault_media row delete error: ${e.message}")
            false
        }
    }

    fun clearMediaError() {
        _mediaError.value = null
    }

    // ---------------------------------------------------------- media map

    /** LocalFileName → {mediaId, storagePath} join cache (prefs JSON). */
    private fun mediaMap(): MutableMap<String, JSONObject> {
        val out = mutableMapOf<String, JSONObject>()
        val raw = prefs.getString(MEDIA_MAP_KEY, null) ?: return out
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optJSONObject(k) ?: JSONObject()
            }
        } catch (e: Exception) {
            Log.w(TAG, "media map parse failed: ${e.message}")
        }
        return out
    }

    private fun saveMediaMap(map: Map<String, JSONObject>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(MEDIA_MAP_KEY, obj.toString()).apply()
    }

    // ---------------------------------------------------------- helpers

    private fun isVideoFile(name: String): Boolean {
        val n = name.lowercase(Locale.getDefault())
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv")
    }

    private fun extensionFor(mimeType: String, isVideo: Boolean): String = when (mimeType.lowercase(Locale.getDefault())) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        else -> if (isVideo) "mp4" else "jpg"
    }

    /** Parses PostgREST/ISO-8601 timestamps ("...Z" or "...+00:00", with/without millis). */
    private fun parseIsoMs(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = false
                if (pattern == "yyyy-MM-dd'T'HH:mm:ss'Z'") {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = fmt.parse(iso) ?: continue
                return parsed.time
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    private fun formatIso(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    companion object {
        private const val MEDIA_MAP_KEY = "media_map"
        const val VAULT_BUCKET = "vault_media"
        const val VAULT_BUCKET_LIMIT_BYTES = 100L * 1024 * 1024 // bucket file-size limit
    }
}
