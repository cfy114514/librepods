package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class BatterySessionPresentationTest {
    private val peer = "AA:BB:CC:DD:EE:01"
    private var now = 1_000L
    private val repository = BatterySessionRepository { now }
    private val lease = repository.claim()
    private val connection = requireNotNull(lease.connect(peer))
    private fun record(level: Int, status: Int = BatteryStatus.NOT_CHARGING) {
        assertTrue(connection.recordReceived(listOf(Battery(BatteryComponent.LEFT, level, status)), now))
    }
    private fun display(address: String = peer, time: Long = now) =
        batterySessionPresentation(address, repository.snapshots.value, time)
    private fun mature(start: Int = 90) {
        repeat(4) { index ->
            if (index > 0) now += 5 * 60_000
            record(start - index)
        }
    }

    @Test fun enoughIndependentDeclinesProduceClearlyTypedConservativeFiveMinuteEstimates() {
        mature()
        val side = display().left
        assertEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, side.state)
        assertEquals(255, side.roughRemainingMinutes)
        assertEquals(15, side.observedMinutes)
        assertEquals(listOf(90, 89, 88, 87), side.points.map { it.level })
        assertEquals(BatterySessionEstimateState.WAITING, display().right.state)
        // Unbuffered endpoint extrapolation would claim 435 minutes; the display must not.
        assertTrue(requireNotNull(side.roughRemainingMinutes) < 435)
    }

    @Test fun elapsedTimeAloneCannotInventObservationsOrAnEstimate() {
        record(90)
        val snapshot = repository.snapshots.value
        now += 4 * 60_000
        assertEquals(BatterySessionEstimateState.COLLECTING, display().left.state)
        assertNull(display().left.roughRemainingMinutes)
        assertEquals(snapshot, repository.snapshots.value)
        now += 2 * 60_000
        assertEquals(BatterySessionEstimateState.STALE, display().left.state)
        assertEquals(snapshot, repository.snapshots.value)
    }

    @Test fun allSampleDurationAndDeclineGatesAreNecessary() {
        repeat(4) { index ->
            if (index > 0) now += 60_000
            record(90 - index)
        }
        assertEquals(BatterySessionEstimateState.COLLECTING, display().left.state)
        repeat(4) {
            now += 5 * 60_000
            record(87)
        }
        // A fresh segment with many flat reports cannot become a runtime estimate.
        val fresh = requireNotNull(lease.connect(peer))
        repeat(5) {
            now += 5 * 60_000
            fresh.recordReceived(listOf(Battery(BatteryComponent.LEFT, 87, BatteryStatus.NOT_CHARGING)), now)
        }
        assertEquals(BatterySessionEstimateState.INSUFFICIENT_DRAIN, display().left.state)
        assertNull(display().left.roughRemainingMinutes)
    }

    @Test fun oneDropCannotPretendToBeThreeIndependentDeclines() {
        for (level in listOf(90, 87, 87, 87)) {
            record(level)
            now += 5 * 60_000
        }
        assertEquals(BatterySessionEstimateState.INSUFFICIENT_DRAIN, display().left.state)
    }

    @Test fun silenceExpiresAtTheExactBoundaryAndAgeNeverExtendsAnEstimate() {
        mature()
        val sampleTime = now
        now += 4 * 60_000
        assertEquals(250, display().left.roughRemainingMinutes)
        now = sampleTime + BATTERY_SESSION_MAX_GAP_MS
        assertEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, display().left.state)
        now++
        assertEquals(BatterySessionEstimateState.STALE, display().left.state)
        assertNull(display().left.roughRemainingMinutes)
        assertNull(display().left.level)
    }

    @Test fun staleOrDifferentOwnerChargingAndInvalidReadingsSuppressEstimates() {
        mature()
        assertEquals(BatterySessionEstimateState.DISCONNECTED, display("11:22:33:44:55:66").left.state)
        assertEquals(BatterySessionEstimateState.DISCONNECTED, display("").left.state)
        assertEquals(BatterySessionEstimateState.CLOCK_CHANGED, display(time = now - 1).left.state)
        now++
        record(87, BatteryStatus.CHARGING)
        assertEquals(BatterySessionEstimateState.CHARGING, display().left.state)
        assertNull(display().left.roughRemainingMinutes)
        now++
        record(127)
        assertEquals(BatterySessionEstimateState.UNAVAILABLE, display().left.state)
        connection.close()
        assertEquals(BatterySessionEstimateState.DISCONNECTED, display().left.state)
    }

    @Test fun resumedDischargeAndUpwardCorrectionsCannotReuseTheEarlierSlope() {
        mature()
        now++
        record(87, BatteryStatus.OPTIMIZED_CHARGING)
        now++
        record(87)
        assertEquals(BatterySessionEstimateState.COLLECTING, display().left.state)
        assertEquals(1, display().left.points.size)
        assertNull(display().left.roughRemainingMinutes)
        now++
        record(88)
        assertEquals(1, display().left.points.size)
        assertEquals(BatterySessionEstimateState.COLLECTING, display().left.state)
    }

    @Test fun realUnchangedReceiptCanEndATrendButAClockTickCannot() {
        mature()
        now += 1_000
        record(87)
        val series = repository.snapshots.value.left
        assertEquals(4, series.observations.size)
        assertEquals(5, display().left.points.size)
        assertEquals(now, display().left.points.last().receivedAtElapsedMs)
        now += 1_000
        assertEquals(series.lastReceivedAtElapsedMs, display().left.points.last().receivedAtElapsedMs)
    }

    @Test fun zeroAndVeryLargeMonotonicTimestampsStayFinite() {
        mature(start = 3)
        assertEquals(0, display().left.roughRemainingMinutes)
        now = Long.MAX_VALUE - BATTERY_SESSION_MIN_SPAN_MS
        val large = requireNotNull(lease.connect(peer))
        repeat(4) { index ->
            if (index > 0) now += 5 * 60_000
            assertTrue(large.recordReceived(listOf(Battery(BatteryComponent.LEFT, 90 - index, BatteryStatus.NOT_CHARGING)), now))
        }
        assertEquals(Long.MAX_VALUE, now)
        assertEquals(255, display().left.roughRemainingMinutes)
    }

    @Test fun fasterRecentDrainWinsOverTheLongSlowAverage() {
        repeat(13) { index ->
            if (index > 0) now += 5 * 60_000
            record(90 - index / 4)
        }
        repeat(3) { index ->
            now += 5 * 60_000
            record(84 - index * 3)
        }
        val estimate = requireNotNull(display().left.roughRemainingMinutes)
        assertEquals(105, estimate)
        assertTrue(estimate < 77 * 75 / 14)
    }
}
