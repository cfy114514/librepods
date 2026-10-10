package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class MusicVolumeSessionTest {
    private class Dispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { var limit = 1000; while (tasks.isNotEmpty()) { check(limit-- > 0); tasks.removeFirst().run() } }
    }
    private class Fixture : Closeable {
        val dispatcher = Dispatcher()
        val delivery = MusicVolumeDelivery(dispatcher)
        var volume = 4
        var maximum = 15
        var reads = 0
        var readHook: (() -> Unit)? = null
        var writeHook: ((Int) -> Unit)? = null
        var readFailure = false
        var writeFailure = false
        val writes = mutableListOf<Int>()
        val errors = mutableListOf<Exception>()
        fun session() = MusicVolumeSession(read = {
            reads++
            val snapshot = maximum to volume
            readHook?.invoke()
            if (readFailure) error("read failed")
            snapshot
        }, write = { next ->
            writes.add(next)
            writeHook?.invoke(next)
            if (writeFailure) error("write failed")
            volume = next
        }, delivery = delivery, onError = errors::add)
        fun drain() = dispatcher.drain()
        override fun close() { delivery.close(); drain() }
    }

    @Test fun initialStateIsUnknownAndRejectsWritesUntilReadCompletes() = Fixture().use { f ->
        val session = f.session()
        assertEquals(MusicVolumeState(), session.state.value)
        assertFalse(session.setVolume(0)); assertEquals(0, f.reads)
        f.drain()
        assertEquals(MusicVolumeState(15, 4, ready = true), session.state.value)
        assertTrue(f.writes.isEmpty())
    }
    @Test fun initialFailureCanRetryAndDoesNotPretendToBeMuted() = Fixture().use { f ->
        f.readFailure = true; val session = f.session(); f.drain()
        assertFalse(session.state.value.ready); assertTrue(session.state.value.failed)
        f.readFailure = false; f.volume = 9; session.refresh(); f.drain()
        assertEquals(MusicVolumeState(15, 9, ready = true), session.state.value)
    }
    @Test fun invalidSnapshotRetainsKnownStateAndRejectsOutOfRangeCommands() = Fixture().use { f ->
        val session = f.session(); f.drain()
        assertFalse(session.setVolume(-1)); assertFalse(session.setVolume(16))
        f.maximum = 0; session.refresh(); f.drain()
        assertEquals(MusicVolumeState(15, 4, ready = true, failed = true), session.state.value)
        f.maximum = 15; f.volume = 16; session.refresh(); f.drain()
        assertEquals(4, session.state.value.volume); assertEquals(2, f.errors.size)
        assertTrue(f.writes.isEmpty())
    }
    @Test fun refreshFloodDuringActiveReadOnlyKeepsLatestAndIgnoresOldSnapshot() = Fixture().use { f ->
        val session = f.session(); f.drain()
        f.readHook = { f.readHook = null; f.volume = 12; repeat(500) { session.refresh() } }
        session.refresh(); f.drain()
        assertEquals(3, f.reads) // initial, active, latest pending
        assertEquals(12, session.state.value.volume)
    }
    @Test fun writeFloodDuringActiveWriteIsSerialAndRefreshDoesNotReplaceCommand() = Fixture().use { f ->
        val session = f.session(); f.drain()
        f.writeHook = {
            f.writeHook = null
            repeat(500) { index -> session.setVolume(index % 16); session.refresh() }
            session.setVolume(13); session.refresh()
        }
        session.setVolume(6); f.drain()
        assertEquals(listOf(6, 13), f.writes)
        assertEquals(13, session.state.value.volume)
        assertFalse(session.state.value.pending); assertFalse(session.state.value.failed)
    }
    @Test fun refreshBeforeQueuedWriteCannotRestoreEarlierSystemVolume() = Fixture().use { f ->
        val session = f.session(); f.drain()
        session.refresh(); session.setVolume(10); session.refresh(); f.drain()
        assertEquals(listOf(10), f.writes)
        assertEquals(10, session.state.value.volume)
    }
    @Test fun backendReadbackReflectsSystemClampingRatherThanRequestedValue() = Fixture().use { f ->
        val session = MusicVolumeSession(read = { 15 to 7 }, write = { f.writes.add(it) }, delivery = f.delivery)
        f.drain(); session.setVolume(12)
        assertEquals(12, session.state.value.volume); assertTrue(session.state.value.pending)
        f.drain(); assertEquals(7, session.state.value.volume); assertFalse(session.state.value.pending)
    }
    @Test fun writeFailureAllowsAnExplicitRetryOfTheSameValue() = Fixture().use { f ->
        val session = f.session(); f.drain(); f.writeFailure = true
        session.setVolume(8); f.drain()
        assertTrue(session.state.value.failed); assertFalse(session.state.value.pending)
        f.writeFailure = false; assertTrue(session.setVolume(8)); f.drain()
        assertEquals(listOf(8, 8), f.writes); assertFalse(session.state.value.failed)
        assertEquals(8, session.state.value.volume)
    }
    @Test fun retiringWhileReadRunsCannotPublishOrDisplaceNewOwner() = Fixture().use { f ->
        val old = f.session(); lateinit var successor: MusicVolumeSession
        f.readHook = { f.readHook = null; f.volume = 11; successor = f.session(); old.close() }
        f.drain()
        assertFalse(old.state.value.ready); assertEquals(11, successor.state.value.volume)
        assertFalse(old.refresh()); assertTrue(successor.setVolume(9)); f.drain()
        assertEquals(listOf(9), f.writes)
    }
    @Test fun repeatedReplacementDuringActiveWriteKeepsOnlySuccessorsLatestOperation() = Fixture().use { f ->
        val old = f.session(); f.drain(); lateinit var successor: MusicVolumeSession
        f.writeHook = {
            f.writeHook = null
            repeat(500) { successor = f.session() }
            old.close()
        }
        old.setVolume(6); f.drain()
        assertTrue(old.state.value.pending) // retired result never published
        assertEquals(6, successor.state.value.volume); assertEquals(2, f.reads)
        assertFalse(old.setVolume(7)); assertTrue(successor.setVolume(10)); f.drain()
        assertEquals(listOf(6, 10), f.writes)
    }
    @Test fun newerWriteOfferedWhileOldReadbackRunsWins() = Fixture().use { f ->
        val session = f.session(); f.drain()
        f.readHook = { f.readHook = null; session.setVolume(14) }
        session.setVolume(5); f.drain()
        assertEquals(listOf(5, 14), f.writes)
        assertEquals(14, session.state.value.volume)
    }
    @Test fun closeDropsPendingWorkAndRejectsFurtherRequests() = Fixture().use { f ->
        val session = f.session(); f.drain()
        session.setVolume(12); session.close(); session.close(); f.drain()
        assertTrue(f.writes.isEmpty()); assertFalse(session.refresh()); assertFalse(session.setVolume(12))
    }
}
