package me.kavishdevar.librepods.utils

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test

class CoalescedWorkSessionTest {
    private class Dispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() {
            var remaining = 100
            while (queued.isNotEmpty()) { check(remaining-- > 0); queued.removeFirst().run() }
        }
    }
    private class Window {
        val waiting = ArrayDeque<CancellableContinuation<Unit>>()
        val durations = mutableListOf<Long>()
        suspend fun pause(duration: Long) = suspendCancellableCoroutine<Unit> {
            durations.add(duration)
            waiting.addLast(it)
        }
        fun finish() { waiting.removeFirst().resume(Unit) }
    }

    @Test fun aHundredThousandRequestsKeepOneWorkerAndPublishCurrentStateOnce() {
        val dispatcher = Dispatcher()
        val window = Window()
        val observed = mutableListOf<Int>()
        var current = 0
        val session = CoalescedWorkSession(dispatcher, pause = window::pause) { observed.add(current) }
        repeat(50_000) { current = it; assertTrue(session.request()) }
        assertEquals(1, dispatcher.queued.size)
        dispatcher.drain()
        repeat(50_000) { current = it + 50_000; assertTrue(session.request()) }
        assertEquals(listOf(100L), window.durations)
        assertEquals(1, window.waiting.size)
        window.finish(); dispatcher.drain()
        assertEquals(listOf(99_999), observed)
        assertEquals(listOf(100L), window.durations)
        session.close(); dispatcher.drain()
    }

    @Test fun changesArrivingDuringPublicationProduceExactlyOneFollowUp() {
        val dispatcher = Dispatcher()
        val window = Window()
        var publications = 0
        lateinit var session: CoalescedWorkSession
        session = CoalescedWorkSession(dispatcher, pause = window::pause) {
            publications++
            if (publications == 1) repeat(10_000) { session.request() }
        }
        session.request(); dispatcher.drain()
        window.finish(); dispatcher.drain()
        assertEquals(1, publications)
        assertEquals(1, window.waiting.size)
        window.finish(); dispatcher.drain()
        assertEquals(2, publications)
        assertTrue(window.waiting.isEmpty())
        session.close(); dispatcher.drain()
    }

    @Test fun closeCancelsThePendingWindowAndRejectsFurtherRefreshes() {
        val dispatcher = Dispatcher()
        val window = Window()
        var publications = 0
        val session = CoalescedWorkSession(dispatcher, pause = window::pause) { publications++ }
        session.request(); dispatcher.drain()
        session.close()
        window.finish(); dispatcher.drain()
        assertEquals(0, publications)
        assertFalse(session.request())
    }

    @Test fun aTransientPublicationFailureDoesNotStrandLaterRequests() {
        val dispatcher = Dispatcher()
        val window = Window()
        val errors = mutableListOf<Exception>()
        var attempts = 0
        val session = CoalescedWorkSession(dispatcher, pause = window::pause, onError = errors::add) {
            if (++attempts == 1) throw IllegalStateException("host unavailable")
        }
        session.request(); dispatcher.drain(); window.finish(); dispatcher.drain()
        assertEquals(1, errors.size)
        session.request(); dispatcher.drain(); window.finish(); dispatcher.drain()
        assertEquals(2, attempts)
        session.close(); dispatcher.drain()
    }

    @Test fun closingOldSessionDoesNotDropAReplacementRefresh() {
        val dispatcher = Dispatcher()
        val observed = mutableListOf<String>()
        val old = CoalescedWorkSession(dispatcher, pause = {}) { observed.add("old") }
        old.request(); old.close()
        val fresh = CoalescedWorkSession(dispatcher, pause = {}) { observed.add("fresh") }
        fresh.request(); old.close(); dispatcher.drain()
        assertEquals(listOf("fresh"), observed)
        fresh.close(); dispatcher.drain()
    }
}
