package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DbKeyManager — owns the SQLCipher passphrase for the encrypted message
 * store ("trigger_msgstore.db").
 *
 * ## Key layout
 * - The DATABASE passphrase is 32 random bytes from [SecureRandom], generated
 *   once on first use and used directly as SQLCipher key material (raw key,
 *   no KDF — matches what [net.zetetic.database.sqlcipher.SupportOpenHelperFactory]
 *   feeds to the engine when Room opens the store).
 * - The passphrase itself is never stored: it is wrapped with an AES-256-GCM
 *   key that lives in the hardware-backed AndroidKeyStore under the
 *   "trigger_db_master_key" alias, and only the base64(IV + ciphertext) blob
 *   is persisted in the app-private "trigger_secure_prefs" prefs
 *   ([Context.MODE_PRIVATE]).
 * - Later launches decrypt the blob with the Keystore key.
 *
 * ## Cloner safety
 * AndroidKeyStore entries and MODE_PRIVATE prefs are scoped to the app UID,
 * and both are derived from the running process's real applicationId at
 * runtime (nothing is hardcoded). A cloned build therefore gets a DIFFERENT
 * master key and a DIFFERENT prefs file — it can never decrypt another
 * clone's database, even though the passphrase blobs are structurally
 * identical.
 *
 * ## Fail fast
 * Any Keystore or prefs failure (master key missing, blob corrupt, unwrap
 * rejected) throws immediately with a clear exception. We deliberately do NOT
 * fall back to a freshly generated key: the on-disk store is already
 * encrypted with the OLD key, so a silent re-key would trade a loud,
 * diagnosable failure for quiet, permanent data loss. Key material is never
 * logged.
 *
 * Threading: callers invoke this on a background dispatcher (the startup
 * pipeline runs on Dispatchers.IO); Keystore crypto must never run on main.
 */
class DbKeyManager(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Unwraps the persisted passphrase, creating + wrapping a new one on first use. */
    fun getOrCreatePassphrase(): ByteArray {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val blob = prefs.getString(KEY_WRAPPED_PASSPHRASE, null) ?: return createAndStore(prefs)
            return unwrap(blob)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Database master key unavailable — refusing to fall back to a new key " +
                    "because the existing store would become unrecoverable",
                e
            )
        }
    }

    /** First-use path: fresh 32-byte passphrase, wrapped by the Keystore key and persisted. */
    private fun createAndStore(prefs: android.content.SharedPreferences): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val ciphertext = cipher.doFinal(passphrase)
        val iv = cipher.iv
        val blob = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)

        // commit() (not apply()): the wrapped key must be durable before any
        // database file is created with the passphrase it protects.
        val persisted = prefs.edit().putString(KEY_WRAPPED_PASSPHRASE, blob).commit()
        if (!persisted) {
            throw IllegalStateException("Could not persist the wrapped database key")
        }
        return passphrase
    }

    /** Subsequent launches: decrypt base64(IV + ciphertext) with the Keystore key. */
    private fun unwrap(blob: String): ByteArray {
        val masterKey = getOrCreateMasterKey(allowCreate = false)
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateMasterKey(allowCreate: Boolean = true): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!allowCreate) {
            throw IllegalStateException(
                "AndroidKeyStore master key '$KEY_ALIAS' is missing while a wrapped " +
                    "database key exists — the store cannot be decrypted"
            )
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(MASTER_KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "trigger_secure_prefs"
        const val KEY_WRAPPED_PASSPHRASE = "trigger_db_master_key_blob"

        /** AndroidKeyStore alias for the AES-256-GCM wrapping key. */
        const val KEY_ALIAS = "trigger_db_master_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val PASSPHRASE_BYTES = 32
        const val MASTER_KEY_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
