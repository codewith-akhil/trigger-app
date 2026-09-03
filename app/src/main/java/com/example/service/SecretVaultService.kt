package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val scope = CoroutineScope(Dispatchers.IO)

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

    init {
        val savedPin = prefs.getString("vault_6digit_pin", null)
        _isPinSet.value = !savedPin.isNullOrEmpty()
        loadVaultItems()
    }

    fun setPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false
        prefs.edit().putString("vault_6digit_pin", pin).apply()
        _isPinSet.value = true
        _isUnlocked.value = true
        return true
    }

    fun verifyPin(pin: String): Boolean {
        val savedPin = prefs.getString("vault_6digit_pin", null)
        val matches = savedPin != null && savedPin == pin
        if (matches) {
            _isUnlocked.value = true
            loadVaultItems()
        }
        return matches
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun resetVaultPin(oldPin: String, newPin: String): Boolean {
        if (verifyPin(oldPin)) {
            return setPin(newPin)
        }
        return false
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
