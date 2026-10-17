package me.kavishdevar.librepods.services

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun replacingPendingValueCompletesOnlyTheSupersededRequest() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        val ticket = queue.offer(1, Purpose.ATTACH, "attach")!!.ticket
        queue.offer(1, Purpose.POLICY, "old")
        queue.offer(1, Purpose.DISCOVER, "discover")
        queue.offer(1, Purpose.POLICY, "new")
        assertEquals(listOf("old"), discarded)
        assertEquals(listOf("attach", "new", "discover"), queue.take(ticket))
        queue.close()
        assertEquals(listOf("old"), discarded)
    }

    @Test fun reofferingTheSamePendingInstanceDoesNotCompleteTheRetainedValue() {
        val discarded = mutableListOf<Any>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        val request = Any()
        val ticket = queue.offer(1, Purpose.POLICY, request)!!.ticket
        queue.offer(1, Purpose.POLICY, request)
        assertTrue(discarded.isEmpty())
        assertSame(request, queue.take(ticket)!!.single())
        queue.close()
        assertTrue(discarded.isEmpty())
    }

    @Test fun rejectedWaitingAcquisitionCompletesAllPendingValuesWithoutRetry() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        val ticket = queue.offer(1, Purpose.ATTACH, "attach")!!.ticket
        queue.offer(1, Purpose.POLICY, "policy")
        queue.rejected(ticket)
        queue.rejected(ticket)
        assertEquals(listOf("attach", "policy"), discarded)
        assertFalse(queue.contains(ticket))
        assertNull(queue.take(ticket))
        assertNull(queue.finished(ticket))
        val next = queue.offer(1, Purpose.POLICY, "new")!!
        assertTrue(next.acquire)
        queue.rejected(ticket)
        assertEquals(listOf("new"), queue.take(next.ticket))
        assertEquals(listOf("attach", "policy"), discarded)
    }

    @Test fun lateRejectionCannotRetireAWorkingProxyOrStartAnOverlappingAcquisition() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        val ticket = queue.offer(1, Purpose.POLICY, "running")!!.ticket
        assertEquals(listOf("running"), queue.take(ticket))
        queue.offer(1, Purpose.POLICY, "next")
        queue.rejected(ticket)
        assertTrue(queue.contains(ticket))
        assertTrue(discarded.isEmpty())
        assertFalse(queue.offer(1, Purpose.ATTACH, "attach")!!.acquire)
        val successor = queue.finished(ticket)!!
        assertEquals(listOf("next", "attach"), queue.take(successor))
        assertTrue(discarded.isEmpty())
    }

    @Test fun completionBeforeDeliveryCannotReplaceTheWaitingAcquisition() {
        val queue = queue()
        val ticket = queue.offer(1, Purpose.POLICY, "waiting")!!.ticket
        assertNull(queue.finished(ticket))
        assertTrue(queue.contains(ticket))
        assertEquals(listOf("waiting"), queue.take(ticket))
    }

    @Test fun closeCompletesPendingAndPostCloseOffersButNeverTakenValues() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1, 2), Purpose.entries.toSet(), discarded::add)
        queue.offer(1, Purpose.ATTACH, "waiting")
        val working = queue.offer(2, Purpose.POLICY, "working")!!.ticket
        queue.take(working)
        queue.offer(2, Purpose.POLICY, "behind-working")
        queue.close()
        queue.close()
        assertEquals(setOf("waiting", "behind-working"), discarded.toSet())
        assertEquals(2, discarded.size)
        assertNull(queue.offer(1, Purpose.POLICY, "late"))
        assertEquals(1, discarded.count { it == "late" })
        assertFalse(discarded.contains("working"))
        assertNull(queue.finished(working))
    }

    @Test fun everyDiscardPathInvokesCompletionOutsideTheQueueMonitor() {
        val discarded = mutableListOf<String>()
        val heldMonitor = AtomicBoolean(false)
        lateinit var queue: PendingProfileRequests<Int, Purpose, String>
        queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet()) {
            if (Thread.holdsLock(queue)) heldMonitor.set(true)
            discarded.add(it)
        }
        val first = queue.offer(1, Purpose.POLICY, "replaced")!!.ticket
        queue.offer(1, Purpose.POLICY, "rejected")
        queue.rejected(first)
        queue.offer(1, Purpose.POLICY, "closed")
        queue.close()
        queue.offer(1, Purpose.POLICY, "late")
        assertEquals(listOf("replaced", "rejected", "closed", "late"), discarded)
        assertFalse(heldMonitor.get())
    }

    @Test fun blockedOldCompletionDoesNotPreventTakingItsReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet()) { value: String ->
            if (value == "old") {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            completed.incrementAndGet()
        }
        val ticket = queue.offer(1, Purpose.POLICY, "old")!!.ticket
        val workers = Executors.newFixedThreadPool(2)
        try {
            val replacing = workers.submit { queue.offer(1, Purpose.POLICY, "new") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val delivered = workers.submit<List<String>?> { queue.take(ticket) }
            assertEquals(listOf("new"), delivered.get(1, TimeUnit.SECONDS))
            release.countDown()
            replacing.get(3, TimeUnit.SECONDS)
            assertEquals(1, completed.get())
            queue.close()
            assertEquals(1, completed.get())
        } finally { release.countDown(); workers.shutdownNow(); queue.close() }
    }

    @Test fun aFailingCompletionDoesNotAbandonRemainingPendingRequests() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet()) { value: String ->
            discarded.add(value)
            if (value == "broken") throw IllegalStateException("Completion failed")
        }
        queue.offer(1, Purpose.ATTACH, "broken")
        queue.offer(1, Purpose.POLICY, "remaining")
        queue.close()
        assertEquals(listOf("broken", "remaining"), discarded)
        assertNull(queue.offer(1, Purpose.POLICY, "late"))
        assertEquals(listOf("broken", "remaining", "late"), discarded)
    }

    @Test fun closeRacingReplacementCompletesBothRequestsExactlyOnce() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val completions = Array(2) { AtomicInteger() }
                val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet()) { value: Int ->
                    completions[value].incrementAndGet()
                }
                queue.offer(1, Purpose.POLICY, 0)
                val start = CountDownLatch(1)
                val replacing = workers.submit { start.await(); queue.offer(1, Purpose.POLICY, 1) }
                val closing = workers.submit { start.await(); queue.close() }
                start.countDown()
                replacing.get(3, TimeUnit.SECONDS)
                closing.get(3, TimeUnit.SECONDS)
                assertEquals(1, completions[0].get())
                assertEquals(1, completions[1].get())
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun takeRacingCloseTransfersOrDiscardsEachRequestExactlyOnce() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val completions = Array(3) { AtomicInteger() }
                val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet()) { value: Int ->
                    completions[value].incrementAndGet()
                }
                val ticket = queue.offer(1, Purpose.ATTACH, 0)!!.ticket
                queue.offer(1, Purpose.POLICY, 1)
                queue.offer(1, Purpose.DISCOVER, 2)
                val start = CountDownLatch(1)
                val taking = workers.submit { start.await(); queue.take(ticket)?.forEach { completions[it].incrementAndGet() } }
                val closing = workers.submit { start.await(); queue.close() }
                start.countDown()
                taking.get(3, TimeUnit.SECONDS)
                closing.get(3, TimeUnit.SECONDS)
                completions.forEach { assertEquals(1, it.get()) }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun invalidOfferCannotReplaceTheCurrentPendingRequest() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        val current = queue.offer(1, Purpose.POLICY, "current")!!
        assertNull(queue.offer(1, Purpose.POLICY, "stale") { false })
        assertEquals(listOf("stale"), discarded)
        assertTrue(queue.contains(current.ticket))
        assertEquals(listOf("current"), queue.take(current.ticket))
        assertEquals(listOf("stale"), discarded)
    }

    @Test fun invalidOfferCompletesWithoutCreatingAnAcquisition() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        assertNull(queue.offer(1, Purpose.POLICY, "invalid") { false })
        assertEquals(listOf("invalid"), discarded)
        val valid = queue.offer(1, Purpose.POLICY, "valid") { true }!!
        assertTrue(valid.acquire)
        assertEquals(1L, valid.ticket.id)
        assertEquals(listOf("valid"), queue.take(valid.ticket))
    }

    @Test fun admissionChecksUnderTheQueueMonitorButDiscardRunsOutsideIt() {
        val admissionHeldMonitor = AtomicBoolean()
        val discardHeldMonitor = AtomicBoolean()
        val discards = AtomicInteger()
        lateinit var queue: PendingProfileRequests<Int, Purpose, String>
        queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet()) {
            discardHeldMonitor.set(Thread.holdsLock(queue))
            discards.incrementAndGet()
        }
        assertNull(queue.offer(1, Purpose.POLICY, "invalid") {
            admissionHeldMonitor.set(Thread.holdsLock(queue))
            false
        })
        assertTrue(admissionHeldMonitor.get())
        assertFalse(discardHeldMonitor.get())
        assertEquals(1, discards.get())
    }

    @Test fun delayedStaleProducerCannotReplaceNewerWorkAdmittedWhileItWaited() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        val generation = AtomicInteger(1)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val delayed = worker.submit<PendingProfileRequests.Offer<Int>?> {
                assertEquals(1, generation.get())
                entered.countDown()
                assertTrue(proceed.await(3, TimeUnit.SECONDS))
                queue.offer(1, Purpose.POLICY, "old") { generation.get() == 1 }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            generation.set(2)
            val current = queue.offer(1, Purpose.POLICY, "new") { generation.get() == 2 }!!
            proceed.countDown()
            assertNull(delayed.get(3, TimeUnit.SECONDS))
            assertEquals(listOf("old"), discarded)
            assertEquals(listOf("new"), queue.take(current.ticket))
        } finally { proceed.countDown(); worker.shutdownNow(); queue.close() }
    }

    @Test fun closedQueueCompletesTheOfferWithoutEvaluatingItsGuard() {
        val discarded = mutableListOf<String>()
        val queue = PendingProfileRequests(setOf(1), Purpose.entries.toSet(), discarded::add)
        queue.close()
        assertNull(queue.offer(1, Purpose.POLICY, "late") { fail("Closed queue evaluated admission"); true })
        assertEquals(listOf("late"), discarded)
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
