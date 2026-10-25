package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class BatterySessionRepositoryTest {
    private val peer = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"
    private var now = 1_000L
    private val repository = BatterySessionRepository { now }
    private val lease = repository.claim()
    private val connection = requireNotNull(lease.connect(peer))
    private fun left(level: Int, status: Int = BatteryStatus.NOT_CHARGING) = Battery(BatteryComponent.LEFT, level, status)
    private fun right(level: Int, status: Int = BatteryStatus.NOT_CHARGING) = Battery(BatteryComponent.RIGHT, level, status)
    private fun record(vararg readings: Battery): Boolean = connection.recordReceived(readings.toList(), now)
    private fun snapshot() = repository.snapshots.value

    @Test fun actualReportsAreSeparateAndOmittedOrCaseReportsDoNotRefreshAnEarbud() {
        assertTrue(record(left(90), right(80), Battery(BatteryComponent.CASE, 70, BatteryStatus.NOT_CHARGING)))
        val first = snapshot()
        now += 60_000
        assertTrue(record(right(79)))
        assertEquals(first.left, snapshot().left)
        assertEquals(79, snapshot().right.level)
        assertFalse(record(Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)))
        assertEquals(1, snapshot().left.observations.size)
        assertEquals(2, snapshot().right.observations.size)
    }

    @Test fun zeroIsRealButInvalidReportsAndDuplicatesCannotKeepCachedPercentagesLive() {
        assertTrue(record(left(0), right(80)))
        assertEquals(0, snapshot().left.level)
        now++
        assertTrue(record(left(127), right(79), right(20)))
        assertNull(snapshot().left.level)
        assertNull(snapshot().right.level)
        assertEquals(BatterySessionPartState.UNAVAILABLE, snapshot().left.state)
        assertEquals(BatterySessionPartState.UNAVAILABLE, snapshot().right.state)
        now++
        record(left(50, 99), right(50, BatteryStatus.DISCONNECTED))
        assertEquals(BatterySessionPartState.UNAVAILABLE, snapshot().left.state)
        assertEquals(BatterySessionPartState.UNAVAILABLE, snapshot().right.state)
    }

    @Test fun chargingOptimizedChargingUpwardJumpsAndLargeFallsBreakOnlyTheirSide() {
        record(left(90), right(80))
        val original = snapshot()
        now += 60_000
        record(left(89, BatteryStatus.CHARGING), right(79))
        assertEquals(BatterySessionPartState.CHARGING, snapshot().left.state)
        assertNotEquals(original.left.segment, snapshot().left.segment)
        assertEquals(original.right.segment, snapshot().right.segment)
        now += 60_000
        record(left(89, BatteryStatus.OPTIMIZED_CHARGING), right(80))
        assertEquals(BatterySessionPartState.CHARGING, snapshot().left.state)
        assertNotEquals(original.right.segment, snapshot().right.segment)
        val chargingSegment = snapshot().left.segment
        now += 60_000
        record(left(88), right(60))
        assertNotEquals(chargingSegment, snapshot().left.segment)
        assertEquals(BatterySessionInterruption.LEVEL_CHANGE, snapshot().right.interruption)
        assertEquals(1, snapshot().left.observations.count { it.segment == snapshot().left.segment })
    }

    @Test fun exactGapBoundaryContinuesButLargerGapStartsFresh() {
        record(left(90))
        val original = snapshot().left.segment
        now += BATTERY_SESSION_MAX_GAP_MS
        record(left(89))
        assertEquals(original, snapshot().left.segment)
        now += BATTERY_SESSION_MAX_GAP_MS + 1
        record(left(88))
        assertNotEquals(original, snapshot().left.segment)
        assertEquals(BatterySessionInterruption.GAP, snapshot().left.interruption)
    }

    @Test fun futurePreconnectionOldAndOutOfOrderCallbacksAreIgnoredWithoutRefreshing() {
        record(left(90))
        val initial = snapshot()
        assertFalse(connection.recordReceived(listOf(left(1)), now + 1))
        assertFalse(connection.recordReceived(listOf(left(1)), 0))
        now += BATTERY_SESSION_MAX_DELIVERY_AGE_MS + 1
        assertFalse(connection.recordReceived(listOf(left(1)), 1_000))
        assertEquals(initial, snapshot())
        assertTrue(record(left(89)))
        val recent = snapshot()
        assertFalse(connection.recordReceived(listOf(left(1)), now - 1))
        assertFalse(record(left(1)))
        assertEquals(recent, snapshot())
    }

    @Test fun deliveryAgeBoundaryIsInclusive() {
        val received = now
        now += BATTERY_SESSION_MAX_DELIVERY_AGE_MS
        assertTrue(connection.recordReceived(listOf(left(90)), received))
        assertEquals(received, snapshot().left.lastReceivedAtElapsedMs)
    }

    @Test fun samePeerReconnectPeerSwitchAndOwnerReplacementFenceOldHandles() {
        record(left(90))
        val reconnect = requireNotNull(lease.connect(peer.lowercase()))
        assertTrue(snapshot().left.observations.isEmpty())
        assertFalse(record(left(1)))
        connection.close()
        assertTrue(snapshot().connected)
        assertTrue(reconnect.recordReceived(listOf(left(80)), now))
        val switched = requireNotNull(lease.connect(other))
        reconnect.close()
        assertFalse(reconnect.recordReceived(listOf(left(1)), now))
        assertEquals(other, snapshot().address)
        val nextOwner = repository.claim()
        val next = requireNotNull(nextOwner.connect(peer))
        lease.close()
        switched.close()
        assertFalse(switched.recordReceived(listOf(left(1)), now))
        assertNull(lease.connect(other))
        assertTrue(next.recordReceived(listOf(left(70)), now))
        assertEquals(70, snapshot().left.level)
        next.close()
        assertFalse(snapshot().connected)
        assertFalse(next.recordReceived(listOf(left(1)), now))
        nextOwner.close()
    }

    @Test fun invalidSelectionRetiresThePriorConnection() {
        record(left(90))
        assertNull(lease.connect("not a peer"))
        assertFalse(snapshot().connected)
        assertEquals("", snapshot().address)
        assertFalse(record(left(1)))
    }

    @Test fun rollbackClearsTimeSeriesAndRequiresNewObservations() {
        record(left(90))
        now += 60_000
        record(left(89))
        now = 50
        assertFalse(record(left(88)))
        assertTrue(snapshot().left.observations.isEmpty())
        assertEquals(BatterySessionInterruption.CLOCK, snapshot().left.interruption)
        now++
        assertTrue(record(left(87)))
        assertEquals(listOf(87), snapshot().left.observations.map { it.level })
    }

    @Test fun highRateUpdatesAndLongSessionsHaveBoundedIndependentHistories() {
        repeat(10_000) { index ->
            now++
            record(left(50 + index % 2), right(80))
        }
        assertEquals(BATTERY_SESSION_MAX_OBSERVATIONS, snapshot().left.observations.size)
        assertEquals(1, snapshot().right.observations.size)
        repeat(400) {
            now += 60_000
            record(left(50), right(80))
        }
        for (side in listOf(snapshot().left, snapshot().right)) {
            assertTrue(side.observations.size <= BATTERY_SESSION_MAX_OBSERVATIONS)
            assertTrue(side.observations.all { now - it.receivedAtElapsedMs <= BATTERY_SESSION_WINDOW_MS })
        }
    }

    @Test fun unchangedReportsAreThinnedWithoutLosingTheActualLatestReceiptTime() {
        record(left(90))
        val first = snapshot()
        now += BATTERY_SESSION_SAMPLE_INTERVAL_MS - 1
        record(left(90))
        assertEquals(first.left.observations, snapshot().left.observations)
        assertEquals(now, snapshot().left.lastReceivedAtElapsedMs)
        now++
        record(left(90))
        assertEquals(2, snapshot().left.observations.size)
        assertEquals(1, first.left.observations.size)
    }
}
