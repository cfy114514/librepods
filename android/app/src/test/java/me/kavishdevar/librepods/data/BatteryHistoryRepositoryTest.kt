package me.kavishdevar.librepods.data

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class BatteryHistoryRepositoryTest {
    private val first = "AA:BB:CC:DD:EE:FF"
    private val second = "11:22:33:44:55:66"
    private fun left(level: Int) = listOf(Battery(BatteryComponent.LEFT, level, BatteryStatus.NOT_CHARGING))
    private class Dispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { var remaining = 100; while (queued.isNotEmpty()) { check(remaining-- > 0); queued.removeFirst().run() } }
    }
    private class Clock { var elapsed = 0L; var wall = 1_000_000L }
    private fun repository(d: Dispatcher, clock: Clock, save: (BatteryHistorySnapshot) -> Unit,
                           initial: BatteryHistorySnapshot = BatteryHistorySnapshot(), onError: (Exception) -> Unit = {}) =
        BatteryHistoryRepository(initial, d, save, { clock.elapsed }, { clock.wall }, onError)

    @Test fun aHundredThousandChangingReportsRetainOnlyLatestPendingSnapshot() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val repo = repository(d, c, writes::add); val writer = repo.claim()
        repeat(100_000) { c.wall++; writer.record(first, left(it % 100)) }
        assertEquals(99, repo.snapshot().readings[BatteryComponent.LEFT]?.level)
        d.drain(); assertEquals(listOf(repo.snapshot()), writes)
        repo.close(); d.drain()
    }
    @Test fun readsDoNotAcquireWriterOwnershipOrWaitForDisk() {
        val d = Dispatcher(); val c = Clock(); val repo = repository(d, c, {})
        val writer = repo.claim(); writer.record(first, left(90))
        repeat(500) { assertEquals(first, repo.snapshot().address) }
        assertTrue(writer.record(first, left(80)))
        assertEquals(80, repo.snapshot().readings[BatteryComponent.LEFT]?.level)
        repo.close(); d.drain()
    }
    @Test fun aRetiredLeaseCannotReplaceOrFlushAnotherPeer() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val repo = repository(d, c, writes::add); val old = repo.claim(); old.record(first, left(90))
        val current = repo.claim(); current.record(second, listOf(Battery(BatteryComponent.RIGHT, 80, BatteryStatus.CHARGING)))
        assertFalse(old.record(first, left(1))); assertFalse(old.flush())
        d.drain(); assertEquals(second, writes.single().address)
        assertFalse(writes.single().readings.containsKey(BatteryComponent.LEFT))
        repo.close(); d.drain()
    }
    @Test fun closingAServiceLeaseKeepsItsAcceptedFinalFlush() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val repo = repository(d, c, writes::add); val writer = repo.claim()
        writer.record(first, left(90)); writer.flush(); writer.close()
        assertFalse(writer.record(first, left(1))); assertFalse(writer.flush())
        d.drain(); assertEquals(listOf(repo.snapshot()), writes)
        repo.close(); d.drain()
    }
    @Test fun enteredOldWritesCompleteBeforeTheReplacementWritesItsOwnSnapshot() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>(); lateinit var repo: BatteryHistoryRepository
        repo = repository(d, c, { value -> writes += value; if (writes.size == 1) repo.claim().record(second, left(80)) })
        repo.claim().record(first, left(90)); d.drain()
        assertEquals(listOf(first, second), writes.map { it.address })
        assertEquals(second, repo.snapshot().address)
        repo.close(); d.drain()
    }
    @Test fun repeatedLevelsAreThrottledButForceIsPreservedAcrossNewerReports() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val repo = repository(d, c, writes::add); val writer = repo.claim()
        writer.record(first, left(90)); d.drain()
        c.wall++; c.elapsed = 100; writer.record(first, left(90)); d.drain(); assertEquals(1, writes.size)
        writer.flush(); c.wall++; writer.record(first, left(90)); d.drain()
        assertEquals(2, writes.size); assertEquals(c.wall, writes.last().readings[BatteryComponent.LEFT]?.observedAt)
        repo.close(); d.drain()
    }
    @Test fun failedSavesDoNotAdvanceTheSuccessfulSnapshotCache() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>(); var attempts = 0; var errors = 0
        val repo = repository(d, c, { if (++attempts == 1) error("disk failure") else writes += it }, onError = { errors++ })
        val writer = repo.claim(); writer.record(first, left(90)); d.drain(); assertEquals(1, errors)
        writer.flush(); d.drain(); assertEquals(2, attempts); assertEquals(listOf(repo.snapshot()), writes)
        repo.close(); d.drain()
    }
    @Test fun claimingAnAlreadySavedInitialSnapshotDoesNotRewriteIt() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val initial = BatteryHistorySnapshot().merge(first, left(90), c.wall)
        val repo = repository(d, c, writes::add, initial); val writer = repo.claim(); d.drain(); assertTrue(writes.isEmpty())
        c.wall++; writer.record(first, left(90)); d.drain(); assertEquals(listOf(repo.snapshot()), writes)
        repo.close(); d.drain()
    }
    @Test fun invalidPeerAndUnavailableReadingsCannotContaminateHistory() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val repo = repository(d, c, writes::add); val writer = repo.claim()
        assertFalse(writer.record("bad", left(90)))
        writer.record(first, listOf(Battery(BatteryComponent.LEFT, 127, BatteryStatus.NOT_CHARGING), Battery(BatteryComponent.RIGHT, 0, BatteryStatus.NOT_CHARGING)))
        d.drain(); assertEquals(0, writes.single().readings[BatteryComponent.RIGHT]?.level)
        assertFalse(writes.single().readings.containsKey(BatteryComponent.LEFT))
        repo.close(); d.drain()
    }
    @Test fun anUnchangedReportAfterTheIntervalSavesTheFreshTimestamp() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val repo = repository(d, c, writes::add); val writer = repo.claim()
        writer.record(first, left(90)); d.drain(); c.elapsed = 30_000; c.wall += 30_000
        writer.record(first, left(90)); d.drain(); assertEquals(2, writes.size)
        assertEquals(c.wall, writes.last().readings[BatteryComponent.LEFT]?.observedAt)
        repo.close(); d.drain()
    }

    private fun coldRepository(d: Dispatcher, clock: Clock, save: (BatteryHistorySnapshot) -> Unit,
                               load: () -> BatteryHistorySnapshot, onError: (Exception) -> Unit = {}) =
        BatteryHistoryRepository(BatteryHistorySnapshot(), d, save, { clock.elapsed }, { clock.wall }, onError, load)

    @Test fun coldConstructionAndSnapshotNeverExecuteTheLoader() {
        val d = Dispatcher(); val c = Clock(); var loads = 0
        val disk = BatteryHistorySnapshot().merge(first, left(80), c.wall)
        val repo = coldRepository(d, c, { fail("Read-only load wrote history") }, { loads++; disk })
        repeat(500) { assertEquals(BatteryHistorySnapshot(), repo.snapshot()) }
        assertEquals(0, loads); d.drain(); assertEquals(1, loads)
        assertEquals(disk, repo.snapshot()); assertEquals(disk, repo.snapshots.value)
        repo.close(); d.drain()
    }

    @Test fun claimingBeforeColdLoadDoesNotRewriteAnUnchangedFile() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, left(80), c.wall)
        val repo = coldRepository(d, c, writes::add, { disk }); repo.claim().flush()
        d.drain(); assertEquals(disk, repo.snapshot()); assertTrue(writes.isEmpty())
        repo.close(); d.drain()
    }

    @Test fun reportsDuringColdLoadOverlayOnlyTheirComponentsAndKeepHistoricalTime() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, listOf(Battery(4, 80, BatteryStatus.NOT_CHARGING),
            Battery(2, 70, BatteryStatus.CHARGING), Battery(8, 60, BatteryStatus.NOT_CHARGING)), c.wall)
        lateinit var writer: BatteryHistoryRepository.Lease
        val repo = coldRepository(d, c, writes::add, { c.wall += 100; writer.record(first, left(50)); disk })
        writer = repo.claim(); d.drain()
        assertEquals(50, repo.snapshot().readings[4]?.level)
        assertEquals(c.wall, repo.snapshot().readings[4]?.observedAt)
        assertEquals(disk.readings[2], repo.snapshot().readings[2])
        assertEquals(disk.readings[8], repo.snapshot().readings[8])
        assertEquals(listOf(repo.snapshot()), writes); repo.close(); d.drain()
    }

    @Test fun anotherPeerDuringColdLoadCannotInheritAnyDiskComponents() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, left(80), c.wall)
        val repo = coldRepository(d, c, writes::add, { disk }); repo.claim().record(second,
            listOf(Battery(2, 70, BatteryStatus.NOT_CHARGING)))
        d.drain(); assertEquals(second, repo.snapshot().address)
        assertEquals(setOf(2), repo.snapshot().readings.keys)
        assertEquals(listOf(repo.snapshot()), writes); repo.close(); d.drain()
    }

    @Test fun switchingAwayAndBackBeforeLoadCannotResurrectDiscardedComponents() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, listOf(Battery(8, 60, BatteryStatus.NOT_CHARGING)), c.wall)
        val repo = coldRepository(d, c, writes::add, { disk }); val writer = repo.claim()
        writer.record(first, left(20)); writer.record(second, left(30)); writer.record(first, left(40))
        d.drain(); assertEquals(setOf(4), repo.snapshot().readings.keys)
        assertEquals(40, repo.snapshot().readings[4]?.level)
        assertEquals(listOf(repo.snapshot()), writes); repo.close(); d.drain()
    }

    @Test fun unavailableReportsDoNotEraseSavedComponentsDuringLoad() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, left(80), c.wall)
        val repo = coldRepository(d, c, writes::add, { disk })
        repo.claim().record(first, left(127)); d.drain()
        assertEquals(disk, repo.snapshot()); assertTrue(writes.isEmpty()); repo.close(); d.drain()
    }

    @Test fun replacingTheWriterDuringLoadKeepsOnlyTheCurrentAcceptedPeer() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, left(80), c.wall)
        val repo = coldRepository(d, c, writes::add, { disk }); val old = repo.claim()
        old.record(first, left(70)); val current = repo.claim(); current.record(second, left(60))
        assertFalse(old.record(first, left(1))); assertFalse(old.flush())
        d.drain(); assertEquals(second, writes.single().address)
        assertEquals(60, writes.single().readings[4]?.level); repo.close(); d.drain()
    }

    @Test fun closingTheLeaseBeforeLoadKeepsItsAcceptedFinalState() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>()
        val disk = BatteryHistorySnapshot().merge(first, listOf(Battery(8, 60, BatteryStatus.NOT_CHARGING)), c.wall)
        val repo = coldRepository(d, c, writes::add, { disk }); val writer = repo.claim()
        writer.record(first, left(50)); writer.flush(); writer.close()
        assertFalse(writer.record(first, left(1))); d.drain()
        assertEquals(setOf(4, 8), writes.single().readings.keys)
        repo.close(); d.drain()
    }

    @Test fun failedColdReadCannotWriteOverTheUnreadFileAndExplicitFlushRetries() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>(); var loads = 0; var errors = 0
        val disk = BatteryHistorySnapshot().merge(first, listOf(Battery(8, 60, BatteryStatus.NOT_CHARGING)), c.wall)
        val repo = coldRepository(d, c, writes::add, { if (++loads == 1) error("read failed") else disk }, { errors++ })
        val writer = repo.claim(); writer.record(first, left(50)); d.drain()
        assertEquals(1, loads); assertEquals(1, errors); assertTrue(writes.isEmpty())
        assertEquals(50, repo.snapshots.value.readings[4]?.level)
        writer.flush(); d.drain(); assertEquals(2, loads)
        assertEquals(setOf(4, 8), writes.single().readings.keys)
        assertEquals(writes.single(), repo.snapshots.value); repo.close(); d.drain()
    }

    @Test fun closingBeforeTheLoaderRunsDoesNotReadOrPublish() {
        val d = Dispatcher(); val c = Clock(); var loads = 0
        val repo = coldRepository(d, c, { fail("Closed loader wrote") }, { loads++; BatteryHistorySnapshot() })
        repo.close(); d.drain(); assertEquals(0, loads)
        assertEquals(BatteryHistorySnapshot(), repo.snapshots.value)
    }

    @Test fun anotherReadConsumerCanRetryInitialLoadingWithoutTakingWriterOwnership() {
        val d = Dispatcher(); val c = Clock(); val writes = mutableListOf<BatteryHistorySnapshot>(); var attempts = 0
        val disk = BatteryHistorySnapshot().merge(first, listOf(Battery(8, 60, BatteryStatus.NOT_CHARGING)), c.wall)
        val repo = coldRepository(d, c, writes::add, { if (++attempts == 1) error("read failed") else disk })
        val writer = repo.claim(); writer.record(first, left(50)); d.drain(); assertTrue(writes.isEmpty())
        repo.requestInitialLoad(); repo.requestInitialLoad(); d.drain(); assertEquals(2, attempts)
        assertEquals(60, repo.snapshot().readings[8]?.level)
        assertTrue(writer.record(first, left(49))); d.drain(); assertEquals(49, writes.last().readings[4]?.level)
        repo.close(); d.drain()
    }
}
