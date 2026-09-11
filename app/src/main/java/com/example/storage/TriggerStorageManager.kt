package com.example.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for EVERY on-device storage path the app uses.
 *
 * ## Cloner safety (the core guarantee)
 *
 * Every path produced by this class is derived exclusively from [Context] —
 * specifically [Context.getExternalFilesDir] and [Context.getFilesDir] — so the
 * real package name of the running process is resolved by the OS at runtime.
 * The package id "com.trigger.app" is NEVER hardcoded anywhere here, and no
 * absolute path is ever stored. A cloned build (different applicationId, e.g.
 * a parallel-space / dual-app wrapper) therefore automatically gets its own
 * private tree under its own Android/data entry: no cross-clone path
 * collisions, no permission errors, no data leaking between clones. Any future
 * code that needs a storage location MUST ask this manager instead of building
 * paths by hand.
 *
 * ## Layout
 *
 * The external root is `getExternalFilesDir(null)/"Trigger"` (i.e.
 * `Android/data/<actualPackageId>/files/Trigger`), an app-private external
 * directory: visible to the user via file managers on most devices, yet NOT
 * readable by other apps on API 30+, and NOT scanned by the media gallery.
 * Because of the latter, **no `.nomedia` marker is created anywhere** — it
 * would be redundant clutter in a directory that is already invisible to
 * MediaStore.
 *
 * If external storage is unavailable (rare: locked-down devices, work
 * profiles, transient unmount), the manager falls back to the internal
 * `filesDir/"Trigger"` and raises the [usingInternalFallback] flag once, with
 * a warning log. Callers can consult that flag to degrade gracefully (e.g.
 * refuse to take on multi-GB backups).
 *
 * The tree (created idempotently by [ensureTree]) — 14 enum entries modeling
 * 19 physical directories (the five media bases each carry a `Sent/` leaf):
 *
 * ```
 * Trigger/
 *   Media/                        (MEDIA — plain root, no files of its own)
 *     Trigger Images/             (IMAGES)        + Sent/
 *     Trigger Video/              (VIDEO)         + Sent/
 *     Trigger Audio/              (AUDIO)         + Sent/
 *     Trigger Documents/          (DOCUMENTS)     + Sent/
 *     Trigger Voice Notes/        (VOICE_NOTES)   + Sent/
 *   Databases/                    (DATABASES)
 *   Backups/                      (BACKUPS)
 *   accounts/                     (ACCOUNTS)
 *   .Shared/                      (SHARED)
 *   .trash/                       (TRASH)
 *   .Thumbs/                      (THUMBS)
 *   Profile Photos/               (PROFILE_PHOTOS)
 *   Streams/                      (STREAMS)
 * ```
 *
 * `Databases/` is reserved for **encrypted database snapshots only** — never
 * the live plaintext Room database, never keys. Anything landing there later
 * (scheduled backup/export phase) must already be ciphertext at rest, because
 * this directory is the one users are most likely to exfiltrate for manual
 * inspection.
 *
 * Threading: all methods are safe to call from any thread. Path derivation is
 * pure; [ensureTree] never throws (per-directory failures are logged and
 * skipped so one locked directory cannot take down the whole tree walk).
 */
class TriggerStorageManager(context: Context) {

    /** Application context only — never pins an Activity (leak pattern). */
    private val appContext: Context = context.applicationContext

    private val secureRandom = SecureRandom()

    /** Resolved once, lazily, on first path access. */
    private class RootResolution(val dir: File, val isInternalFallback: Boolean)

    private val rootResolution: RootResolution by lazy {
        val external = try {
            appContext.getExternalFilesDir(null)
        } catch (t: Throwable) {
            Log.w(TAG, "getExternalFilesDir(null) threw — treating as unavailable", t)
            null
        }
        if (external != null) {
            RootResolution(dir = File(external, ROOT_DIR_NAME), isInternalFallback = false)
        } else {
            Log.w(
                TAG,
                "External files dir unavailable — falling back to INTERNAL storage " +
                    "(${appContext.filesDir}/$ROOT_DIR_NAME). Media/backups may fill the " +
                    "internal quota and will not survive uninstall."
            )
            RootResolution(dir = File(appContext.filesDir, ROOT_DIR_NAME), isInternalFallback = true)
        }
    }

