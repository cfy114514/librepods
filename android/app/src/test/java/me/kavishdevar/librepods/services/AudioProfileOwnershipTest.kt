package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioProfileOwnershipTest {
    private val a2dp = 2
    private val headset = 1
    private val peer = "AA:BB:CC:DD:EE:FF"
    private fun policy() = AudioProfileOwnership(setOf(a2dp, headset)).apply { select(peer) }

    @Test fun passiveFirstBatteryAndEarReportsCannotClaimAudio() {
        val state = policy()
        repeat(100_000) { assertNull(state.beginRestore(peer)) }
        assertFalse(state.hasReleased(peer))
    }

    @Test fun rejectedReleaseNeverGrantsReconnect() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, false)
        assertNull(state.beginRestore(peer))
    }

    @Test fun restoreOnlyIncludesProfilesTheApplicationActuallyReleased() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, headset, true)
        val restore = state.beginRestore(peer)!!
        assertEquals(setOf(headset), restore.profiles)
        assertEquals(setOf(headset), restore.releasedProfiles)
        assertFalse(state.withCurrent(restore, a2dp) { fail("Must preserve disabled media audio") })
        assertTrue(state.withCurrent(restore, headset) { state.recordRestored(restore, headset) })
        assertNull(state.beginRestore(peer))
    }

    @Test fun socketDropKeepsAcceptedForbiddenPoliciesButInvalidatesOldCallbacks() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, true)
        val oldRestore = state.beginRestore(peer)!!
        state.socketClosed()
        assertFalse(state.withCurrent(oldRestore, a2dp) { fail("Old reader must not restore") })
        assertTrue(state.hasReleased(peer.lowercase()))
        assertEquals(setOf(a2dp), state.beginRestore(peer)!!.profiles)
    }

    @Test fun socketDropNeverKeepsAnUnfulfilledAutomaticClaim() {
        val state = policy()
        state.claim(peer)
        state.socketClosed()
        assertNull(state.beginRestore(peer))
    }

    @Test fun ownershipLossRevokesReleaseAndBlocksFurtherPassiveReleases() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, true)
        val restore = state.beginRestore(peer)!!
        state.revoke(lost = true)
        assertFalse(state.allowsMediaControl(peer))
        assertFalse(state.withCurrent(restore, a2dp) { fail("Other device owns audio") })
        assertNull(state.beginRelease(peer, true))
        assertNull(state.beginRestore(peer))
        assertFalse(state.hasReleased(peer))
        val relinquish = state.beginRelease(peer, false)!!
        assertNull(state.beginRestore(peer))
        assertTrue(state.withCurrent(relinquish, headset) {})
    }

    @Test fun realLocalConnectionOrExplicitClaimCanEndOwnershipLoss() {
        val state = policy()
        state.revoke(lost = true)
        state.localProfileConnected("11:22:33:44:55:66", a2dp)
        assertNull(state.beginRelease(peer, true))
        state.localProfileConnected(peer, a2dp)
        assertTrue(state.allowsMediaControl(peer))
        assertNotNull(state.beginRelease(peer, true))
        state.revoke(lost = true)
        state.claim(peer)
        assertEquals(setOf(a2dp, headset), state.beginRestore(peer)!!.profiles)
    }

    @Test fun explicitReconnectSupersedesRestoresAndDisconnectSupersedesExplicitReconnect() {
        val state = policy()
        state.claim(peer)
        val restore = state.beginRestore(peer)!!
        val manual = state.beginExplicitConnect(peer)
        assertFalse(state.isCurrent(restore))
        assertTrue(state.withCurrent(manual, a2dp) {})
        state.revoke()
        assertFalse(state.withCurrent(manual, headset) { fail("Late connect after user disconnect") })
        assertNull(state.beginRestore(peer))
    }

    @Test fun puttingEarphonesBackInBeforeProfileCallbackCancelsThePendingRelease() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        assertNull(state.beginRestore(peer))
        assertFalse(state.withCurrent(release, a2dp) { fail("Superseded release must not forbid audio") })
        state.recordReleased(release, a2dp, true)
        assertNull(state.beginRestore(peer))
    }

    @Test fun firstPassiveReportCannotCancelTheManualAudioConnectionItFollows() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        assertNull(state.beginRestore(peer))
        assertTrue(state.withCurrent(manual, a2dp) {})
        assertTrue(state.withCurrent(manual, headset) {})
    }

    @Test fun disablingAutomaticClaimInvalidatesItsCallbackButKeepsAcceptedRelease() {
        val state = policy()
        state.claim(peer)
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, true)
        val restore = state.beginRestore(peer)!!
        state.cancelClaim()
        assertFalse(state.isCurrent(restore))
        assertEquals(setOf(a2dp), state.beginRestore(peer)!!.profiles)
        val manual = state.beginExplicitConnect(peer)
        state.cancelClaim()
        assertTrue(state.isCurrent(manual))
    }

    @Test fun wrongPeerAndChangedSelectionInvalidatePendingOperations() {
        val state = policy()
        state.claim(peer)
        val restore = state.beginRestore(peer)!!
        assertNull(state.beginRestore("11:22:33:44:55:66"))
        assertTrue(state.isCurrent(restore))
        state.select("11:22:33:44:55:66")
        assertFalse(state.withCurrent(restore, a2dp) { fail("Must not act for the previous earphones") })
        assertFalse(state.hasReleased(peer))
    }

    @Test fun expiredBleClaimCannotRestoreUnreleasedProfiles() {
        val state = policy()
        state.claim(peer)
        val restore = state.beginRestore(peer)!!
        state.expireClaim()
        assertFalse(state.withCurrent(restore, a2dp) { fail("Expired presence must not grant a connection") })
        assertFalse(state.withCurrent(restore, headset) { fail("Expired presence must not grant a connection") })
        assertNull(state.beginRestore(peer))
    }

    @Test fun expiredBleClaimPreservesAcceptedPolicyRelease() {
        val state = policy()
        state.claim(peer)
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, true)
        val restore = state.beginRestore(peer)!!
        state.expireClaim()
        assertTrue(state.withCurrent(restore, a2dp) {})
        assertFalse(state.withCurrent(restore, headset) { fail("Only the accepted release may restore") })
        assertEquals(setOf(a2dp), state.beginRestore(peer)!!.profiles)
    }

    @Test fun expiredBleClaimPreservesManualSelectionBeforeAdmission() {
        val state = policy()
        state.claim(peer)
        val selection = state.captureSelection(peer)!!
        state.expireClaim()
        val manual = state.beginExplicitConnectIfCurrent(selection)!!
        assertTrue(state.withCurrent(manual, a2dp) {})
    }

    @Test fun expiredBlePresencePreservesExplicitRequestButRealDisconnectRevokesIt() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        state.expireClaim()
        assertTrue(state.withCurrent(manual, a2dp) {})
        assertTrue(state.withCurrent(manual, headset) {})
        state.socketClosed()
        assertFalse(state.withCurrent(manual, a2dp) { fail("A real disconnect must revoke old work") })
    }

    @Test fun passiveClaimCannotReplaceQueuedManualAudio() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        state.claim(peer.lowercase())
        assertTrue(state.withCurrent(manual, a2dp) {})
        assertTrue(state.withCurrent(manual, headset) {})
    }

    @Test fun manualProtectsEveryReservedProfileUntilAllWorkFinishes() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        state.completeExplicitProfile(manual, a2dp)
        assertFalse(state.withCurrent(manual, a2dp) { fail("Completed profile cannot run twice") })
        assertFalse(state.claim(peer))
        assertNull(state.beginRestore(peer))
        assertTrue(state.withCurrent(manual, headset) {})
        state.completeExplicitProfile(manual, headset)
        assertFalse(state.isCurrent(manual))
        assertTrue(state.claim(peer))
        assertEquals(setOf(a2dp, headset), state.beginRestore(peer)!!.profiles)
    }

    @Test fun ordinaryCompletionPreservesSelectionAndMediaButNotExplicitTicket() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        val selection = state.captureSelection(peer)!!
        val media = state.captureMedia(peer)!!
        state.completeExplicitProfile(manual, a2dp)
        state.completeExplicitProfile(manual, headset)
        assertFalse(state.isCurrent(manual))
        assertTrue(state.isCurrentMedia(media))
        assertNotNull(state.beginExplicitConnectIfCurrent(selection))
    }

    @Test fun unsuccessfulExplicitWorkCanRetireWithoutEverPublishingControl() {
        val state = policy()
        val failed = state.beginExplicitConnect(peer)
        // Terminal completion also represents lookup rejection or a denied profile.
        state.completeExplicitProfile(failed, a2dp)
        state.completeExplicitProfile(failed, headset)
        assertTrue(state.claim(peer))
        assertNotNull(state.beginRestore(peer))
    }

    @Test fun timeoutRetiresLateActionsAndAllowsAFreshClaim() {
        val state = policy()
        val timedOut = state.beginExplicitConnect(peer)
        val media = state.captureMedia(peer)!!
        state.abandonExplicitConnect(timedOut)
        assertFalse(state.withCurrent(timedOut, a2dp) { fail("Late callback after timeout") })
        assertFalse(state.withCurrent(timedOut, headset) { fail("Late callback after timeout") })
        assertTrue(state.isCurrentMedia(media))
        assertTrue(state.claim(peer))
        assertNotNull(state.beginRestore(peer))
    }

    @Test fun duplicateAndStaleCompletionsCannotReleaseANewerManualRequest() {
        val state = policy()
        val old = state.beginExplicitConnect(peer)
        val current = state.beginExplicitConnect(peer)
        state.completeExplicitProfile(old, a2dp)
        state.completeExplicitProfile(old, headset)
        state.abandonExplicitConnect(old)
        state.completeExplicitProfile(current, a2dp)
        state.completeExplicitProfile(current, a2dp)
        state.completeExplicitProfile(current, 999)
        assertFalse(state.claim(peer))
        assertTrue(state.withCurrent(current, headset) {})
        state.completeExplicitProfile(current, headset)
        assertTrue(state.claim(peer))
    }

    @Test fun automaticTakeoverCannotSupersedeActiveUserWork() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        val selection = state.captureSelection(peer)!!
        assertNull(state.beginExplicitConnectIfCurrent(selection, userInitiated = false))
        assertTrue(state.withCurrent(manual, a2dp) {})
        assertTrue(state.withCurrent(manual, headset) {})
        state.abandonExplicitConnect(manual)
        assertNotNull(state.beginExplicitConnectIfCurrent(selection, userInitiated = false))
    }

    @Test fun userCanSupersedeAutomaticWorkAndAutomaticWorkCanReplaceAutomaticWork() {
        val state = policy()
        val first = state.beginExplicitConnectIfCurrent(state.captureSelection(peer)!!, userInitiated = false)!!
        val second = state.beginExplicitConnectIfCurrent(state.captureSelection(peer)!!, userInitiated = false)!!
        assertFalse(state.isCurrent(first))
        val manual = state.beginExplicitConnect(peer)
        assertFalse(state.isCurrent(second))
        state.completeExplicitProfile(second, a2dp)
        state.abandonExplicitConnect(second)
        assertFalse(state.claim(peer))
        assertTrue(state.withCurrent(manual, headset) {})
    }

    @Test fun freshPassiveClaimCanReplaceAutomaticRequestWhoseSourceExpired() {
        val state = policy()
        val automatic = state.beginExplicitConnectIfCurrent(state.captureSelection(peer)!!, userInitiated = false)!!
        state.expireClaim()
        assertTrue(state.claim(peer))
        assertFalse(state.isCurrent(automatic))
        state.abandonExplicitConnect(automatic)
        assertEquals(setOf(a2dp, headset), state.beginRestore(peer)!!.profiles)
    }

    @Test fun atomicUserAdmissionSurvivesPassiveClaimButRejectsChangedSelectionOrSource() {
        val state = policy()
        state.claim(peer)
        val manual = state.beginExplicitConnectIfSelected(peer.lowercase())!!
        assertTrue(state.withCurrent(manual, a2dp) {})
        assertNull(state.beginExplicitConnectIfSelected(peer, valid = { false }))
        assertTrue(state.isCurrent(manual))
        state.select("11:22:33:44:55:66")
        assertNull(state.beginExplicitConnectIfSelected(peer))
        assertFalse(state.isCurrent(manual))
    }

    @Test fun actualRetirementStillWinsOverActiveUserAndLateCompletion() {
        val retirements: List<(AudioProfileOwnership) -> Unit> = listOf(
            { it.socketClosed() }, { it.revoke() }, { it.revoke(lost = true) },
            { it.select("11:22:33:44:55:66") }, { it.beginRelease(peer, restorable = false) }
        )
        for (retire in retirements) {
            val state = policy()
            val manual = state.beginExplicitConnect(peer)
            retire(state)
            state.completeExplicitProfile(manual, a2dp)
            state.abandonExplicitConnect(manual)
            assertFalse(state.withCurrent(manual, headset) { fail("Retired user operation") })
        }
    }

    @Test fun timeoutDoesNotWaitForBinderAndRejectsItsLateContinuation() {
        val state = policy()
        val manual = state.beginExplicitConnect(peer)
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val blocked = workers.submit<Boolean> {
                state.withCurrent(manual, a2dp) {
                    entered.countDown()
                    assertTrue(complete.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.submit { state.abandonExplicitConnect(manual) }.get(500, TimeUnit.MILLISECONDS)
            complete.countDown()
            assertFalse(blocked.get(1, TimeUnit.SECONDS))
            assertFalse(state.withCurrent(manual, headset) { fail("Timed-out headset request") })
            assertTrue(state.claim(peer))
        } finally { complete.countDown(); workers.shutdownNow() }
    }

    @Test fun failedRestoreAndInvalidSocketKeepReleaseForAValidRetry() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, true)
        val restore = state.beginRestore(peer)!!
        assertFalse(state.withCurrent(restore, a2dp, valid = { false }) { fail("Socket closed") })
        assertTrue(state.hasReleased(peer))
        val retry = state.beginRestore(peer)!!
        assertFalse(state.isCurrent(restore))
        assertTrue(state.withCurrent(retry, a2dp) { state.recordRestored(retry, a2dp) })
        assertFalse(state.hasReleased(peer))
    }

    @Test fun removingEarphonesCancelsPendingAutomaticPlayWithoutLosingReleaseOrManualConnect() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        state.recordReleased(release, a2dp, true)
        val restore = state.beginRestore(peer)!!
        state.cancelPendingRestore()
        assertFalse(state.isCurrent(restore))
        assertTrue(state.hasReleased(peer))
        val manual = state.beginExplicitConnect(peer)
        state.cancelPendingRestore()
        assertTrue(state.isCurrent(manual))
    }

    @Test fun revocationDoesNotWaitForEnteredBinderAndRejectsItsLateCompletion() {
        val state = policy()
        state.claim(peer)
        val restore = state.beginRestore(peer)!!
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val mutation = workers.submit<Boolean> {
                state.withCurrent(restore, a2dp) {
                    entered.countDown()
                    assertTrue(complete.await(5, TimeUnit.SECONDS))
                    state.recordRestored(restore, a2dp)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val revocation = workers.submit { state.revoke(lost = true) }
            revocation.get(500, TimeUnit.MILLISECONDS)
            assertFalse(state.isCurrent(restore))
            complete.countDown()
            assertFalse(mutation.get(5, TimeUnit.SECONDS))
            assertFalse(state.withCurrent(restore, headset) { fail("Revoked callback") })
        } finally { complete.countDown(); workers.shutdownNow() }
    }

    private fun pendingReleaseScenario(accepted: Boolean, invalidate: (AudioProfileOwnership) -> Unit = {}) {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val pending = workers.submit<Boolean> {
                state.withCurrent(release, a2dp) {
                    state.applyReleasePolicy(release, a2dp) {
                        entered.countDown()
                        assertTrue(complete.await(3, TimeUnit.SECONDS))
                        accepted
                    }
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val restore = state.beginRestore(peer)!!
            assertEquals(setOf(a2dp), restore.profiles)
            assertEquals(setOf(a2dp), restore.releasedProfiles)
            assertFalse(state.hasReleased(peer)) // A pending write grants nothing yet.
            invalidate(state)
            val restoreRan = java.util.concurrent.atomic.AtomicBoolean()
            val resumed = workers.submit<Boolean> {
                state.withCurrent(restore, a2dp) {
                    restoreRan.set(true)
                    state.recordRestored(restore, a2dp)
                }
            }
            assertFalse(restoreRan.get())
            complete.countDown()
            assertFalse(pending.get(1, TimeUnit.SECONDS))
            val mayRestore = accepted && state.isCurrent(restore)
            assertEquals(mayRestore, resumed.get(1, TimeUnit.SECONDS))
            assertEquals(mayRestore, restoreRan.get())
            assertFalse(state.hasReleased(peer))
        } finally { complete.countDown(); workers.shutdownNow() }
    }

    @Test fun returningDuringAcceptedPolicyWriteRestoresOnlyAfterItsResult() = pendingReleaseScenario(true)
    @Test fun returningDuringRejectedPolicyWriteCannotClaimAudio() = pendingReleaseScenario(false)
    @Test fun ownershipLossDuringEnteredReleaseCannotAuthorizeReconnect() = pendingReleaseScenario(true) { it.revoke(true) }
    @Test fun peerChangeAndReturnCannotBorrowAnEnteredOldRelease() = pendingReleaseScenario(true) {
        it.select("11:22:33:44:55:66"); it.select(peer)
    }

    @Test fun policyWriteFailureClearsPendingGrant() {
        val state = policy()
        val release = state.beginRelease(peer, true)!!
        assertThrows(IllegalStateException::class.java) {
            state.applyReleasePolicy(release, a2dp) { throw IllegalStateException("Binder failure") }
        }
        assertNull(state.beginRestore(peer))
    }

    @Test fun selectionReturnsWhileBinderIsBlockedAndInvalidatesQueuedOldProfile() {
        val state = policy()
        val ticket = state.beginExplicitConnect(peer)
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val blocked = workers.submit<Boolean> {
                state.withCurrent(ticket, a2dp) { entered.countDown(); assertTrue(complete.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.submit { state.select("11:22:33:44:55:66") }.get(500, TimeUnit.MILLISECONDS)
            assertFalse(state.isCurrent(ticket))
            complete.countDown()
            assertFalse(blocked.get(1, TimeUnit.SECONDS))
            assertFalse(state.withCurrent(ticket, headset) { fail("Old headset callback") })
        } finally { complete.countDown(); workers.shutdownNow() }
    }

    @Test fun invalidatedLookupNeverEntersOrRegistersAPolicyWrite() {
        val state = policy()
        val ticket = state.beginRelease(peer, true)!!
        state.beginRestore(peer)
        assertFalse(state.applyReleasePolicy(ticket, a2dp) { fail("No policy entry after query became stale"); true })
        assertNull(state.beginRestore(peer))
    }

    @Test fun socketLossKeepsAcceptedEnteredReleaseForLaterValidReconnect() {
        val state = policy()
        val ticket = state.beginRelease(peer, true)!!
        assertTrue(state.applyReleasePolicy(ticket, a2dp) { state.socketClosed(); true })
        assertTrue(state.hasReleased(peer))
        assertEquals(setOf(a2dp), state.beginRestore(peer)!!.releasedProfiles)
    }

    @Test fun explicitConnectSupersedesLateReleaseWithoutKeepingAutomaticGrant() {
        val state = policy()
        val ticket = state.beginRelease(peer, true)!!
        assertTrue(state.applyReleasePolicy(ticket, a2dp) { state.beginExplicitConnect(peer); true })
        assertFalse(state.hasReleased(peer))
        assertNull(state.beginRestore(peer))
    }
}
