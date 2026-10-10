package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class ListeningModeStoreTest {
    private val selected = "AA:BB:CC:DD:EE:FF"
    private val other = "11:22:33:44:55:66"
    private fun values(model: String, owner: String = selected, off: Boolean = false) = mapOf(
        "mac_address" to selected, "airpods_model_address" to selected,
        "airpods_model_number" to model, OFF_LISTENING_MODE_OWNER to owner,
        "off_listening_mode" to off
    )
    @Test fun knownOwnerPreservesEnabledAndDisabledValuesWithCanonicalAddresses() {
        assertFalse(ownedOffListeningMode(false, selected.lowercase(), selected))
        assertTrue(ownedOffListeningMode(true, selected, selected.lowercase()))
    }
    @Test fun missingUnknownAndOtherPeerOwnersDoNotReuseTheirOption() {
        for (owner in listOf("", "invalid", other)) assertTrue(ownedOffListeningMode(false, owner, selected))
        assertTrue(ownedOffListeningMode(false, selected, ""))
        assertTrue(ownedOffListeningMode(null, selected, selected))
    }
    @Test fun bothAirPodsFiveVariantsUseOnlyTheirOwnedOption() {
        for (model in listOf("A3531", "A3439")) {
            assertEquals(listOf(3, 4, 2), cachedListeningModes(values(model)))
            assertEquals(listOf(1, 3, 4, 2), cachedListeningModes(values(model, other)))
            assertEquals(listOf(1, 3, 4, 2), cachedListeningModes(values(model, off = true)))
        }
    }
    @Test fun snapshotRequiresModelOwnershipEvenWhenOffOptionBelongsToPeer() {
        assertTrue(cachedListeningModes(values("A3531") + ("airpods_model_address" to other)).isEmpty())
        assertTrue(cachedListeningModes(values("unknown")).isEmpty())
    }
    @Test fun missingOrWrongTypedLegacyValuesCannotHideOffOnNewPeers() {
        assertTrue(cachedOffListeningMode(values("A3531") - OFF_LISTENING_MODE_OWNER))
        assertTrue(cachedOffListeningMode(values("A3531") + ("off_listening_mode" to "false")))
        assertTrue(cachedOffListeningMode(emptyMap<String, Any>()))
    }
}
