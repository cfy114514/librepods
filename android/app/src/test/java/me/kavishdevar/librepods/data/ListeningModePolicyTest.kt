package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class ListeningModePolicyTest {
    @Test fun proOneDoesNotCycleIntoUnsupportedAdaptiveMode() {
        val modes = availableListeningModes(AirPodsPro1().capabilities, true)
        assertEquals(listOf(1, 3, 2), modes)
        assertEquals(2, nextListeningMode(3, modes))
        assertEquals(1, nextListeningMode(2, modes))
    }
    @Test fun bothAirPodsFiveCaseVariantsRetainAllTheirListedModes() {
        for (model in listOf(AirPods5(), AirPods5Wireless())) {
            assertEquals(listOf(1, 3, 4, 2), availableListeningModes(model.capabilities, true))
            assertEquals(listOf(3, 4, 2), availableListeningModes(model.capabilities, false))
        }
    }
    @Test fun standardModelsAndUnresolvedModelsDoNotAuthorizeNoiseControls() {
        assertTrue(availableListeningModes(AirPods3().capabilities, true).isEmpty())
        assertTrue(availableListeningModes(null, true).isEmpty())
        assertNull(nextListeningMode(3, emptyList()))
    }
    @Test fun cachedModelMustBelongToTheCurrentSelectedPeer() {
        assertEquals(AirPodsPro1().capabilities, ownedListeningCapabilities("A2084", "aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF"))
        assertNull(ownedListeningCapabilities("A2084", "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"))
        assertNull(ownedListeningCapabilities("A2084", "", "AA:BB:CC:DD:EE:FF"))
        assertNull(ownedListeningCapabilities("unknown", "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"))
    }
    @Test fun cyclesStayWithinSupportedModesForEveryKnownModelAndOffPreference() {
        for (model in AirPodsModels.models) for (off in listOf(false, true)) {
            val modes = availableListeningModes(model.capabilities, off)
            for (start in -1..5) {
                var mode = start
                repeat(20) {
                    val next = nextListeningMode(mode, modes)
                    if (modes.isEmpty()) assertNull(next) else {
                        assertTrue(next in modes); mode = next!!
                    }
                }
            }
        }
    }
}
