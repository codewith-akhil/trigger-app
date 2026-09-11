package com.example.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

/**
 * TriggerDbMigrator — one-time, zero-data-loss move of the chat database from
 * the legacy plaintext file ("whatsapp_chat_db", the pre-Phase-2 Room store)
 * to its renamed, SQLCipher-encrypted home ("trigger_msgstore.db").
 *
 * The Room schema is untouched (still version 11, same entities, same
 * handwritten migration chain): this is a FILE-level rename + at-rest
 * encryption, not a schema migration.
 *
 * ## Why the recipe runs on the SQLCipher engine
 * The conversion is the standard `sqlcipher_export()` recipe: open the legacy
 * file, ATTACH the destination WITH a key, `SELECT sqlcipher_export('enc')`,
 * carry `user_version` over, DETACH. `ATTACH ... KEY` and `sqlcipher_export()`
 * exist ONLY in SQLCipher's build of SQLite — the platform's framework SQLite
 * has neither — so the plaintext store is opened through
 * [net.zetetic.database.sqlcipher.SQLiteDatabase], passing an EMPTY byte[]
 * passphrase, which is SQLCipher's documented plaintext-access mode. The
 * passphrase bytes are consumed as RAW key material by SQLCipher's byte[]
 * APIs (no PBKDF2) — the exact bytes Room's
 * [net.zetetic.database.sqlcipher.SupportOpenHelperFactory] will present when
 * opening the store — and are handed to ATTACH as x'<hex>' so both sides see
 * the identical key.
 *
 * ## Decision tree (idempotent, crash-resumable)
 * 1. Legacy AND encrypted target exist → a previous run already converted;
 *    the legacy trio is a stale leftover → delete it, keep encrypted (re-run
 *    protection: the encrypted store is never touched).
 * 2. Only legacy exists → rename the legacy trio (db/-wal/-shm) to the target
 *    names, convert in place into "trigger_msgstore.db.enc-tmp", VERIFY the
 *    temp opens with the exact passphrase Room will use, then swap (delete
 *    the plain trio, promote the temp). ANY failure → best-effort restore of
 *    the legacy trio and rethrow — never silently lose data.
 * 3. Plaintext data sitting at the TARGET path (crash between the rename and
 *    the swap) → same in-place conversion, no rename needed.
 * 4. Finished ".enc-tmp" with no target (crash between the plain delete and
 *    the promote) → verify, then promote it to the target.
 * 5. Only the encrypted target exists → no-op. Neither exists → fresh
 *    install; Room creates the encrypted store on first open.
 *
 * ## Safety notes
 * - The verification open happens BEFORE any plaintext file is deleted, so a
 *   key/recipe mismatch can never destroy data: the legacy trio is restored
 *   and the failure propagates (fail fast with a clear exception instead of
 *   a silently re-created empty store).
 * - The source's `user_version` (whatever schema version the install is on,
 *   ≤ 11) is preserved so Room's normal migration chain still runs on the
 *   encrypted copy.
 * - Key material is never logged — only file names and outcomes.
 * - Every path is derived from [Context] (cloner-safe: a cloned build
 *   migrates its own private databases directory).
 *
 * Threading: must be called BEFORE any Room open of the encrypted store (the
 * AppServiceContainer DI gate enforces that ordering); runs on
 * [Dispatchers.IO] — never on main.
 */
object TriggerDbMigrator {

    private const val TAG = "TriggerDbMigrator"

    /**
     * Legacy plaintext store name. The ONLY place in the codebase where this
     * name appears — it exists purely to detect + migrate the old file.
     */
    private const val LEGACY_DB_NAME = "whatsapp_chat_db"

    /** Encrypted store name Room opens after the migration. */
    private const val TARGET_DB_NAME = "trigger_msgstore.db"

    /** Temporary name for the encrypted export before the atomic-ish swap. */
    private const val TMP_SUFFIX = ".enc-tmp"

    private val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Volatile
    private var nativeLoaded = false

