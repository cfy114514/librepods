package me.kavishdevar.librepods.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TakeoverSelectionTest {
    private val first = "AA:BB:CC:DD:EE:FF"
    private val second = "11:22:33:44:55:66"
    private fun state() = AudioProfileOwnership(setOf(2)).apply { select(first) }

    @Test fun explicitConnectCanReclaimLostAudioButSelectionAloneCannot() {
        val state = state()
        state.revoke(true)
        val selection = state.captureSelection(first.lowercase())!!
        assertNull(state.captureMedia(first))
        assertNull(state.beginRestore(first))
        assertNotNull(state.beginExplicitConnectIfCurrent(selection))
        assertNotNull(state.captureMedia(first))
        assertNull(state.beginExplicitConnectIfCurrent(selection))
    }

    @Test fun oldQueuedActionCannotReselectPreviousPeer() {
        val state = state()
        val selection = state.captureSelection(first)!!
        state.select(second)
        val current = state.captureMedia(second)!!
        assertNull(state.beginExplicitConnectIfCurrent(selection))
        assertNull(state.captureSelection(first))
        assertTrue(state.isCurrentMedia(current))
        assertNotNull(state.captureSelection(second))
    }

    @Test fun returningToSamePeerAndReplacingProfileEpochDoNotReviveSelection() {
        val state = state()
        val selection = state.captureSelection(first)!!
        state.select(second)
        state.select(first)
        assertNull(state.beginExplicitConnectIfCurrent(selection))
        val beforeRelease = state.captureSelection(first)!!
        state.beginRelease(first, true)
        assertNull(state.beginExplicitConnectIfCurrent(beforeRelease))
    }

    @Test fun rejectedConnectionGuardPreservesLostOwnershipAndCanBeRetriedExplicitly() {
        val state = state()
        state.revoke(true)
        val selection = state.captureSelection(first)!!
        assertNull(state.beginExplicitConnectIfCurrent(selection) { false })
        assertNull(state.captureMedia(first))
        assertNotNull(state.beginExplicitConnectIfCurrent(selection) { true })
    }

    @Test fun alreadyStaleSelectionDoesNotInvokeTransportGuard() {
        val state = state()
        val selection = state.captureSelection(first)!!
        state.socketClosed()
        var called = false
        assertNull(state.beginExplicitConnectIfCurrent(selection) { called = true; true })
        assertFalse(called)
        assertNull(state.captureSelection(second))
    }

    @Test fun captureDoesNotWaitForBlockedProfileTransaction() {
        val state = state()
        val ticket = state.beginExplicitConnect(first)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Executors.newSingleThreadExecutor()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val work = writer.submit<Boolean> {
                state.withCurrent(ticket, 2) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNotNull(reader.submit<AudioProfileOwnership.SelectionTicket?> {
                state.captureSelection(first)
            }.get(500, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(work.get(1, TimeUnit.SECONDS))
        } finally { release.countDown(); writer.shutdownNow(); reader.shutdownNow() }
    }
}
