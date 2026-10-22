package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLidStateTest {
    @Test fun unchangedOpenAdvertisementReopensAfterTimeout() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertTrue(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 1_000, timeoutMillis = 2_500))

        // The address remains cached for 15 seconds, with its previous lid value still true.
        assertTrue(state.observe(lidOpen = true, timestampNanos = 11_000_000_000))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 12_000_000_000))
    }

    @Test fun cachedAddressCanCloseTheLidAfterAnotherAddressOpenedIt() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = false, timestampNanos = 1_000_000_000))
        assertTrue(state.observe(lidOpen = true, timestampNanos = 2_000_000_000))

        // The first address still has a closed advertisement cached, but reports it again later.
        assertTrue(state.observe(lidOpen = false, timestampNanos = 3_000_000_000))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 4_000_000_000))
    }

    @Test fun aNewAddressDoesNotRepeatTheSameGlobalLidState() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 2_000_000_000))
        assertTrue(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 2_000, timeoutMillis = 2_500))
        assertTrue(state.observe(lidOpen = true, timestampNanos = 11_000_000_000))
    }

    @Test fun timeoutIsStrictlyAfterTheExistingThresholdAndClosesOnlyOnce() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertFalse(state.closeIfTimedOut(now = 3_500, lastBroadcastTime = 1_000, timeoutMillis = 2_500))
        assertTrue(state.closeIfTimedOut(now = 3_501, lastBroadcastTime = 1_000, timeoutMillis = 2_500))
        assertFalse(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 1_000, timeoutMillis = 2_500))
    }

    @Test fun freshBroadcastPreventsTimeoutWithoutRepeatingTheOpenEvent() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 9_000_000_000))
        assertFalse(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 9_000, timeoutMillis = 2_500))
        assertTrue(state.closeIfTimedOut(now = 11_501, lastBroadcastTime = 9_000, timeoutMillis = 2_500))
    }

    @Test fun unknownAndClosedStatesDoNotEmitTimeoutClosures() {
        val state = BleLidState()
        assertFalse(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 0, timeoutMillis = 2_500))
        assertTrue(state.observe(lidOpen = false, timestampNanos = 11_000_000_000))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 12_000_000_000))
        assertFalse(state.closeIfTimedOut(now = 20_000, lastBroadcastTime = 12_000, timeoutMillis = 2_500))
    }

    @Test fun resetForgetsThePreviousOwnerLidStateAndTimestamp() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 10_000_000_000))
        state.reset()
        assertFalse(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 0, timeoutMillis = 2_500))
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
    }

    @Test fun olderAddressInADescendingBatchCannotOverrideANewerLidObservation() {
        val state = BleLidState()
        // Both addresses previously advertised closed. The batch visits newer B, then older A.
        assertTrue(state.observe(lidOpen = false, timestampNanos = 1_000_000_000))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 2_000_000_000))
        assertTrue(state.observe(lidOpen = true, timestampNanos = 4_000_000_000))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 3_000_000_000))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 5_000_000_000))
    }

    @Test fun equalTimestampCannotChangeTheLidOrReopenItAfterTimeout() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 1_000_000_000))
        assertTrue(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 1_000, timeoutMillis = 2_500))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertTrue(state.observe(lidOpen = true, timestampNanos = 11_000_000_000))
    }

    @Test fun anOlderOpenAdvertisementCannotReopenAfterTimeout() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 2_000_000_000))
        assertTrue(state.closeIfTimedOut(now = 10_000, lastBroadcastTime = 2_000, timeoutMillis = 2_500))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertTrue(state.observe(lidOpen = true, timestampNanos = 11_000_000_000))
    }

    @Test fun unchangedNewerAdvertisementStillAdvancesTheObservationTime() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 1_000_000_000))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 3_000_000_000))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 2_000_000_000))
        assertFalse(state.observe(lidOpen = true, timestampNanos = 4_000_000_000))
    }

    @Test fun theFirstTimestampMayBeZero() {
        val state = BleLidState()
        assertTrue(state.observe(lidOpen = true, timestampNanos = 0))
        assertFalse(state.observe(lidOpen = false, timestampNanos = 0))
        assertTrue(state.observe(lidOpen = false, timestampNanos = 1))
    }
}
