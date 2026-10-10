package me.kavishdevar.librepods.utils

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class KeyedWorkSessionTest {
    @Test fun rejectedOlderProducerCannotReplaceOrDiscardTheNewerValue() {
        val dispatcher = Dispatcher(); val discarded = mutableListOf<String>(); val received = mutableListOf<String>()
        val worker = KeyedWorkSession(setOf(1), dispatcher, onDiscard = discarded::add, consume = received::add)
        worker.offer(1, "new")
        assertFalse(worker.offerIf(1, "old") { false }); assertTrue(discarded.isEmpty())
        dispatcher.drain(); assertEquals(listOf("new"), received)
        worker.close(); dispatcher.drain()
    }
    @Test fun replacingPendingResourcesDiscardsOnlyTheReplacedValues() {
        val dispatcher = Dispatcher(); val discarded = mutableListOf<String>(); val received = mutableListOf<String>()
        val worker = KeyedWorkSession(setOf(1, 2), dispatcher, onDiscard = discarded::add, consume = received::add)
        worker.offer(1, "old"); worker.offer(2, "other"); worker.offer(1, "latest")
        assertEquals(listOf("old"), discarded); dispatcher.drain(); assertEquals(listOf("latest", "other"), received)
        worker.close(); dispatcher.drain(); assertEquals(listOf("old"), discarded)
    }
    @Test fun closeAndCoroutineCleanupReleaseEachPendingResourceOnce() {
        val dispatcher = Dispatcher(); val discarded = mutableListOf<String>()
        val worker = KeyedWorkSession(setOf(1, 2), dispatcher, onDiscard = discarded::add, consume = { fail("Closed resource executed") })
        worker.offer(1, "one"); worker.offer(2, "two"); worker.close(); dispatcher.drain(); worker.close()
        assertEquals(listOf("one", "two"), discarded)
    }
    private class Dispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() {
            var remaining = 1000
            while (queued.isNotEmpty()) { check(remaining-- > 0); queued.removeFirst().run() }
        }
    }

    @Test fun aHundredThousandEditsRetainOnlyLatestValueForEachKnownSetting() {
        val dispatcher = Dispatcher()
        val received = mutableListOf<Pair<Int, Int>>()
        val worker = KeyedWorkSession(setOf(1, 2, 3), dispatcher, consume = received::add)
        repeat(100_000) { worker.offer(it % 3 + 1, (it % 3 + 1) to it) }
        assertEquals(1, dispatcher.queued.size)
        dispatcher.drain()
        assertEquals(listOf(1 to 99_999, 2 to 99_997, 3 to 99_998), received)
        worker.close(); dispatcher.drain()
    }

    @Test fun newerAbsoluteValuesReplacePendingValuesWithoutReorderingOtherSettings() {
        val dispatcher = Dispatcher()
        val received = mutableListOf<String>()
        val worker = KeyedWorkSession(setOf("EQ", "mode"), dispatcher, consume = received::add)
        worker.offer("EQ", "enabled low=50")
        worker.offer("mode", "transparency")
        worker.offer("EQ", "enabled low=65")
        dispatcher.drain()
        assertEquals(listOf("enabled low=65", "transparency"), received)
        worker.close(); dispatcher.drain()
    }

    @Test fun offersDuringAnActiveWriteStillDeliverTheFinalValue() {
        val dispatcher = Dispatcher()
        val received = mutableListOf<Int>()
        lateinit var worker: KeyedWorkSession<Int, Int>
        worker = KeyedWorkSession(setOf(1), dispatcher) {
            received.add(it)
            if (it == 0) repeat(10_000) { value -> worker.offer(1, value + 1) }
        }
        worker.offer(1, 0); dispatcher.drain()
        assertEquals(listOf(0, 10_000), received)
        worker.close(); dispatcher.drain()
    }

    @Test fun closingOldWorkerDropsItsPendingCommandsWithoutCancellingTheReplacement() {
        val dispatcher = Dispatcher()
        val received = mutableListOf<String>()
        val old = KeyedWorkSession(setOf(1), dispatcher, consume = received::add)
        old.offer(1, "old"); old.close()
        val fresh = KeyedWorkSession(setOf(1), dispatcher, consume = received::add)
        fresh.offer(1, "fresh"); old.close(); dispatcher.drain()
        assertEquals(listOf("fresh"), received)
        assertFalse(old.offer(1, "late"))
        fresh.close(); dispatcher.drain()
    }

    @Test fun transientFailureDoesNotCancelOtherSettingsAndFatalCancellationRejectsMoreWork() {
        val dispatcher = Dispatcher()
        val received = mutableListOf<Int>()
        val failures = mutableListOf<Exception>()
        val worker = KeyedWorkSession<Int, Int>(setOf(1, 2), dispatcher, failures::add) {
            if (it == 1) throw IllegalStateException("write failed")
            received.add(it)
        }
        worker.offer(1, 1); worker.offer(2, 2); dispatcher.drain()
        assertEquals(1, failures.size)
        assertEquals(listOf(2), received)
        worker.close(); dispatcher.drain()
        val cancelled = KeyedWorkSession<Int, Int>(setOf(1), dispatcher) { throw CancellationException("closed") }
        cancelled.offer(1, 1); dispatcher.drain()
        assertTrue(cancelled.isClosed)
        assertFalse(cancelled.offer(1, 2))
    }

    @Test fun unknownSettingsCannotGrowTheBoundedKeySpace() {
        val dispatcher = Dispatcher()
        val worker = KeyedWorkSession<Int, Int>(setOf(1), dispatcher) { }
        assertThrows(IllegalArgumentException::class.java) { worker.offer(2, 0) }
        worker.close(); dispatcher.drain()
    }
}
