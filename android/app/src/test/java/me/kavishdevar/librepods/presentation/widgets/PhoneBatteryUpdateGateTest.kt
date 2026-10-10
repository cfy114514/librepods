package me.kavishdevar.librepods.presentation.widgets

import org.junit.Assert.*
import org.junit.Test

class PhoneBatteryUpdateGateTest {
    @Test fun unchangedDisplayFromRepeatedBatteryBroadcastsDoesNotRefresh() {
        val gate = PhoneBatteryUpdateGate()
        assertTrue(gate.changed(65, 100, false))
        repeat(100_000) { assertFalse(gate.changed(65, 100, false)) }
        assertTrue(gate.changed(64, 100, false))
        assertTrue(gate.changed(64, 100, true))
        assertFalse(gate.changed(64, 100, true))
    }

    @Test fun validZeroAndUnknownAreDistinctAndChargingStillRefreshesUnknown() {
        val gate = PhoneBatteryUpdateGate()
        assertTrue(gate.changed(-1, 100, false))
        assertFalse(gate.changed(127, 100, false))
        assertTrue(gate.changed(0, 100, false))
        assertTrue(gate.changed(0, 0, false))
        assertTrue(gate.changed(-1, 100, true))
    }

    @Test fun scaleNormalizationDoesNotOverflowAndResetRefreshesStickyState() {
        val gate = PhoneBatteryUpdateGate()
        assertTrue(gate.changed(130, 200, true))
        assertFalse(gate.changed(65, 100, true))
        assertTrue(gate.changed(Int.MAX_VALUE, Int.MAX_VALUE, true))
        assertFalse(gate.changed(100, 100, true))
        gate.reset()
        assertTrue(gate.changed(100, 100, true))
    }
}
