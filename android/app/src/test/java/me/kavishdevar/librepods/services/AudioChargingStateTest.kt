package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class AudioChargingStateTest {
    @Test fun repeatedBatteryReportsDoNotRepeatAudioProfileOperations() {
        val state = AudioChargingState()
        assertFalse(state.changed(null, false))
        assertTrue(state.changed("device-a", false))
        repeat(100_000) { assertFalse(state.changed("device-a", false)) }
        assertTrue(state.changed("device-a", true))
        repeat(100_000) { assertFalse(state.changed("device-a", true)) }
        assertTrue(state.changed("device-a", false))
    }

    @Test fun newDeviceAndNewConnectionApplyTheirOwnInitialPolicy() {
        val state = AudioChargingState()
        assertTrue(state.changed("device-a", true))
        assertTrue(state.changed("device-b", true))
        state.reset()
        assertTrue(state.changed("device-b", true))
    }
}
