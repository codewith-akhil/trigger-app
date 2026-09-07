package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

data class VaultMediaItem(
    val id: String,
    val name: String,
    val filePath: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val formattedDate: String,
    val thumbnailUri: String? = null
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

class SecretVaultService(private val context: Context) {
    private val TAG = "SecretVaultService"
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences = context.getSharedPreferences("trigger_secret_vault_prefs", Context.MODE_PRIVATE)

    private val _isPinSet = MutableStateFlow(false)
    val isPinSet: StateFlow<Boolean> = _isPinSet.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _vaultItems = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultMediaItem>> = _vaultItems.asStateFlow()

    private val vaultDir: File
        get() {
            val dir = File(context.filesDir, "trigger_secret_vault")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val client get() = AppServiceContainer.supabaseClient

    init {
        val savedPinHash = prefs.getString("vault_pin_hash", null)
        _isPinSet.value = !savedPinHash.isNullOrEmpty()
        loadVaultItems()
    }

    /**
     * Hash a 6-digit PIN with a device-specific salt using SHA-256.
     * The PIN is NEVER stored in plaintext — only the salted hash. This local
     * hash is used only for offline fallback (when the server is unreachable);
     * the canonical PIN lives in the `vault_pins` table.
     */
    private fun hashPin(pin: String): String {
        val salt = prefs.getString("vault_pin_salt", null) ?: run {
            val newSalt = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("vault_pin_salt", newSalt).apply()
            newSalt
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val input = "$pin:$salt".toByteArray(Charsets.UTF_8)
        return md.digest(input).joinToString("") { "%02x".format(it) }
    }

    /**
     * Upserts the vault PIN to the server via the `upsert-vault-pin` edge
     * function in a fire-and-forget coroutine. Also stores the salted hash
     * locally so offline unlock still works.
     *
     * NOTE: This function is non-suspend because the existing
     * `VaultPinLockScreen` callback is non-suspending. The synchronous return
     * reflects only local validation (6-digit format); the actual server-side
     * upsert result is delivered asynchronously via the [isPinSet] /
     * [isUnlocked] StateFlows.
     *
     * Returns false only on local validation failure.
     */
    fun setPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false

        // Optimistically persist locally for offline unlock — the canonical
        // PIN lives in the `vault_pins` table.
        prefs.edit().putString("vault_pin_hash", hashPin(pin)).apply()
        _isPinSet.value = true
        _isUnlocked.value = true

        scope.launch {
            val payload = JSONObject().put("pin", pin)
            when (val res = client.invokeFunction("upsert-vault-pin", payload)) {
                is SupabaseResult.Success -> Log.i(TAG, "Vault PIN upserted to server")
                is SupabaseResult.Error -> {
                    Log.e(TAG, "upsert-vault-pin failed: ${res.message}. Local PIN retained for offline use.")
                    // Local PIN remains set; user can still unlock offline.
                }
            }
        }
        return true
    }

    /**
     * Verifies the PIN against the server-side `vault_pins` row (which has
     * brute-force lockout: 5 attempts / 10 min) in a fire-and-forget
     * coroutine. On network failure, falls back to local hash comparison so
     * the user can still access their vault offline — WARNING: this bypasses
     * the server-side brute-force protection.
     *
     * NOTE: Non-suspend because the existing `VaultPinLockScreen` callback is
     * non-suspending. The synchronous return is true iff the local hash
     * matches (immediate UX feedback). The server-side lockout is enforced
     * independently and surfaces as an [isUnlocked] state change.
     */
    fun verifyPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false

        // Immediate local-hash check for fast UX feedback.
        val savedHash = prefs.getString("vault_pin_hash", null)
        val localMatches = savedHash != null && hashPin(pin) == savedHash

        scope.launch {
            val payload = JSONObject().put("pin", pin)
            when (val res = client.invokeFunction("verify-vault-pin", payload)) {
                is SupabaseResult.Success -> {
                    val verified = res.data.optBoolean("verified", false)
                    if (verified) {
                        _isUnlocked.value = true
                        loadVaultItems()
                    } else {
                        // Server said no — lock the vault even if local hash
                        // happened to match (e.g. PIN was rotated on another
                        // device).
                        _isUnlocked.value = false
                    }
                }
                is SupabaseResult.Error -> {
                    // FALLBACK: rely on the local-hash result we already
                    // computed. WARNING: this bypasses the server-side
                    // 5-attempt brute-force lockout — accept this trade-off
                    // for offline UX.
                    Log.w(TAG, "verify-vault-pin network error — falling back to local hash. Server brute-force protection bypassed.")
                    if (localMatches) {
                        _isUnlocked.value = true
                        loadVaultItems()
                    }
                }
            }
        }

        return localMatches
    }

    fun lock() {
        _isUnlocked.value = false
    }

    /**
     * Wipes all local vault state: the PIN hash + salt prefs, cached items and
     * the media files themselves. Called by AccountStateManager on logout /
     * account switch so the previous account's vault can never be opened by
     * the next one. The canonical PIN lives server-side in `vault_pins`, so a
     * returning user can simply set their PIN again.
     */
    fun resetLocalState() {
        prefs.edit().clear().apply()
        _isPinSet.value = false
        _isUnlocked.value = false
        _vaultItems.value = emptyList()
        try {
            vaultDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Vault dir wipe failed: ${e.message}")
        }
    }

    /**
     * OTP-based PIN reset flow backed by the `reset-vault-pin` edge function.
     *
     * Step 1: call with `otp = null` to send an OTP to [email].
     * Step 2: call with `otp` + `newPin` to verify the OTP and replace the
     *         server-side PIN.
     *
     * Returns true if step 1 succeeded (when otp == null) or if the PIN was
     * actually reset (when otp != null).
     */
    suspend fun resetVaultPin(email: String, otp: String? = null, newPin: String? = null): Boolean {
        val payload = JSONObject().put("email", email.trim())
        if (otp == null) {
            payload.put("action", "send_otp")
        } else {
            if (newPin == null || newPin.length != 6 || !newPin.all { it.isDigit() }) return false
            payload.put("action", "reset")
                .put("otp", otp.trim())
                .put("newPin", newPin)
        }

        return when (val res = client.invokeFunction("reset-vault-pin", payload)) {
            is SupabaseResult.Success -> {
                if (otp != null) {
                    // PIN was reset on the server — sync locally.
                    prefs.edit().putString("vault_pin_hash", hashPin(newPin!!)).apply()
                    _isPinSet.value = true
                    _isUnlocked.value = true
                }
                Log.i(TAG, "reset-vault-pin ${if (otp == null) "send_otp" else "reset"} succeeded for $email")
                true
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "reset-vault-pin failed: ${res.message}")
                false
            }
        }
    }

    /**
     * Best-effort check whether a PIN is set server-side. The
     * `verify-vault-pin` edge function returns a distinct error
     * ("no pin set") when the caller has no PIN row, vs "incorrect pin" when
     * a PIN exists but the supplied value is wrong. We probe with an empty
     * PIN and inspect the error.
     *
     * Returns true if a PIN is set, false if not, or null if we can't tell
     * (network failure).
     */
    suspend fun hasServerPin(): Boolean? {
        val payload = JSONObject().put("pin", "")
        return when (val res = client.invokeFunction("verify-vault-pin", payload)) {
            is SupabaseResult.Success -> res.data.optBoolean("verified", false)
            is SupabaseResult.Error -> {
                val msg = res.message.lowercase(Locale.getDefault())
                when {
                    msg.contains("no pin") || msg.contains("not set") -> false
                    msg.contains("incorrect") || msg.contains("too many") -> true
                    else -> null
                }
            }
        }
    }

    fun loadVaultItems() {
        scope.launch {
            val files = vaultDir.listFiles() ?: emptyArray()
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val list = files.mapNotNull { file ->
                val name = file.name
                val isVideo = name.endsWith(".mp4", ignoreCase = true) ||
                        name.endsWith(".mov", ignoreCase = true) ||
                        name.endsWith(".mkv", ignoreCase = true)
                VaultMediaItem(
                    id = file.nameWithoutExtension,
                    name = file.name,
                    filePath = file.absolutePath,
                    isVideo = isVideo,
                    sizeBytes = file.length(),
                    formattedDate = dateFormat.format(Date(file.lastModified()))
                )
            }.sortedByDescending { it.id }

            // TODO: encrypt vault media with key derived from server-verified unlock token.
            // Currently media files are stored as plaintext in context.filesDir/trigger_secret_vault.
            // Encryption is a larger task tracked separately.

            withContext(Dispatchers.Main) {
                _vaultItems.value = list
            }
        }
    }

    fun importMedia(uri: Uri, isVideo: Boolean, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                val extension = if (isVideo) ".mp4" else ".jpg"
                val id = "vault_${System.currentTimeMillis()}"
                val targetFile = File(vaultDir, "$id$extension")

                context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                loadVaultItems()
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import media to vault: ${e.message}")
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        }
    }

    fun deleteMedia(item: VaultMediaItem): Boolean {
        return try {
            val file = File(item.filePath)
            if (file.exists()) {
                file.delete()
            }
            _vaultItems.value = _vaultItems.value.filter { it.id != item.id }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete vault file: ${e.message}")
            false
        }
    }
}