    /** Storage root, e.g. `.../Android/data/<pkg>/files/Trigger`. Lazily resolved. */
    val root: File
        get() = rootResolution.dir

    /** True when [root] had to fall back to internal storage (external unavailable). */
    val usingInternalFallback: Boolean
        get() = rootResolution.isInternalFallback

    /**
     * Creates the root and exactly the 19 physical directories modeled by
     * [TriggerFolder]. Idempotent (safe to call repeatedly); a failure on any
     * single directory is logged and skipped — this method NEVER throws, so
     * the bootstrap caller can fire it on a background dispatcher without a
     * try/catch.
     */
    fun ensureTree() {
        try {
            mkdirsOrWarn(root, label = "root")
            for (dir in TriggerFolder.physicalDirs(root)) {
                mkdirsOrWarn(dir, label = dir.absolutePath)
            }
            if (usingInternalFallback) {
                Log.w(TAG, "ensureTree running against INTERNAL fallback root: ${root.absolutePath}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensureTree failed unexpectedly — storage tree may be incomplete", t)
        }
    }

    /**
     * Canonical way to obtain any path inside a [TriggerFolder]. Pure path
     * derivation — no directory is created here; the tree is pre-created by
     * [ensureTree] and callers that create deeper structure do their own
     * `mkdirs`.
     */
    fun dir(folder: TriggerFolder, vararg subPath: String): File {
        var file = File(root, folder.relativePath)
        for (segment in subPath) {
            file = File(file, segment)
        }
        return file
    }

    /**
     * Per-account directory under the `accounts/` folder. The [userKey] is
     * sanitized to `[a-zA-Z0-9_-]` (any other character becomes `_`), capped
     * at [MAX_ACCOUNT_SEGMENT_LENGTH] chars; a key that sanitizes to nothing
     * becomes "unknown". Keeps account-scoped files (caches, per-user state)
     * separated without ever letting a raw identifier escape the filesystem.
     */
    fun accountDir(userKey: String): File {
        val safe = buildString(capacity = userKey.length) {
            for (c in userKey) {
                append(if (c.isSafeAccountChar()) c else '_')
            }
        }.take(MAX_ACCOUNT_SEGMENT_LENGTH).ifEmpty { "unknown" }
        return File(dir(TriggerFolder.ACCOUNTS), safe)
    }

    /**
     * Internal-only directory for view-once payloads (used by a later phase).
     * Deliberately under [Context.getFilesDir] — NOT under the external root —
     * so these files are never visible through external storage, MTP, or a
     * user with a file manager.
     */
    fun viewOnceDir(): File = File(appContext.filesDir, VIEW_ONCE_DIR_NAME)

    /**
     * Generates a unique media file path: `TRG-yyyyMMdd-HHmmss-<random5>.<ext>`
     * inside the folder's `Sent/` leaf when [isSent] (folders without a sent
     * leaf fall back to the folder root), otherwise at the folder root.
     *
     * Uniqueness = second-granularity timestamp + 5 [SecureRandom] characters,
     * re-checked with [File.exists]; on the (practically impossible) event of
     * repeated collisions, a base-36 nanoTime suffix is used instead of
     * looping forever. [extension] is sanitized to plain ASCII alphanumerics
     * (defaulting to "bin"); no directory is created eagerly beyond a
     * best-effort `mkdirs` of the parent, so the returned path is writable
     * even if [ensureTree] has not run yet.
     */
    fun newUserMediaFile(folder: TriggerFolder, isSent: Boolean, extension: String): File {
        val ext = sanitizeExtension(extension)
        val base = File(root, folder.relativePath)
        val parent = if (isSent && folder.sentSubfolder != null) {
            File(base, folder.sentSubfolder)
        } else {
            base
        }
        repeat(MAX_NAME_ATTEMPTS) {
            val candidate = File(parent, "TRG-${timestampSegment()}-${randomSuffix()}.$ext")
            if (!candidate.exists()) {
                ensureParentQuietly(parent)
                return candidate
            }
        }
        val fallback = File(parent, "TRG-${timestampSegment()}-${System.nanoTime().toString(36)}.$ext")
        ensureParentQuietly(parent)
        return fallback
    }

    // ---------------------------------------------------------------- private

    private fun mkdirsOrWarn(dir: File, label: String) {
        try {
            if (dir.isDirectory) return // already exists — idempotent fast path
            if (!dir.mkdirs() && !dir.isDirectory) {
                // Second isDirectory() covers the concurrent-creation race.
                Log.w(TAG, "Could not create storage directory ($label): ${dir.absolutePath}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create storage directory ($label): ${dir.absolutePath}", t)
        }
    }

    private fun ensureParentQuietly(parent: File) {
        if (!parent.isDirectory) mkdirsOrWarn(parent, label = parent.absolutePath)
    }

    private fun timestampSegment(): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date())

    private fun randomSuffix(): String {
        val sb = StringBuilder(RANDOM_SUFFIX_LENGTH)
        repeat(RANDOM_SUFFIX_LENGTH) {
            sb.append(FILENAME_ALPHABET[secureRandom.nextInt(FILENAME_ALPHABET.length)])
        }
        return sb.toString()
    }

    private fun sanitizeExtension(raw: String): String {
        val cleaned = raw.trim()
            .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            .lowercase(Locale.US)
            .take(MAX_EXTENSION_LENGTH)
        return cleaned.ifEmpty { DEFAULT_EXTENSION }
    }

    private fun Char.isSafeAccountChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

    companion object {
        private const val TAG = "TriggerStorage"

        /** Name of the storage root inside getExternalFilesDir(null) / filesDir. */
        const val ROOT_DIR_NAME = "Trigger"

        /** Internal-only leaf for view-once media (never under the external root). */
        const val VIEW_ONCE_DIR_NAME = "view_once"

        private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"
        private const val FILENAME_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val RANDOM_SUFFIX_LENGTH = 5
        private const val MAX_NAME_ATTEMPTS = 16
        private const val MAX_EXTENSION_LENGTH = 10
        private const val DEFAULT_EXTENSION = "bin"
        private const val MAX_ACCOUNT_SEGMENT_LENGTH = 100
    }
}

/**
 * Shared name of the outgoing-media leaf (`Sent/`) inside every media base.
 * Top-level (not in the enum's companion) because enum entry constructor
 * arguments run before the companion is initialized.
 */
const val TRIGGER_SENT_SUBFOLDER = "Sent"

/**
 * The 14 logical storage locations modeling the 19 physical directories under
 * the root: the five media bases each own a `Sent/` leaf ([sentSubfolder]),
 * giving 5 + 5 + 9 = 19 directories in total.
 *
 * [relativePath] always uses '/' separators relative to [TriggerStorageManager.root]
 * and is joined via [java.io.File], so it is portable across Linux-based devices.
 */
enum class TriggerFolder(
    /** Path relative to the storage root (no leading slash). */
    val relativePath: String,
    /** Name of the sent-leaf subfolder for media bases; null for plain folders. */
    val sentSubfolder: String? = null
) {
    MEDIA("Media"),
    IMAGES("Media/Trigger Images", TRIGGER_SENT_SUBFOLDER),
    VIDEO("Media/Trigger Video", TRIGGER_SENT_SUBFOLDER),
    AUDIO("Media/Trigger Audio", TRIGGER_SENT_SUBFOLDER),
    DOCUMENTS("Media/Trigger Documents", TRIGGER_SENT_SUBFOLDER),
    VOICE_NOTES("Media/Trigger Voice Notes", TRIGGER_SENT_SUBFOLDER),
    DATABASES("Databases"),
    BACKUPS("Backups"),
    ACCOUNTS("accounts"),
    SHARED(".Shared"),
    TRASH(".trash"),
    THUMBS(".Thumbs"),
    PROFILE_PHOTOS("Profile Photos"),
    STREAMS("Streams");

    /** True when this folder models a media base with a Sent leaf. */
    val hasSentLeaf: Boolean get() = sentSubfolder != null

    companion object {
        /**
         * All 19 physical directories in stable creation order: each entry's
         * own directory, plus its Sent leaf when it has one.
         */
        fun physicalDirs(root: File): List<File> = entries.flatMap { folder ->
            val own = File(root, folder.relativePath)
            val sent = folder.sentSubfolder?.let { File(own, it) }
            if (sent != null) listOf(own, sent) else listOf(own)
        }
    }
}
