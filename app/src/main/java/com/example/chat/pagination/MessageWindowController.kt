package com.example.chat.pagination

import com.example.model.DomainMessage
import com.example.model.MessageCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task 25 — WhatsApp-style bounded message window.
 *
 * Opening a chat renders ONLY the latest [initialWindowSize] messages from
 * Room (local-first: no Supabase wait when the cache is warm). Scrolling to
 * the top grows the window one [pageSize] page at a time — Room first, and
 * only when the local cache is exhausted does it fetch the previous page
 * from the server (which upserts into Room, keeping Room the single source
 * of truth). No page is ever re-fetched or duplicated: every boundary is a
 * strict [MessageCursor] comparison and Room REPLACE-upserts by id.
 *
 * The window is bounded on BOTH ends:
 *  - `top`    (inclusive): oldest message included. Null = include from the
 *             local beginning (only when the whole cache is already in the
 *             window).
 *  - `bottom` (inclusive): newest message included. Null = live edge (the
 *             normal state; every new realtime message appears).
 *             After a reply-navigation jump the bottom is pinned ~one page
 *             past the target so jumping to a 900-deep message never drags
 *             hundreds of rows into memory; [loadNewerMessages] then extends
 *             downward page-by-page as the user scrolls, and [releaseBottom]
 *             re-attaches the live edge (called when the user sends).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageWindowController(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val dataSource: DataSource,
    private val initialWindowSize: Int = 50,
    private val pageSize: Int = 50
) {

    /** Storage/network seams — implemented against Room + the sync-messages
     *  edge function in the app, and by fakes in JVM unit tests. */
    interface DataSource {
        /** Latest [limit] messages, NEWEST first. Local cache only. */
        suspend fun latest(conversationId: String, limit: Int): List<DomainMessage>

        /** Strictly older than [before], NEWEST first, up to [limit]. Local only. */
        suspend fun olderFromLocal(conversationId: String, before: MessageCursor, limit: Int): List<DomainMessage>

        /** Strictly newer than [after], OLDEST first, up to [limit]. Local only. */
        suspend fun newerFromLocal(conversationId: String, after: MessageCursor, limit: Int): List<DomainMessage>

        /** Server history page strictly older than [before]; the
         *  implementation MUST upsert the rows into the local cache.
         *  Returns the mapped messages, newest first. */
        suspend fun olderFromServer(conversationId: String, before: MessageCursor, limit: Int): List<DomainMessage>

        /** Single message by id from the server (upserted into the local cache), or null. */
        suspend fun messageFromServerById(conversationId: String, messageId: String): DomainMessage?

        /** Single message by id from the local cache, or null. */
        suspend fun messageById(conversationId: String, messageId: String): DomainMessage?

        /** Live Room flow over the inclusive [top..bottom] window (null = unbounded), ascending. */
        fun observeWindow(conversationId: String, top: MessageCursor?, bottom: MessageCursor?): Flow<List<DomainMessage>>
    }

    private val _top = MutableStateFlow<MessageCursor?>(null)
    private val _bottom = MutableStateFlow<MessageCursor?>(null)
    private val _initialized = MutableStateFlow(false)

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _isLoadingNewer = MutableStateFlow(false)
    val isLoadingNewer: StateFlow<Boolean> = _isLoadingNewer.asStateFlow()

    /** False only after a load proved there is nothing older (server short-read). */
    private val _hasMoreOlder = MutableStateFlow(true)
    val hasMoreOlder: StateFlow<Boolean> = _hasMoreOlder.asStateFlow()

    /** True while the bottom is pinned away from the live edge (post-jump). */
    val isWindowed: StateFlow<Boolean> =
        _bottom.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The bounded, chronologically-sorted message list the chat renders. */
    val messages: StateFlow<List<DomainMessage>> = _initialized
        .flatMapLatest { ready ->
            if (!ready) {
                flowOf(emptyList())
            } else {
                combine(_top, _bottom) { top, bottom -> top to bottom }
                    .flatMapLatest { (top, bottom) ->
                        dataSource.observeWindow(conversationId, top, bottom)
                    }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Anchor the window ONCE from the local cache — instant, no network.
        scope.launch {
            try {
                val latest = dataSource.latest(conversationId, initialWindowSize)
                _top.value =
                    if (latest.size >= initialWindowSize) MessageCursor.of(latest.last())
                    else null // whole local conversation fits the initial window
            } catch (_: Exception) {
                _top.value = null
            } finally {
                _initialized.value = true
            }
        }
    }

    /** Scroll-to-top trigger: grows the window by one page of older messages. */
    fun loadOlderMessages() {
        if (_isLoadingOlder.value || !_hasMoreOlder.value || !_initialized.value) return
        _isLoadingOlder.value = true
        scope.launch {
            try {
                val oldestLoaded = messages.value.firstOrNull()?.let(MessageCursor::of)
                if (oldestLoaded == null) return@launch // nothing rendered yet

                // 1) Local cache first — free and instant.
                val localPage = dataSource.olderFromLocal(conversationId, oldestLoaded, pageSize)
                var added = localPage

                // 2) Local exhausted → fetch the remainder from the server
                //    (rows are upserted into Room by the data source).
                if (localPage.size < pageSize) {
                    val remaining = pageSize - localPage.size
                    val serverPage = try {
                        dataSource.olderFromServer(conversationId, oldestLoaded, remaining)
                    } catch (_: Exception) {
                        emptyList<DomainMessage>() // offline: keep serving the cache
                    }
                    val serverOnly = serverPage.filter { s -> localPage.none { it.id == s.id } }
                    added = localPage + serverOnly
                    // Short read from the server = history exhausted. A full
                    // read leaves hasMore=true (another page may exist).
                    if (serverPage.size < remaining) _hasMoreOlder.value = false
                }

                if (added.isEmpty()) {
                    _hasMoreOlder.value = false
                    return@launch
                }

                // 3) Move the top anchor down to include the new page.
                val newTop = MessageCursor.of(added.minBy(MessageCursor::of))
                val currentTop = _top.value
                if (currentTop == null || newTop < currentTop) _top.value = newTop
            } catch (_: Exception) {
                // Non-fatal: hasMoreOlder stays true so a later scroll retries.
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    /** Post-jump downward growth: extends the pinned bottom toward the live edge. */
    fun loadNewerMessages() {
        if (_isLoadingNewer.value || _bottom.value == null || !_initialized.value) return
        _isLoadingNewer.value = true
        scope.launch {
            try {
                val bottom = _bottom.value ?: return@launch
                val page = dataSource.newerFromLocal(conversationId, bottom, pageSize)
                // Short read = we reached the live edge → unpin.
                _bottom.value = if (page.size < pageSize) null else MessageCursor.of(page.last())
            } catch (_: Exception) {
                // keep the pin; a later scroll retries
            } finally {
                _isLoadingNewer.value = false
            }
        }
    }

    /** Re-attaches the live edge (e.g. the user sent a message). */
    fun releaseBottom() {
        _bottom.value = null
    }

    /**
     * Reply navigation. Guarantees [messageId] is inside the window afterwards
     * WITHOUT ever loading more than ~two pages around the target:
     *  1. Already rendered → nothing to do.
     *  2. Cached in Room but outside the window → pin a one-page window
     *     around the target.
     *  3. Never synced (ancient) → fetch the single row from the server by
     *     id first, then pin the window around it.
     * Returns false only when the message could not be found anywhere.
     */
    suspend fun jumpToMessage(messageId: String): Boolean {
        if (messages.value.any { it.id == messageId }) return true
        if (!_initialized.value) return false
        return try {
            var target = dataSource.messageById(conversationId, messageId)
            if (target == null) {
                target = dataSource.messageFromServerById(conversationId, messageId) ?: return false
            }
            val targetCursor = MessageCursor.of(target)

            // Bottom pin: one page newer than the target (live edge when the
            // target is near the present).
            val newerPage = dataSource.newerFromLocal(conversationId, targetCursor, pageSize)
            _bottom.value = if (newerPage.size < pageSize) null else MessageCursor.of(newerPage.last())

            // Top pin: one page older than the target (local beginning when
            // nothing older is cached).
            val olderPage = dataSource.olderFromLocal(conversationId, targetCursor, pageSize)
            _top.value = if (olderPage.isNotEmpty()) MessageCursor.of(olderPage.last()) else null
            true
        } catch (_: Exception) {
            false
        }
    }
}
