package me.kavishdevar.librepods.services

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class PacketLogHistoryTest {
    private class Dispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { var remaining = 100; while (queued.isNotEmpty()) { check(remaining-- > 0); queued.removeFirst().run() } }
    }
    @Test fun delayedLoadMergesOlderHistoryBeforeNewPacketsWithinCapacity() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>()
        val history = PacketLogHistory(d, { linkedSetOf("old-1", "old-2") }, saved::add, capacity = 3, windowMillis = 0)
        history.add("new-1"); history.add("new-2"); d.drain()
        assertEquals(linkedSetOf("old-2", "new-1", "new-2"), history.flow.value)
        assertEquals(listOf(history.flow.value), saved)
        history.close(); d.drain()
    }
    @Test fun clearingBeforeLoadDoesNotResurrectSavedHistory() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>()
        val history = PacketLogHistory(d, { setOf("old") }, saved::add, windowMillis = 0)
        history.add("pending"); history.clear(); history.add("after-clear"); d.drain()
        assertEquals(setOf("after-clear"), history.flow.value)
        assertEquals(listOf(setOf("after-clear")), saved)
        history.close(); d.drain()
    }
    @Test fun initialRestorationDoesNotRewriteOrDeleteHistory() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>()
        val history = PacketLogHistory(d, { setOf("old") }, saved::add, windowMillis = 0)
        d.drain(); assertEquals(setOf("old"), history.flow.value); assertTrue(saved.isEmpty())
        assertFalse(history.add("old")); d.drain(); assertTrue(saved.isEmpty())
        history.close(); d.drain()
    }
    @Test fun aHundredThousandPacketsPublishOneBoundedLatestSnapshot() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>()
        val history = PacketLogHistory(d, { emptySet() }, saved::add, capacity = 1000, windowMillis = 0)
        repeat(100_000) { history.add("packet-$it") }; d.drain()
        assertEquals(1, saved.size); assertEquals(1000, saved.single().size)
        assertEquals("packet-99000", saved.single().first()); assertEquals("packet-99999", saved.single().last())
        history.close(); d.drain()
    }
    @Test fun clearDuringAnEnteredWriteIsImmediateAndPersistsAfterIt() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>(); lateinit var history: PacketLogHistory
        history = PacketLogHistory(d, { emptySet() }, { value ->
            assertFalse(Thread.holdsLock(history.buffer))
            saved += value
            if (saved.size == 1) { history.clear(); assertTrue(history.flow.value.isEmpty()) }
        }, windowMillis = 0)
        history.add("entered"); d.drain()
        assertEquals(listOf(setOf("entered"), emptySet<String>()), saved)
        assertTrue(history.flow.value.isEmpty()); history.close(); d.drain()
    }
    @Test fun newPacketsDuringAnEnteredWriteRemainPendingAndSaveNext() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>(); lateinit var history: PacketLogHistory
        history = PacketLogHistory(d, { emptySet() }, { value ->
            saved += value
            if (saved.size == 1) repeat(500) { history.add("new-$it") }
        }, windowMillis = 0)
        history.add("first"); d.drain()
        assertEquals(2, saved.size); assertEquals(501, saved.last().size); assertTrue(saved.last().contains("new-499"))
        history.close(); d.drain()
    }
    @Test fun failedSaveDoesNotKillFuturePersistence() {
        val d = Dispatcher(); val saved = mutableListOf<Set<String>>(); var errors = 0; var writes = 0
        val history = PacketLogHistory(d, { emptySet() }, { if (++writes == 1) error("disk failure") else saved += it }, windowMillis = 0, onError = { errors++ })
        history.add("first"); d.drain(); assertEquals(1, errors)
        history.add("next"); d.drain(); assertEquals(listOf(setOf("first", "next")), saved)
        history.close(); d.drain()
    }
    @Test fun loadAndSaveNeverHoldTheProducerMonitor() {
        val d = Dispatcher(); lateinit var history: PacketLogHistory
        history = PacketLogHistory(d, { assertFalse(Thread.holdsLock(history.buffer)); setOf("old") }, { assertFalse(Thread.holdsLock(history.buffer)) }, windowMillis = 0)
        history.add("new"); d.drain(); assertEquals(setOf("old", "new"), history.flow.value)
        history.close(); d.drain()
    }
}
