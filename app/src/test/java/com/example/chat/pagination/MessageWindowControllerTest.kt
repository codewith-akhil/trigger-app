package com.example.chat.pagination

import com.example.model.DomainMessage
import com.example.model.MessageCursor
import com.example.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 25 — JVM tests for the bounded message window (no Android deps).
 * Uses a fake local cache + server pool modeling a 1000-message thread to
 * verify every pagination requirement: initial window ≤ 50, incremental
 * pages, local-first with server fallback only on exhaustion, no duplicates
 * or skips, exhaustion detection, reply-navigation deep-jump bounding, and
 * live-edge re-attachment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageWindowControllerTest {

    private val convId = "conv-1"

    /** Fake Room cache + fake server history for one conversation. */
    private class FakeStore(serverTotal: Int, initialLocalLimit: Int) {
        private fun makeMessage(i: Int): DomainMessage = DomainMessage(
            id = "msg-%04d".format(i),
            conversationId = "conv-1",
            senderId = if (i % 2 == 0) "me" else "peer",
            senderName = if (i % 2 == 0) "You" else "Peer",
            text = "message $i",
            status = MessageStatus.SENT,
            timestamp = "h:mm",
            timestampMillis = 1_700_000_000_000L + i * 1_000L,
            isOutgoing = i % 2 == 0,
            seq = (i + 1).toLong()
        )

        /** Authoritative server history, ascending by the canonical cursor. */
        private val server: List<DomainMessage> = (0 until serverTotal).map(::makeMessage)

        /** The "Room table" for this conversation (ascending). */
        private val local = MutableStateFlow<List<DomainMessage>>(emptyList())

        var serverHistoryCalls = 0
        var serverByIdCalls = 0

        init {
            // Fresh install cache: only the newest [initialLocalLimit] rows.
            local.value = server.takeLast(initialLocalLimit)
        }

        private fun upsert(m: DomainMessage) {
            local.value = (local.value.filterNot { it.id == m.id } + m)
                .sortedWith(compareBy { MessageCursor.of(it) })
        }

        fun addServerMessage(m: DomainMessage) {
            // Realtime inserts land in Room too.
            upsert(m)
        }

        val dataSource = object : MessageWindowController.DataSource {
            override suspend fun latest(conversationId: String, limit: Int): List<DomainMessage> =
                local.value.takeLast(limit).reversed()

            override suspend fun olderFromLocal(conversationId: String, before: MessageCursor, limit: Int): List<DomainMessage> =
                local.value
                    .filter { MessageCursor.of(it) < before }
                    .takeLast(limit)
                    .reversed()

            override suspend fun newerFromLocal(conversationId: String, after: MessageCursor, limit: Int): List<DomainMessage> =
                local.value
                    .filter { MessageCursor.of(it) > after }
                    .take(limit)

            override suspend fun olderFromServer(conversationId: String, before: MessageCursor, limit: Int): List<DomainMessage> {
                serverHistoryCalls++
                val page = server
                    .filter { MessageCursor.of(it) < before }
                    .takeLast(limit)
                    .reversed()
                // The real data source upserts into Room (REPLACE by id).
                page.forEach { upsert(it) }
                return page
            }

            override suspend fun messageFromServerById(conversationId: String, messageId: String): DomainMessage? {
                serverByIdCalls++
                val hit = server.firstOrNull { it.id == messageId } ?: return null
                upsert(hit)
                return hit
            }

            override suspend fun messageById(conversationId: String, messageId: String): DomainMessage? =
                local.value.firstOrNull { it.id == messageId }

            override fun observeWindow(conversationId: String, top: MessageCursor?, bottom: MessageCursor?): Flow<List<DomainMessage>> =
                local.map { pool ->
                    pool.filter { m ->
                        val c = MessageCursor.of(m)
                        (top == null || c >= top) && (bottom == null || c <= bottom)
                    }
                }
        }
    }

    private fun MessageWindowController.awaitWindow(scope: CoroutineScope) {
        scope.launch { messages.collect {} }
    }

    // ------------------------------------------------------------------

    @Test
    fun `initial window loads only the latest 50 of 1000 cached messages`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        val window = controller.messages.value
        assertEquals(50, window.size)
        assertEquals("msg-0950", window.first().id)
        assertEquals("msg-0999", window.last().id)
        assertTrue(controller.hasMoreOlder.value)
        assertFalse(controller.isLoadingOlder.value)
    }

    @Test
    fun `loadOlderMessages grows the window by 50 from cache without duplicates`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        controller.loadOlderMessages()
        advanceUntilIdle()
        assertEquals(100, controller.messages.value.size)
        assertEquals("msg-0900", controller.messages.value.first().id)

        controller.loadOlderMessages()
        advanceUntilIdle()
        val window = controller.messages.value
        assertEquals(150, window.size)
        assertEquals("msg-0850", window.first().id)
        assertEquals(150, window.map { it.id }.distinct().size)
        assertEquals(0, store.serverHistoryCalls) // cache served everything
    }

    @Test
    fun `loadOlderMessages exhausts history and flips hasMoreOlder`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        repeat(19) {
            controller.loadOlderMessages()
            advanceUntilIdle()
            assertTrue(controller.hasMoreOlder.value)
        }
        assertEquals(1000, controller.messages.value.size)

        // One more attempt proves there is nothing older.
        controller.loadOlderMessages()
        advanceUntilIdle()
        assertFalse(controller.hasMoreOlder.value)
        assertEquals(1000, controller.messages.value.size)
        assertEquals(1000, controller.messages.value.map { it.id }.distinct().size)
    }

    @Test
    fun `cold cache fetches server pages progressively and never duplicates`() = runTest(UnconfinedTestDispatcher()) {
        // Fresh install: Room holds only the newest 50 of a 1000-message thread.
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 50)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()
        assertEquals(50, controller.messages.value.size)

        // 19 pages of 50 = the remaining 950 messages, then the final probe
        // that proves history is exhausted.
        repeat(20) {
            controller.loadOlderMessages()
            advanceUntilIdle()
        }

        val window = controller.messages.value
        assertEquals(1000, window.size)
        assertEquals(1000, window.map { it.id }.distinct().size)
        assertEquals("msg-0000", window.first().id)
        assertEquals(1000, storeLocalSize(store))
        assertFalse(controller.hasMoreOlder.value)
        // Exactly 20 server round-trips for 19 data pages + the exhaustion probe.
        assertEquals(20, store.serverHistoryCalls)
    }

    private fun storeLocalSize(store: FakeStore): Int {
        val field = FakeStore::class.java.getDeclaredField("local")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(store) as MutableStateFlow<List<DomainMessage>>
        return flow.value.size
    }

    @Test
    fun `jumpToMessage pins a bounded window around the target`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        assertTrue(controller.jumpToMessage("msg-0100"))
        advanceUntilIdle()

        val window = controller.messages.value
        assertTrue(window.any { it.id == "msg-0100" })
        // One page older (50) + the target + one page newer (50), inclusive.
        assertEquals(101, window.size)
        assertEquals("msg-0050", window.first().id)
        assertEquals("msg-0150", window.last().id)
        // The rest of the thread was NOT dragged into memory.
        assertFalse(window.any { it.id == "msg-0900" })
        assertTrue(controller.isWindowed.value)
    }

    @Test
    fun `jumpToMessage deep-fetches a target that was never cached`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 50)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        assertTrue(controller.jumpToMessage("msg-0100"))
        advanceUntilIdle()

        assertEquals(1, store.serverByIdCalls)
        val window = controller.messages.value
        assertTrue(window.any { it.id == "msg-0100" })
        assertTrue(window.size <= 120) // bounded around the target, never 1000
    }

    @Test
    fun `jumpToMessage returns false for an unknown id`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        assertFalse(controller.jumpToMessage("msg-does-not-exist"))
        assertFalse(controller.messages.value.any { it.id == "msg-does-not-exist" })
    }

    @Test
    fun `loadNewerMessages extends a pinned bottom to the live edge`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        assertTrue(controller.jumpToMessage("msg-0100"))
        advanceUntilIdle()

        repeat(20) {
            controller.loadNewerMessages()
            advanceUntilIdle()
        }
        assertFalse(controller.isWindowed.value) // live edge re-attached
        val window = controller.messages.value
        assertEquals("msg-0050", window.first().id)
        assertEquals("msg-0999", window.last().id)
        assertEquals(950, window.size)
    }

    @Test
    fun `releaseBottom re-attaches the live edge so new messages appear`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 1000)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        assertTrue(controller.jumpToMessage("msg-0100"))
        advanceUntilIdle()

        // A message arrives while the bottom is pinned — invisible (WhatsApp).
        store.addServerMessage(
            DomainMessage(
                id = "msg-1000",
                conversationId = "conv-1",
                senderId = "peer",
                senderName = "Peer",
                text = "while windowed",
                status = MessageStatus.SENT,
                timestamp = "h:mm",
                timestampMillis = 1_700_000_000_000L + 1000 * 1_000L,
                isOutgoing = false,
                seq = 1001L
            )
        )
        advanceUntilIdle()
        assertFalse(controller.messages.value.any { it.id == "msg-1000" })

        // The user sends → live edge re-attaches → the new message shows.
        controller.releaseBottom()
        advanceUntilIdle()
        assertEquals("msg-1000", controller.messages.value.last().id)
        assertFalse(controller.isWindowed.value)
    }

    @Test
    fun `window stays sorted by the canonical cursor`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(serverTotal = 1000, initialLocalLimit = 50)
        val controller = MessageWindowController(convId, backgroundScope, store.dataSource)
        controller.awaitWindow(backgroundScope)
        advanceUntilIdle()

        repeat(5) {
            controller.loadOlderMessages()
            advanceUntilIdle()
            val window = controller.messages.value
            val sorted = window.sortedWith(
                compareBy({ it.timestampMillis }, { it.seq }, { it.id })
            )
            assertEquals(sorted, window)
        }
    }
}
