package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class SystemBatteryLevelTest {
    @Test fun liveZeroAndOneValidBudArePreservedWithoutInventingDefaults() {
        assertEquals(0, systemHeadsetBatteryLevel(listOf(
            Battery(BatteryComponent.LEFT, 0, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 80, BatteryStatus.DISCONNECTED))))
        assertEquals(43, systemHeadsetBatteryLevel(listOf(
            Battery(BatteryComponent.RIGHT, 43, BatteryStatus.OPTIMIZED_CHARGING))))
        assertNull(systemHeadsetBatteryLevel(emptyList()))
    }

    @Test fun staleInvalidAndCaseOnlyReadingsCannotBecomeSystemHeadsetLevels() {
        assertNull(systemHeadsetBatteryLevel(listOf(
            Battery(BatteryComponent.LEFT, 58, BatteryStatus.DISCONNECTED),
            Battery(BatteryComponent.RIGHT, 127, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, 100, BatteryStatus.CHARGING))))
        assertNull(systemHeadsetBatteryLevel(listOf(Battery(BatteryComponent.LEFT, 60, 127))))
        assertEquals(58, systemHeadsetBatteryLevel(listOf(
            Battery(BatteryComponent.LEFT, 65, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 58, BatteryStatus.CHARGING),
            Battery(BatteryComponent.CASE, 1, BatteryStatus.NOT_CHARGING))))
    }

    @Test fun appleVendorIndicatorMatchesAospDecoderAndItsTenPercentResolution() {
        for (percent in 1..100) {
            val indicator = appleHeadsetBatteryIndicator(percent)!!
            assertTrue(indicator in 0..9)
            val decodedByAosp = (indicator + 1) * 10
            assertTrue(decodedByAosp in percent..(percent + 9))
            if (percent % 10 == 0) assertEquals(percent, decodedByAosp)
        }
        assertEquals(0, appleHeadsetBatteryIndicator(10))
        assertEquals(4, appleHeadsetBatteryIndicator(50))
        assertEquals(9, appleHeadsetBatteryIndicator(100))
    }

    @Test fun zeroAndInvalidPercentagesAreNeverMisrepresentedByAppleVendorEvents() {
        listOf(-1, 0, 101, 127, 255).forEach { assertNull(appleHeadsetBatteryIndicator(it)) }
    }
}
