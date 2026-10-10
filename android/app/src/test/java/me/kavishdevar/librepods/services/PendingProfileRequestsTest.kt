package me.kavishdevar.librepods.services

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PendingProfileRequestsTest {
    private enum class Purpose { DISCOVER, ATTACH, POLICY }
    private fun queue() = PendingProfileRequests<Int, Purpose, String>(setOf(1, 2), Purpose.entries.toSet())

    @Test fun waitingFloodAcquiresOnceAndRetainsLatestPerPurpose() {
        val queue = queue()
        val first = queue.offer(1, Purpose.POLICY, "connect")!!
        assertTrue(first.acquire)
        repeat(10_000) {
            assertFalse(queue.offer(1, Purpose.DISCOVER, "discovery-$it")!!.acquire)
            assertFalse(queue.offer(1, Purpose.ATTACH, "attach-$it")!!.acquire)
        }
        assertEquals(listOf("connect", "discovery-9999", "attach-9999"), queue.take(first.ticket))
        assertNull(queue.finished(first.ticket))
    }

    @Test fun workingProxyFinishesBeforePendingActionsAcquireAnother() {
        val queue = queue()
        val first = queue.offer(1, Purpose.POLICY, "connect")!!
        assertEquals(listOf("connect"), queue.take(first.ticket))
        assertNull(queue.take(first.ticket))
        repeat(100) { assertFalse(queue.offer(1, Purpose.POLICY, "policy-$it")!!.acquire) }
        val next = queue.finished(first.ticket)!!
        assertNotEquals(first.ticket, next)
        assertFalse(queue.contains(first.ticket))
        assertEquals(listOf("policy-99"), queue.take(next))
        assertNull(queue.finished(next))
    }

    @Test fun replacedPolicyKeepsItsPlaceAmongIndependentActions() {
        val queue = queue()
        val first = queue.offer(1, Purpose.ATTACH, "attach")!!
        queue.offer(1, Purpose.POLICY, "connect")
        queue.offer(1, Purpose.DISCOVER, "discover")
        queue.offer(1, Purpose.POLICY, "disconnect")
        assertEquals(listOf("attach", "disconnect", "discover"), queue.take(first.ticket))
    }

    @Test fun profilesHaveIndependentAcquisitions() {
        val queue = queue()
        val a = queue.offer(1, Purpose.POLICY, "a")!!
        val b = queue.offer(2, Purpose.POLICY, "b")!!
        assertTrue(a.acquire && b.acquire)
        assertEquals(listOf("b"), queue.take(b.ticket))
        assertEquals(listOf("a"), queue.take(a.ticket))
    }

    @Test fun closeDropsWaitingAndWorkingPendingActions() {
        val queue = queue()
        val waiting = queue.offer(1, Purpose.ATTACH, "waiting")!!.ticket
        val working = queue.offer(2, Purpose.POLICY, "working")!!.ticket
        queue.take(working)
        queue.offer(2, Purpose.POLICY, "queued")
        queue.close()
        assertTrue(queue.isClosed)
        assertFalse(queue.contains(waiting))
        assertNull(queue.take(waiting))
        assertNull(queue.finished(working))
        assertNull(queue.offer(1, Purpose.ATTACH, "late"))
    }

    @Test fun rejectedAcquisitionCanBeRetriedOnlyByANewRequest() {
        val queue = queue()
        val first = queue.offer(1, Purpose.POLICY, "first")!!.ticket
        queue.rejected(first)
        assertNull(queue.take(first))
        val next = queue.offer(1, Purpose.POLICY, "second")!!
        assertTrue(next.acquire)
        queue.rejected(first)
        assertEquals(listOf("second"), queue.take(next.ticket))
    }

    @Test fun staleCompletionCannotRemoveOrDrainReplacement() {
        val queue = queue()
        val first = queue.offer(1, Purpose.POLICY, "first")!!.ticket
        queue.take(first)
        queue.offer(1, Purpose.POLICY, "second")
        val next = queue.finished(first)!!
        assertNull(queue.finished(first))
        assertNull(queue.take(first))
        assertEquals(listOf("second"), queue.take(next))
    }

    @Test fun concurrentWaitingProducersCreateOnlyTwoAcquisitions() {
        val queue = queue()
        val executor = Executors.newFixedThreadPool(8)
        val acquisitions = java.util.concurrent.atomic.AtomicInteger()
        try {
            (0 until 8).map { worker -> executor.submit {
                repeat(1000) { index ->
                    if (queue.offer(worker % 2 + 1, Purpose.entries[index % 3], "$worker:$index")!!.acquire)
                        acquisitions.incrementAndGet()
                }
            } }.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, acquisitions.get())
        } finally { executor.shutdownNow(); queue.close() }
    }

    @Test(expected = IllegalArgumentException::class) fun unknownProfileCannotGrowTheRegistry() {
        queue().offer(99, Purpose.POLICY, "unknown")
    }
}