    /**
     * Loads the SQLCipher native library exactly once per process, idempotent
     * and thread-safe. The migrator and Room's open path (via
     * SupportOpenHelperFactory) share this single library instance.
     */
    fun ensureSqlCipherLoaded() {
        if (nativeLoaded) return
        synchronized(this) {
            if (nativeLoaded) return
            System.loadLibrary("sqlcipher")
            nativeLoaded = true
        }
    }

    /**
     * Ensures the on-disk chat store is the encrypted "trigger_msgstore.db",
     * migrating the legacy plaintext file if one exists.
     *
     * @param passphrase 32-byte SQLCipher key (raw key material) from
     * [DbKeyManager]; never logged.
     */
    suspend fun ensureMigrated(context: Context, passphrase: ByteArray) = withContext(Dispatchers.IO) {
        ensureSqlCipherLoaded()

        val dir = checkNotNull(context.getDatabasePath(LEGACY_DB_NAME).parentFile) {
            "Could not resolve the databases directory"
        }
        val legacy = trioOf(File(dir, LEGACY_DB_NAME))
        val target = trioOf(File(dir, TARGET_DB_NAME))
        val tmp = trioOf(File(dir, "$TARGET_DB_NAME$TMP_SUFFIX"))

        when {
            legacy.main.exists() && target.main.exists() && !isPlaintext(target.main) -> {
                // Case 1 — already migrated on a previous run (re-run
                // protection): the encrypted store wins, plaintext leftovers
                // and any stale temp are removed.
                deleteQuietly(tmp)
                deleteQuietly(legacy)
                Log.i(TAG, "Legacy plaintext chat DB removed; encrypted store already in place")
            }

            target.main.exists() && isPlaintext(target.main) -> {
                // Case 2 — crash resume: the rename already happened but the
                // conversion never finished, so plaintext sits at the target
                // path. Convert in place. (If a stale legacy file still
                // exists alongside, it is left untouched and cleaned up as a
                // case-1 re-run once the encrypted store is in place.)
                deleteQuietly(tmp)
                convertInPlace(source = target, tmp = tmp, passphrase = passphrase)
                promoteTmp(target, tmp)
                Log.i(TAG, "Interrupted conversion resumed: chat DB encrypted at rest")
            }

            legacy.main.exists() -> {
                // Case 3 — pre-encryption install: rename → convert → swap.
                renameTrio(legacy, target)
                try {
                    convertInPlace(source = target, tmp = tmp, passphrase = passphrase)
                    promoteTmp(target, tmp)
                    Log.i(TAG, "Chat DB renamed + encrypted at rest ($TARGET_DB_NAME)")
                } catch (t: Throwable) {
                    // Best-effort restore so nothing is lost; the caller sees
                    // the real failure — no fresh-DB fallback, no silent loss.
                    runCatching { renameTrio(target, legacy, overwrite = true) }
                        .onFailure {
                            Log.e(TAG, "Could not restore legacy DB after failed migration", it)
                        }
                    throw t
                }
            }

            target.main.exists() -> {
                // Case 4 — encrypted store already in place: nothing to do.
                // A leftover temp from an interrupted retry is stale — the
                // live store is authoritative.
                deleteQuietly(tmp)
            }

            tmp.main.exists() -> {
                // Case 5 — crash resume: the verified temp exists but the
                // final swap never ran. The temp is the only remaining copy
                // (the plaintext is already gone), so verify before promoting;
                // on verification failure rethrow WITHOUT deleting anything so
                // the file stays recoverable.
                verifyEncrypted(tmp.main, passphrase)
                promoteTmp(target, tmp)
                Log.i(TAG, "Promoted finished encrypted temp to $TARGET_DB_NAME")
            }

            else -> {
                // Case 6 — fresh install: nothing to migrate. Room creates
                // the encrypted DB on first open through the gated factory.
                Log.i(TAG, "No legacy chat DB found — nothing to migrate")
            }
        }
    }

    // ------------------------------------------------------------------
    // Conversion recipe (runs on the SQLCipher engine — see class KDoc)
    // ------------------------------------------------------------------

