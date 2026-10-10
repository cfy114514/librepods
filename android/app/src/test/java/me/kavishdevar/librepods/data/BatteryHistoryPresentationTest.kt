package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class BatteryHistoryPresentationTest {
    private val peer = "AA:BB:CC:DD:EE:FF"
    private val now = 10_000_000_000L
    private fun history(level: Int = 80, seen: Long = now - 10_000) =
        BatteryHistorySnapshot(peer, mapOf(4 to BatteryHistoryReading(level, seen)))
    private fun display(value: BatteryHistorySnapshot, time: Long = now, days: Int = 7,
                        enabled: Boolean = true, address: String = peer, preview: Boolean = false) =
        batteryHistoryPresentation(address, value, enabled, days, time, !preview) { _, at -> "minute:${at / 60_000}" }

    @Test fun differingOriginalTimesWithIdenticalVisibleTextHaveEqualPresentations() {
        val minute = now / 60_000 * 60_000 - 60_000
        val initial = display(history(seen = minute + 1))
        repeat(1000) { assertEquals(initial, display(history(seen = minute + it + 2))) }
        assertEquals(minute + 1001, history(seen = minute + 1001).readings[4]?.observedAt)
    }

    @Test fun aLevelOrFormattedTimeChangeCreatesANewPresentation() {
        val initial = display(history())
        assertNotEquals(initial, display(history(level = 79)))
        assertNotEquals(initial, display(history(seen = now - 120_000)))
    }

    @Test fun eligibilityUsesOriginalMillisecondsAtTheExactExpiryBoundary() {
        val observed = now - 86_400_000L
        assertEquals(80, display(history(seen = observed), days = 1).readings[0].level)
        assertNull(display(history(seen = observed), time = now + 1, days = 1).readings[0].level)
        assertEquals(80, display(history(seen = observed), time = now + 1, days = 30).readings[0].level)
    }

    @Test fun futureTimestampsAndUnknownReadingsNeverBecomeFormattedHistory() {
        val future = display(history(seen = now + 1))
        assertNull(future.readings[0].level); assertNull(future.oldestReportedAt)
        assertNull(display(history(level = 127)).readings[0].level)
        assertEquals(0, display(history(level = 0)).readings[0].level)
    }

    @Test fun disabledHistoryAndOtherPeersDoNotReuseAnyVisibleReading() {
        assertNull(display(history(), enabled = false).oldestReportedAt)
        assertTrue(display(history(), address = "11:22:33:44:55:66").readings.all { it.level == null })
        assertEquals(1, display(history(), days = -1).days)
        assertEquals(30, display(history(), days = 100).days)
    }

    @Test fun previewFormatsOnlyTheOldestEligibleObservation() {
        val value = BatteryHistorySnapshot(peer, mapOf(4 to BatteryHistoryReading(80, now - 10_000),
            2 to BatteryHistoryReading(70, now - 20_000), 8 to BatteryHistoryReading(60, now + 1)))
        val formatted = mutableListOf<Pair<Int, Long>>()
        val preview = batteryHistoryPresentation(peer, value, true, 7, now, false) { part, at ->
            formatted += part to at; "$part:$at"
        }
        assertEquals(listOf(2 to now - 20_000), formatted)
        assertEquals("2:${now - 20_000}", preview.oldestReportedAt)
        assertTrue(preview.readings.all { it.reportedAt == null })
        assertNull(preview.readings[2].level)
    }

    @Test fun detailReusesTheAlreadyFormattedOldestRow() {
        val value = BatteryHistorySnapshot(peer, mapOf(4 to BatteryHistoryReading(80, now - 10_000),
            2 to BatteryHistoryReading(70, now - 20_000)))
        var formats = 0
        val detailed = batteryHistoryPresentation(peer, value, true, 7, now) { part, at -> formats++; "$part:$at" }
        assertEquals(2, formats)
        assertEquals(detailed.readings[1].reportedAt, detailed.oldestReportedAt)
    }

    @Test fun formatterKeepsUnchangedPartsAndInvalidationRefreshesAllText() {
        var formats = 0; var prefix = "old"
        val formatter = HistoryTimeFormatter { formats++; "$prefix:$it" }
        repeat(1000) { formatter.format(4, it.toLong()); formatter.format(2, 1); formatter.format(8, 2) }
        assertEquals(1002, formats)
        assertEquals("old:1", formatter.format(2, 1))
        prefix = "new"; formatter.clear()
        assertEquals("new:1", formatter.format(2, 1)); assertEquals(1003, formats)
    }
}
