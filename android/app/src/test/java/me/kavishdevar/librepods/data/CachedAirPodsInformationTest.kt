package me.kavishdevar.librepods.data

import me.kavishdevar.librepods.bluetooth.shouldConnectAtt
import org.junit.Assert.*
import org.junit.Test

class CachedAirPodsInformationTest {
    private val peer = "AA:BB:CC:DD:EE:FF"
    private fun values(model: String = "A3531") = mutableMapOf<String, Any>(
        "mac_address" to peer, "airpods_model_address" to peer.lowercase(),
        "airpods_model_number" to model, "name" to "My AirPods",
        "airpods_serial_number" to "case", "airpods_left_serial_number" to "left",
        "airpods_right_serial_number" to "right", "airpods_version1" to "v1",
        "airpods_version2" to "v2", "airpods_version3" to "v3"
    )
    @Test fun bothAirPods5VariantsRestoreTheirOwnedIdentityAndFirmware() {
        for (model in listOf("A3531", "A3439")) {
            val info = cachedAirPodsInformation(values(model))!!
            assertEquals(batteryHistoryIdentity(peer), info.owner)
            assertEquals("My AirPods", info.instance.name)
            assertEquals(model, info.instance.actualModelNumber)
            assertEquals("case", info.instance.serialNumber)
            assertEquals("left", info.instance.leftSerialNumber)
            assertEquals("right", info.instance.rightSerialNumber)
            assertEquals("v3", info.instance.version3)
            assertFalse(shouldConnectAtt(info.instance.model, true))
        }
    }
    @Test fun anotherDeviceMissingOwnerOrInvalidModelCannotSupplyInformation() {
        for (changed in listOf(
            "mac_address" to "11:22:33:44:55:66", "mac_address" to "",
            "airpods_model_address" to "", "airpods_model_address" to "not-an-address",
            "airpods_model_number" to "unknown"
        )) assertNull(cachedAirPodsInformation(values().apply { put(changed.first, changed.second) }))
        assertNull(cachedAirPodsInformation(values().apply { remove("airpods_model_address") }))
    }
    @Test fun everyKnownModelAliasKeepsItsActualNumberAndCapabilities() {
        for (model in AirPodsModels.models) for (number in model.modelNumber) {
            val info = cachedAirPodsInformation(values(number))!!
            assertSame(model, info.instance.model)
            assertEquals(number, info.instance.actualModelNumber)
            assertEquals(model.capabilities, ownedListeningCapabilities(number, peer, peer))
        }
    }
    @Test fun cachedFieldsAreAnImmutableSnapshot() {
        val source = values()
        val info = cachedAirPodsInformation(source)!!
        source["airpods_serial_number"] = "replacement"
        source["airpods_model_number"] = "A2084"
        source["mac_address"] = "11:22:33:44:55:66"
        assertEquals("case", info.instance.serialNumber)
        assertEquals("A3531", info.instance.actualModelNumber)
        assertNull(cachedAirPodsInformation(source))
    }
    @Test fun missingOptionalFieldsDoNotInventAnotherDevicesSerialOrVersion() {
        val source = values().apply {
            remove("name"); remove("airpods_serial_number"); remove("airpods_version1")
        }
        val info = cachedAirPodsInformation(source)!!
        assertEquals("AirPods", info.instance.name)
        assertEquals("", info.instance.serialNumber)
        assertEquals("", info.instance.version1)
    }
}
