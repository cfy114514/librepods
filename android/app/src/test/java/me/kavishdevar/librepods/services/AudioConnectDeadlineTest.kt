package me.kavishdevar.librepods.services

import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalCoroutinesApi::class)
class AudioConnectDeadlineTest {
    private val peer = "AA:BB:CC:DD:EE:FF"
    private val a2dp = 2
    private val headset = 1

    /** Virtual dispatcher exercises real coroutine delay/cancellation without wall-clock waits. */
    private class Scheduler : CoroutineDispatcher(), Delay {
        private data class Wake(val at: Long, val id: Long, val continuation: CancellableContinuation<Unit>)
        private val ready = ArrayDeque<Runnable>()
        private val wakes = PriorityQueue(compareBy<Wake> { it.at }.thenBy { it.id })
        private var nextId = 0L
        var now = 0L
            private set
        val pendingDelays: Int get() = wakes.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { ready.addLast(block) }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val wake = Wake(now + timeMillis, ++nextId, continuation)
            wakes.add(wake)
            continuation.invokeOnCancellation { wakes.remove(wake) }
        }
        fun runCurrent() { while (ready.isNotEmpty()) ready.removeFirst().run() }
        fun queueDueAt(time: Long) {
            now = time
            while (wakes.peek()?.at?.let { it <= time } == true) {
                val wake = wakes.remove()
                if (wake.continuation.isActive) wake.continuation.resume(Unit)
            }
        }
        fun advanceTo(time: Long) { runCurrent(); queueDueAt(time); runCurrent() }
    }

    private fun fixture(
        profiles: Set<Int> = setOf(a2dp, headset),
        test: (AudioProfileOwnership, AudioConnectDeadline, Scheduler, CoroutineScope) -> Unit
    ) {
        val scheduler = Scheduler()
        val scope = CoroutineScope(SupervisorJob() + scheduler)
        val owner = AudioProfileOwnership(profiles).apply { select(peer) }
        val deadline = AudioConnectDeadline(owner, scope)
        try { test(owner, deadline, scheduler, scope) }
        finally { deadline.close(); scope.cancel(); scheduler.runCurrent() }
    }

    @Test fun expiresAtDeadlineWithoutChangingMediaEpoch() = fixture { owner, deadline, time, _ ->
        val ticket = owner.beginExplicitConnect(peer)
        val media = owner.captureMedia(peer)!!
        deadline.start(ticket)
        time.advanceTo(9_999)
        assertTrue(owner.isCurrent(ticket))
        time.advanceTo(10_000)
        assertFalse(owner.isCurrent(ticket))
        assertTrue(owner.isCurrentMedia(media))
        assertTrue(owner.claim(peer))
        assertEquals(0, time.pendingDelays)
    }

    @Test fun sameTicketDoesNotRestartOrDuplicateDeadline() = fixture { owner, deadline, time, _ ->
        val ticket = owner.beginExplicitConnect(peer)
        deadline.start(ticket)
        time.advanceTo(5_000)
        repeat(100) { deadline.start(ticket) }
        time.runCurrent()
        assertEquals(1, time.pendingDelays)
        time.advanceTo(10_000)
        assertFalse(owner.isCurrent(ticket))
    }

    @Test fun firstProfileCompletionKeepsDeadlineAndFinalCompletionCancelsIt() = fixture { owner, deadline, time, _ ->
        val ticket = owner.beginExplicitConnect(peer)
        deadline.start(ticket)
        time.runCurrent()
        deadline.complete(ticket, a2dp)
        assertTrue(owner.isCurrent(ticket))
        assertEquals(1, time.pendingDelays)
        deadline.complete(ticket, headset)
        assertFalse(owner.isCurrent(ticket))
        assertEquals(0, time.pendingDelays)
        val next = owner.beginExplicitConnect(peer)
        time.advanceTo(10_000)
        assertTrue(owner.isCurrent(next))
    }

    @Test fun staleStartCannotReplaceANewerTimer() = fixture { owner, deadline, time, _ ->
        val old = owner.beginExplicitConnect(peer)
        deadline.start(old)
        time.advanceTo(2_000)
        val current = owner.beginExplicitConnect(peer)
        deadline.start(current)
        time.runCurrent()
        deadline.start(old)
        assertEquals(1, time.pendingDelays)
        time.advanceTo(10_000)
        assertTrue(owner.isCurrent(current))
        time.advanceTo(12_000)
        assertFalse(owner.isCurrent(current))
    }

    @Test fun staleCompletionAndAbandonCannotCancelNewerDeadline() = fixture { owner, deadline, time, _ ->
        val old = owner.beginExplicitConnect(peer)
        deadline.start(old)
        val current = owner.beginExplicitConnect(peer)
        deadline.start(current)
        time.runCurrent()
        deadline.complete(old, a2dp)
        deadline.complete(old, headset)
        deadline.abandon(old)
        assertTrue(owner.isCurrent(current))
        assertEquals(1, time.pendingDelays)
        time.advanceTo(10_000)
        assertFalse(owner.isCurrent(current))
    }

    @Test fun queuedOldTimeoutCannotRetireItsReplacement() = fixture { owner, deadline, time, _ ->
        val old = owner.beginExplicitConnect(peer)
        deadline.start(old)
        time.runCurrent()
        time.queueDueAt(10_000) // The timeout continuation is ready but has not run.
        val current = owner.beginExplicitConnect(peer)
        deadline.start(current)
        time.runCurrent()
        assertTrue(owner.isCurrent(current))
        assertEquals(1, time.pendingDelays)
        time.advanceTo(20_000)
        assertFalse(owner.isCurrent(current))
    }

    @Test fun closeRetiresItsTicketAndPreventsNewTimers() = fixture { owner, deadline, time, _ ->
        val old = owner.beginExplicitConnect(peer)
        deadline.start(old)
        time.runCurrent()
        deadline.close()
        deadline.close()
        assertFalse(owner.isCurrent(old))
        assertEquals(0, time.pendingDelays)
        val current = owner.beginExplicitConnect(peer)
        deadline.start(current)
        time.advanceTo(10_000)
        assertTrue(owner.isCurrent(current))
        assertEquals(0, time.pendingDelays)
    }

    @Test fun cancelledParentRetiresRequestEvenBeforeLazyBodyRuns() = fixture { owner, deadline, time, scope ->
        scope.cancel()
        val ticket = owner.beginExplicitConnect(peer)
        deadline.start(ticket)
        time.runCurrent()
        assertFalse(owner.isCurrent(ticket))
        assertEquals(0, time.pendingDelays)
    }

    @Test fun restoreAndReleaseTicketsDoNotAcquireDeadline() = fixture { owner, deadline, time, _ ->
        val release = owner.beginRelease(peer, restorable = true)!!
        owner.recordReleased(release, a2dp, true)
        deadline.start(release)
        deadline.complete(release, a2dp)
        deadline.abandon(release)
        assertTrue(owner.isCurrent(release))
        val restore = owner.beginRestore(peer)!!
        deadline.start(restore)
        time.advanceTo(10_000)
        assertTrue(owner.isCurrent(restore))
        assertEquals(0, time.pendingDelays)
    }

    @Test fun repeatedTimeoutsReuseOneRegistrationAndDeliverOnlyFreshRetry() =
        assertBoundedMissingCallback(deliverToFreshRetry = true)

    @Test fun repeatedTimeoutsDiscardLateDeliveryAndPermitFreshAcquisition() =
        assertBoundedMissingCallback(deliverToFreshRetry = false)

    /** Helper composition only: an accepted registration deliberately never supplies its callback. */
    private fun assertBoundedMissingCallback(deliverToFreshRetry: Boolean) =
        fixture(setOf(a2dp)) { owner, deadline, time, _ ->
            val requests = PendingProfileRequests<Int, String, AudioProfileOwnership.Ticket>(
                setOf(a2dp), setOf("POLICY"), { deadline.complete(it, a2dp) }
            )
            val acquisitions = PendingAcquisitions<Int, PendingProfileRequests.Ticket<Int>>(setOf(a2dp))
            var registration: PendingAcquisitions.Ticket<Int>? = null
            var registrations = 0
            fun request(): AudioProfileOwnership.Ticket {
                val ticket = owner.beginExplicitConnect(peer)
                deadline.start(ticket)
                val offered = requests.offer(a2dp, "POLICY", ticket) { owner.isCurrent(ticket) }!!
                if (offered.acquire) {
                    val acquired = acquisitions.offer(a2dp, offered.ticket) { requests.contains(offered.ticket) }!!
                    assertTrue(acquired.acquire)
                    assertTrue(acquisitions.start(acquired.ticket) { requests.contains(it) }.ready)
                    registration = acquired.ticket
                    registrations++
                }
                time.runCurrent()
                assertEquals(1, time.pendingDelays)
                return ticket
            }
            try {
                repeat(100) {
                    val expired = request()
                    time.advanceTo(time.now + 10_000)
                    assertFalse(owner.isCurrent(expired))
                    assertEquals(0, time.pendingDelays)
                    assertEquals(1, registrations)
                }
                time.advanceTo(time.now + 1_000_000)
                assertEquals(1, registrations)
                assertEquals(0, time.pendingDelays)
                val current = if (deliverToFreshRetry) request() else null
                assertEquals(1, registrations)
                val deliveredRegistration = registration!!
                val recipient = acquisitions.take(deliveredRegistration)!!
                val delivered = requests.take(recipient)!!
                assertEquals(1, delivered.size)
                val actions = mutableListOf<AudioProfileOwnership.Ticket>()
                delivered.forEach { ticket ->
                    owner.withCurrent(ticket, a2dp) { actions.add(ticket) }
                }
                assertEquals(if (current == null) emptyList() else listOf(current), actions)
                delivered.forEach { deadline.complete(it, a2dp) }
                // The caller reports completion only after closing its delivered proxy.
                assertNull(requests.finished(recipient))
                assertNull(acquisitions.finished(deliveredRegistration))
                assertNull(acquisitions.take(deliveredRegistration))
                assertEquals(0, time.pendingDelays)
                current?.let { assertFalse(owner.isCurrent(it)) }
                val next = request()
                assertEquals(2, registrations)
                assertTrue(owner.isCurrent(next))
            } finally {
                requests.close()
            }
        }
}
