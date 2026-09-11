package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AccountStateManager
 * ----------------------------------------------------------------------------
 * Owns logout & account-switch hygiene for every piece of LOCAL per-user state:
 *
 *   - Room chat DB (encrypted "trigger_msgstore.db") -> messages/conversations leaked across accounts
 *   - "trigger_chat_prefs"                     -> last_sync_ts etc. leaked across accounts
 *   - "trigger_secret_vault_prefs" + media dir -> vault PIN hash + files leaked across accounts
 *   - WalletService / SecretVaultService /     -> in-memory StateFlows kept serving the
 *     MessageServiceImpl / PresenceServiceImpl    previous account's data until refetched
 *   - UserRepository                           -> previous account's profile row in memory
 *
 * Two entry points:
 *
 *  1. [performLogout] — the full logout sequence, executed on an APP-LEVEL scope
 *     wrapped in [NonCancellable]:
 *         presence offline -> realtime disconnect -> server signOut (revokes the
 *         session and clears it from memory + MODE_PRIVATE prefs) -> wipe all
 *         local per-user state -> THEN (and only then) invoke [onComplete] on
 *         the Main thread so the caller can navigate.
 *     The previous implementation launched cleanup in `rememberCoroutineScope()`
 *     and navigated immediately in parallel: the scope can be cancelled by
 *     composition teardown mid-cleanup, and the user could reach the signup
 *     flow before the stale session was cleared — leaving account A logged in
 *     while account B was being created.
 *
 *  2. [onUserSessionChanged] — safety net. Called whenever an authenticated
 *     surface (Dashboard) becomes active. If the authenticated user id differs
 *     from the last id this device saw, ALL local per-user state is wiped before
 *     the new account's UI reads it. Catches every path that lands a new account
 *     on top of the previous account's local data (signup without logout,
 *     restored stale session, etc.). Idempotent for the same user.
 */
object AccountStateManager {

    private const val TAG = "AccountStateManager"
    private const val STATE_PREFS = "trigger_account_state_prefs"
    private const val KEY_LAST_USER_ID = "last_local_user_id"

    /** App-level scope: outlives any screen/composition, survives navigation. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes wipe/switch so concurrent triggers never double-wipe. */
    private val guardMutex = Mutex()

    private val prefs: SharedPreferences? by lazy {
        try {
            AppServiceContainer.context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------
    // 1. Logout
    // ---------------------------------------------------------------

    /**
     * Full logout. Runs cleanup on an app-level, non-cancellable scope and
     * calls [onComplete] on Main ONLY after the session is revoked and all
     * local per-user data has been wiped — safe to navigate inside the
     * callback.
     */
    fun performLogout(onComplete: () -> Unit) {
        scope.launch {
            try {
                withContext(NonCancellable) {
                    runCatching {
                        // 1. Tell the server we're offline (still-valid token)
                        (AppServiceContainer.presenceService as? PresenceServiceImpl)?.onAppBackground()
                        // 2. Kill the Realtime WebSocket
                        AppServiceContainer.supabaseClient.disconnectRealtime()
                        // 3. Revoke the session server-side + clear it from
                        //    memory and the persisted session prefs
                        AppServiceContainer.supabaseClient.signOut()
                        // 4. Wipe every local trace of this account
                        wipeLocalAccountState()
                        markLocalUserId("")
                    }.onFailure {
                        Log.w(TAG, "Logout cleanup error: ${it.message}")
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    // ---------------------------------------------------------------
    // 2. Account-switch safety net
    // ---------------------------------------------------------------

    /**
     * Call when an authenticated surface becomes active with the id of the
     * currently authenticated user. Wipes all local per-user state when the
     * authenticated user differs from the last user seen on this device.
     * No-op when [currentUserId] is null/blank (unauthenticated) or unchanged.
     */
    fun onUserSessionChanged(currentUserId: String?) {
        if (currentUserId.isNullOrBlank()) return
        scope.launch {
            guardMutex.withLock {
                val last = prefs?.getString(KEY_LAST_USER_ID, null)
                if (last == currentUserId) return@withLock

                // last == null/"" means NO previous user's data can exist on
                // this device (fresh install, or a clean logout already wiped
                // everything). Skip the wipe in that case so we don't clobber
                // the new user's just-hydrated profile.
                if (!last.isNullOrBlank()) {
                    Log.w(TAG, "Authenticated user changed ($last -> $currentUserId) — wiping stale local data")
                    withContext(NonCancellable) {
                        runCatching { wipeLocalAccountState() }
                            .onFailure { Log.w(TAG, "Account-switch wipe error: ${it.message}") }
                    }
                    // The wipe cleared the profile singleton — re-hydrate it
                    // with the NEW account's row so the UI shows it.
                    withContext(NonCancellable) {
                        runCatching { ProfileService.refreshFromServer(AppServiceContainer.supabaseClient) }
                            .onFailure { Log.w(TAG, "Post-switch profile refresh failed: ${it.message}") }
                    }
                }
                markLocalUserId(currentUserId)
            }
        }
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    /**
     * Wipes ALL local per-user state. Must be called off the main thread.
     * Every step is independent and best-effort — one failure never blocks
     * the rest.
     */
    private suspend fun wipeLocalAccountState() = withContext(NonCancellable + Dispatchers.IO) {
        val ctx = try { AppServiceContainer.context } catch (e: Exception) { null }

        // 1. Room chat database (messages + conversations)
        try {
            AppServiceContainer.database.clearAllTables()
            Log.i(TAG, "Room chat DB wiped")
        } catch (e: Exception) {
            Log.w(TAG, "Room wipe failed: ${e.message}")
        }

        // 2. Chat prefs (last_sync_ts and friends — forces a full resync)
        ctx?.let { clearPrefs(it, "trigger_chat_prefs") }

        // 3. Vault prefs (PIN hash + salt) and vault media files
        ctx?.let { clearPrefs(it, "trigger_secret_vault_prefs") }
        try {
            if (ctx != null) {
                val vaultDir = File(ctx.filesDir, "trigger_secret_vault")
                if (vaultDir.deleteRecursively()) Log.i(TAG, "Vault media dir wiped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vault dir wipe failed: ${e.message}")
        }

        // 4. In-memory service state (singletons holding the old account's data)
        try { (AppServiceContainer.messageService as? MessageServiceImpl)?.reset() } catch (e: Exception) {
            Log.w(TAG, "MessageService reset failed: ${e.message}")
        }
        try { (AppServiceContainer.presenceService as? PresenceServiceImpl)?.reset() } catch (e: Exception) {
            Log.w(TAG, "PresenceService reset failed: ${e.message}")
        }
        try { AppServiceContainer.walletService.reset() } catch (e: Exception) {
            Log.w(TAG, "WalletService reset failed: ${e.message}")
        }
        try { AppServiceContainer.secretVaultService.resetLocalState() } catch (e: Exception) {
            Log.w(TAG, "SecretVaultService reset failed: ${e.message}")
        }

        // 5. Profile singleton
        UserRepository.clear()
        Log.i(TAG, "Local per-user state wiped")
    }

    private fun clearPrefs(context: Context, name: String) {
        try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "Prefs wipe failed for $name: ${e.message}")
        }
    }

    private fun markLocalUserId(userId: String) {
        try {
            prefs?.edit()?.putString(KEY_LAST_USER_ID, userId)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record local user id: ${e.message}")
        }
    }
}
