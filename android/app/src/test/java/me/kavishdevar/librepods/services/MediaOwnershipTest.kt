package me.kavishdevar.librepods.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaOwnershipTest {
    private val peer = "AA:BB:CC:DD:EE:FF"
    private fun state() = AudioProfileOwnership(setOf(2)).apply { select(peer) }

    @Test fun captureRequiresSelectedPeerAndDoesNotGrantReconnect() {
        val state = AudioProfileOwnership(setOf(2))
        assertNull(state.captureMedia(peer))
        state.select(peer)
        val ticket = state.captureMedia(peer.lowercase())!!
        repeat(1000) { assertTrue(state.isCurrentMedia(ticket)); assertNotNull(state.captureMedia(peer)) }
        assertNull(state.captureMedia("00:11:22:33:44:55"))
        assertFalse(state.hasReleased(peer))
        assertNull(state.beginRestore(peer))
    }

    @Test fun ownershipLossRejectsOldAndNewRequestsUntilLocalProfileReturns() {
        val state = state()
        val old = state.captureMedia(peer)!!
        state.revoke(true)
        assertFalse(state.isCurrentMedia(old))
        assertNull(state.captureMedia(peer))
        state.localProfileConnected("00:11:22:33:44:55", 2)
        assertNull(state.captureMedia(peer))
        state.localProfileConnected(peer, 2)
        assertNotNull(state.captureMedia(peer))
        assertFalse(state.isCurrentMedia(old))
    }

    @Test fun disconnectAndReturningToSamePeerCannotReviveOldRequests() {
        val state = state()
        val old = state.captureMedia(peer)!!
        state.socketClosed()
        assertFalse(state.isCurrentMedia(old))
        val disconnected = state.captureMedia(peer)!!
        state.select("00:11:22:33:44:55")
        state.select(peer)
        assertFalse(state.isCurrentMedia(disconnected))
        assertNotNull(state.captureMedia(peer))
    }

    @Test fun releaseAndEmptyRestoreInvalidatePendingMediaWithoutGrantingAudio() {
        val state = state()
        val old = state.captureMedia(peer)!!
        state.beginRelease(peer, true)
        assertFalse(state.isCurrentMedia(old))
        val pending = state.captureMedia(peer)!!
        assertNull(state.beginRestore(peer))
        assertFalse(state.isCurrentMedia(pending))
        assertFalse(state.hasReleased(peer))
        val restore = state.captureMedia(peer)!!
        state.cancelPendingRestore()
        assertFalse(state.isCurrentMedia(restore))
    }

    @Test fun cancelledClaimsAndNewExplicitConnectInvalidateOldRequests() {
        val state = state()
        state.claim(peer)
        val claim = state.captureMedia(peer)!!
        state.cancelClaim()
        assertFalse(state.isCurrentMedia(claim))
        val cancelled = state.captureMedia(peer)!!
        state.beginExplicitConnect(peer)
        assertFalse(state.isCurrentMedia(cancelled))
    }

    @Test fun unchangedSelectionAndRejectedOperationsPreserveMediaEpoch() {
        val state = state()
        val ticket = state.captureMedia(peer)!!
        state.select(peer.lowercase())
        state.cancelClaim()
        state.cancelPendingRestore()
        assertNull(state.beginRelease("00:11:22:33:44:55", true))
        assertTrue(state.isCurrentMedia(ticket))
    }

    @Test fun mediaReadsDoNotWaitForBlockedBluetoothProfileTransaction() {
        val state = state()
        val profile = state.beginExplicitConnect(peer)
        val media = state.captureMedia(peer)!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val blocked = worker.submit<Boolean> {
                state.withCurrent(profile, 2) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(reader.submit<Boolean> {
                state.captureMedia(peer) != null && state.isCurrentMedia(media)
            }.get(500, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(blocked.get(1, TimeUnit.SECONDS))
        } finally { release.countDown(); worker.shutdownNow(); reader.shutdownNow() }
    }
}
