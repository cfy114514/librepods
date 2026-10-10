package me.kavishdevar.librepods.services

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HeadTrackingSessionTest {
    @Test fun stopThenStartCannotReuseAnOldDelayedResume() {
        val state = HeadTrackingSession()
        val first = state.request(true, "AA")!!
        state.request(false, "AA")
        val second = state.request(true, "AA")!!
        assertNull(state.currentStart(first.id))
        assertEquals(second, state.currentStart(second.id))
        assertFalse(state.isCurrent(first))
    }

    @Test fun repeatedManualStartPreservesAnAlreadyRequestedStream() {
        val state = HeadTrackingSession()
        val first = state.request(true, "AA")!!
        val second = state.request(true, "AA")!!
        assertEquals(first, second)
        assertTrue(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
    }

    @Test fun repeatedStopsDoNotCreateNewStreamCommands() {
        val state = HeadTrackingSession()
        val first = state.request(false, "AA")!!
        val last = (0 until 1000).map { state.request(false, "AA")!! }.last()
        assertEquals(first, last)
        assertTrue(state.isCurrent(first))
        assertTrue(state.isCurrent(last))
        assertNull(state.currentStart(last.id))
    }

    @Test fun anotherPeerCannotBorrowAnEarlierRequest() {
        val state = HeadTrackingSession()
        val first = state.request(true, "AA")!!
        val second = state.request(true, "BB")!!
        assertFalse(state.isCurrent(first))
        assertEquals("BB", state.currentStart(second.id)!!.peer)
    }

    @Test fun closeInvalidatesAllCommandsAndRejectsLateStart() {
        val state = HeadTrackingSession()
        val first = state.request(true, "AA")!!
        state.close()
        assertFalse(state.isCurrent(first))
        assertFalse(state.capture().enabled)
        assertNull(state.currentStart(first.id))
        assertNull(state.request(true, "AA"))
    }

    @Test fun concurrentLeasesNeverShareAnIdAndOldReleasesCannotStopNewest() {
        val state = HeadTrackingSession()
        val executor = Executors.newFixedThreadPool(4)
        val tickets = java.util.concurrent.ConcurrentLinkedQueue<HeadTrackingSession.Lease>()
        try {
            (0 until 4).map { worker -> executor.submit {
                repeat(1000) { tickets.add(state.acquire(HeadTrackingSession.Consumer.MANUAL, "AA")!!) }
            } }.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(4000, tickets.map { it.id }.toSet().size)
            val newest = tickets.maxBy { it.id }
            tickets.filter { it != newest }.forEach { assertNull(state.release(it)) }
            assertTrue(state.capture().enabled)
            assertFalse(state.release(newest)!!.enabled)
        } finally { executor.shutdownNow(); state.close() }
    }

    @Test fun previewExitCannotStopGestures() {
        val state = HeadTrackingSession()
        val preview = state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")!!
        val current = state.capture()
        val gesture = state.acquire(HeadTrackingSession.Consumer.GESTURE, "AA")!!
        assertEquals(current, state.release(preview))
        assertTrue(state.capture().enabled)
        assertFalse(state.release(gesture)!!.enabled)
    }

    @Test fun gestureCompletionCannotStopPreview() {
        val state = HeadTrackingSession()
        val gesture = state.acquire(HeadTrackingSession.Consumer.GESTURE, "AA")!!
        val preview = state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")!!
        assertTrue(state.release(gesture)!!.enabled)
        assertFalse(state.release(preview)!!.enabled)
    }

    @Test fun manualStopCannotReleaseAnotherConsumersLease() {
        val state = HeadTrackingSession()
        state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")
        state.request(true, "AA")
        assertTrue(state.request(false, "AA")!!.enabled)
    }

    @Test fun latePreviewCloseCannotReleaseItsReplacement() {
        val state = HeadTrackingSession()
        val first = state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")!!
        val replacement = state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")!!
        assertNull(state.release(first))
        assertTrue(state.capture().enabled)
        assertFalse(state.release(replacement)!!.enabled)
    }

    @Test fun joiningAndLeavingSharedStreamDoesNotResetItsCalibrationTicket() {
        val state = HeadTrackingSession()
        state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")
        val current = state.capture()
        val gesture = state.acquire(HeadTrackingSession.Consumer.GESTURE, "AA")!!
        assertEquals(current, state.capture())
        state.release(gesture)
        assertEquals(current, state.capture())
    }

    @Test fun selectingAnotherPeerInvalidatesEveryOldLease() {
        val state = HeadTrackingSession()
        val old = HeadTrackingSession.Consumer.entries.map { state.acquire(it, "AA")!! }
        state.selectPeer("BB")
        assertFalse(state.capture().enabled)
        val fresh = state.acquire(HeadTrackingSession.Consumer.PREVIEW, "BB")!!
        old.forEach { assertNull(state.release(it)) }
        assertTrue(state.capture().enabled)
        assertFalse(state.release(fresh)!!.enabled)
    }

    @Test fun staleManualStopForAnotherPeerCannotCancelNewManualStart() {
        val state = HeadTrackingSession()
        state.request(true, "BB")
        assertTrue(state.request(false, "AA")!!.enabled)
        assertFalse(state.request(false, "BB")!!.enabled)
    }

    @Test fun reselectingSamePeerPreservesExistingConsumers() {
        val state = HeadTrackingSession()
        val lease = state.acquire(HeadTrackingSession.Consumer.GESTURE, "AA")!!
        val current = state.capture()
        state.selectPeer("AA")
        assertEquals(current, state.capture())
        assertFalse(state.release(lease)!!.enabled)
    }

    @Test fun closeDropsAllConsumersAndRejectsAcquisition() {
        val state = HeadTrackingSession()
        val leases = HeadTrackingSession.Consumer.entries.map { state.acquire(it, "AA")!! }
        state.close()
        leases.forEach { assertNull(state.release(it)) }
        assertNull(state.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA"))
        assertFalse(state.capture().enabled)
    }
}
