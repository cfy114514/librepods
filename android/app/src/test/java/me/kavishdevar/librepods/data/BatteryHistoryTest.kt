package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class BatteryHistoryTest {
    private val first = "AA:BB:CC:DD:EE:01"
    private val second = "AA:BB:CC:DD:EE:02"
    private val time = 1_000_000L
    private fun left(level: Int, status: Int = BatteryStatus.NOT_CHARGING) = Battery(BatteryComponent.LEFT, level, status)

    @Test fun validZeroIsDifferentFromNoReportAndSentinelDoesNotOverwriteHistory() {
        val initial = BatteryHistorySnapshot().merge(first, listOf(left(0)), time)
        val same = initial.merge(first, listOf(left(127), left(-1), left(20, BatteryStatus.DISCONNECTED), left(20, 99)), time + 10)
        assertEquals(BatteryHistoryReading(0, time), same.readings[BatteryComponent.LEFT])
        val display = displayBatteries(first, emptyList(), false, same, true, time + 10)
        assertEquals(0, display[0].level)
        assertTrue(display[0].historical)
        assertNull(display[1].level)
        assertNull(display[2].level)
    }

    @Test fun componentTimesAreIndependentAndUnknownCaseCannotRefreshItsTimestamp() {
        val initial = BatteryHistorySnapshot().merge(first,
            listOf(left(90), Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)), time)
        val update = initial.merge(first, listOf(left(89), Battery(BatteryComponent.CASE, 127, BatteryStatus.DISCONNECTED)), time + 1000)
        assertEquals(time + 1000, update.readings[BatteryComponent.LEFT]?.observedAt)
        assertEquals(time, update.readings[BatteryComponent.CASE]?.observedAt)
    }

    @Test fun newDeviceCannotInheritAnotherPairsComponents() {
        val initial = BatteryHistorySnapshot().merge(first, listOf(left(90)), time)
        assertTrue(displayBatteries(second, emptyList(), false, initial, true, time).all { it.level == null })
        val update = initial.merge(second, listOf(Battery(BatteryComponent.RIGHT, 80, BatteryStatus.CHARGING)), time + 1000)
        assertEquals(second, update.address)
        assertNull(update.readings[BatteryComponent.LEFT])
        assertEquals(80, update.readings[BatteryComponent.RIGHT]?.level)
        assertTrue(displayBatteries(first, emptyList(), false, update, true, time + 1000).all { it.level == null })
    }

    @Test fun missingLiveComponentUsesHistoryButNeverAnOldChargingIcon() {
        val history = BatteryHistorySnapshot().merge(first,
            listOf(left(90, BatteryStatus.CHARGING), Battery(BatteryComponent.CASE, 60, BatteryStatus.CHARGING)), time)
        val output = displayBatteries(first, listOf(left(89, BatteryStatus.CHARGING)), true, history, true, time + 100)
        assertEquals(89, output[0].level)
        assertTrue(output[0].charging)
        assertFalse(output[0].historical)
        assertEquals(60, output[2].level)
        assertTrue(output[2].historical)
        assertFalse(output[2].charging)
    }

    @Test fun disconnectionNeverTreatsTheLastLiveObjectsAsCurrent() {
        val live = listOf(left(90, BatteryStatus.CHARGING))
        assertTrue(displayBatteries(first, live, false, BatteryHistorySnapshot(), true, time).all { it.level == null })
        val history = BatteryHistorySnapshot().merge(first, live, time)
        val reading = displayBatteries(first, live, false, history, true, time + 1)[0]
        assertTrue(reading.historical)
        assertFalse(reading.charging)
    }

    @Test fun expirationDisabledHistoryAndFutureDatesCannotAppearAsKnown() {
        val history = BatteryHistorySnapshot().merge(first, listOf(left(90)), time)
        assertNotNull(displayBatteries(first, emptyList(), false, history, true, time + 100, maxAge = 100)[0].level)
        assertNull(displayBatteries(first, emptyList(), false, history, true, time + 101, maxAge = 100)[0].level)
        assertNull(displayBatteries(first, emptyList(), false, history, false, time)[0].level)
        assertNull(displayBatteries(first, emptyList(), false, history, true, time - 1)[0].level)
        assertNull(displayBatteries("", emptyList(), false, history, true, time)[0].level)
        val corrected = history.merge(first, listOf(left(89)), time - 10)
        assertEquals(time - 10, corrected.readings[BatteryComponent.LEFT]?.observedAt)
    }

    @Test fun repeatedReportsAreThrottledButChangesAndDisconnectFlushImmediately() {
        val persistence = BatteryHistoryPersistence()
        var history = BatteryHistorySnapshot()
        var saves = 0
        repeat(100_000) { elapsed ->
            history = history.merge(first, listOf(left(90)), time + elapsed)
            if (persistence.shouldSave(history, elapsed.toLong())) {
                saves++
                persistence.saved(history, elapsed.toLong())
            }
        }
        assertEquals(4, saves)
        assertTrue(persistence.shouldSave(history, 100_000, force = true))
        persistence.saved(history, 100_000)
        assertFalse(persistence.shouldSave(history, 100_001, force = true))
        history = history.merge(first, listOf(left(89)), time + 100_002)
        assertTrue(persistence.shouldSave(history, 100_002))
        assertTrue(persistence.shouldSave(history.merge(second, listOf(left(89)), time + 100_003), 100_003))
    }

    @Test fun bleReadingsRequireKeysBelongingToTheSelectedPair() {
        assertFalse(canRecordBleBattery(first, null, false, null))
        assertFalse(canRecordBleBattery(first, second, false, null))
        assertFalse(canRecordBleBattery(first, first, true, null))
        assertFalse(canRecordBleBattery(first, first, true, second))
        assertTrue(canRecordBleBattery(first.lowercase(), first, true, first))
        assertTrue(canRecordBleBattery(first, first, false, null))
    }
}