    /**
     * Exports the plaintext [source] (already sitting at the target path)
     * into an encrypted [tmp] file, preserving Room's `user_version`.
     */
    private fun convertInPlace(source: DbFiles, tmp: DbFiles, passphrase: ByteArray) {
        // sqlcipher_export() requires a non-existent destination.
        deleteQuietly(tmp)

        // EMPTY byte[] passphrase = SQLCipher's documented plaintext access.
        val db = SQLiteDatabase.openDatabase(
            source.main.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        val sourceVersion: Int
        try {
            // Flush any WAL frames into the main file so the export sees
            // every committed transaction.
            db.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            sourceVersion = db.version

            val hex = passphrase.toHexString()
            db.rawExecSQL(
                "ATTACH DATABASE '${tmp.main.absolutePath}' AS enc KEY \"x'$hex'\""
            )
            try {
                db.rawExecSQL("SELECT sqlcipher_export('enc')")
                // Preserve Room's schema version — Room then runs its own
                // migration chain (legacy version → 11) on the encrypted copy.
                db.rawExecSQL("PRAGMA enc.user_version = $sourceVersion")
            } finally {
                // Never let a failed DETACH mask the real export failure.
                runCatching { db.rawExecSQL("DETACH DATABASE enc") }
                    .onFailure { Log.w(TAG, "DETACH of the export target failed", it) }
            }
        } finally {
            db.close()
        }

        // Fail fast BEFORE the plaintext is gone anywhere: prove the temp
        // decrypts with the exact bytes Room's open path will use.
        verifyEncrypted(tmp.main, passphrase)
    }

    /**
     * Re-opens [file] through SQLCipher with the real passphrase and touches
     * a header page (user_version) — a wrong key/recipe throws here.
     */
    private fun verifyEncrypted(file: File, passphrase: ByteArray) {
        val probe = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        try {
            probe.version
        } finally {
            probe.close()
        }
    }

    /**
     * Final swap: rename the verified temp over the plaintext trio. After the
     * main-file rename succeeds the encrypted store is authoritative — a
     * crash at any later point resumes into the "already encrypted" case.
     */
    private fun promoteTmp(target: DbFiles, tmp: DbFiles) {
        if (!tmp.main.renameTo(target.main)) {
            throw IllegalStateException(
                "Could not promote ${tmp.main.name} to ${target.main.name}"
            )
        }
        // The plaintext journal files are junk now that the encrypted main is
        // in place; then carry over the temp's own journals (normally none:
        // the export connection was closed cleanly, which checkpoints and
        // removes them).
        target.wal.delete()
        target.shm.delete()
        if (tmp.wal.exists()) tmp.wal.renameTo(target.wal)
        if (tmp.shm.exists()) tmp.shm.renameTo(target.shm)
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    /** The three physical files of one database (main + WAL + SHM). */
    private class DbFiles(val main: File) {
        val wal = File(main.path + "-wal")
        val shm = File(main.path + "-shm")
        val files: List<File> get() = listOf(main, wal, shm)
    }

    private fun trioOf(main: File) = DbFiles(main)

    private fun renameTrio(from: DbFiles, to: DbFiles, overwrite: Boolean = false) {
        from.files.forEachIndexed { index, file ->
            if (!file.exists()) return@forEachIndexed
            val destination = to.files[index]
            if (overwrite && destination.exists() && !destination.delete()) {
                throw IllegalStateException("Could not clear ${destination.name} for restore")
            }
            if (!file.renameTo(destination)) {
                throw IllegalStateException("Could not rename ${file.name} to ${destination.name}")
            }
        }
    }

    private fun deleteQuietly(trio: DbFiles) {
        trio.files.forEach { it.delete() }
    }

    /** True when the file carries the unencrypted SQLite magic header. */
    private fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER.size.toLong()) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(SQLITE_HEADER.size)
                raf.readFully(header)
                header.contentEquals(SQLITE_HEADER)
            }
        }.getOrDefault(false)
    }

    /** Hex-encodes key bytes for SQLCipher's x'…' raw-key syntax. Never logged. */
    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}
