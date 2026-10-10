package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class TakeoverPacketsTest {
    private val self = "AA:BB:CC:DD:EE:FF"
    private val other = "10:20:30:40:50:60"
    private val third = "11:22:33:44:55:66"
    private fun manager(vararg hosts: String) = AACPManager().apply {
        javaClass.getDeclaredField("connectedDevices").apply { isAccessible = true }.set(this,
            hosts.map { AACPManager.Companion.ConnectedDevice(it, 0, 0, null) })
    }
    private fun owns(manager: AACPManager) = manager.createDataPacket(manager.createControlCommandPacket(
        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, byteArrayOf(1)))

    @Test fun normalTakeoverKeepsProtocolOrderAndAddressesEveryOtherHost() {
        val manager = manager(self, other, third)
        val packets = manager.createTakeoverPackets(self, false)!!
        assertEquals(5, packets.size)
        assertArrayEquals(owns(manager), packets[0])
        assertArrayEquals(manager.createDataPacket(manager.createMediaInformationPacket(self, other, false)), packets[1])
        assertArrayEquals(manager.createDataPacket(manager.createSmartRoutingShowUIPacket(other)), packets[2])
        assertArrayEquals(manager.createDataPacket(manager.createHijackRequestPacket(other)), packets[3])
        assertArrayEquals(manager.createDataPacket(manager.createHijackRequestPacket(third)), packets[4])
    }

    @Test fun reverseIsOneReverseSequenceWithoutAnAdditionalNormalHijack() {
        val manager = manager(other, third)
        val packets = manager.createTakeoverPackets(self, true)!!
        assertEquals(4, packets.size)
        assertArrayEquals(owns(manager), packets[0])
        assertArrayEquals(manager.createDataPacket(manager.createMediaInformationPacket(self, other, false)), packets[1])
        assertArrayEquals(manager.createDataPacket(manager.createHijackReversedPacket(other)), packets[2])
        assertArrayEquals(manager.createDataPacket(manager.createHijackReversedPacket(third)), packets[3])
    }

    @Test fun malformedLocalAddressCannotProduceEvenAnOwnershipPacket() {
        assertNull(manager(other).createTakeoverPackets("invalid", false))
        assertNull(manager(other).createTakeoverPackets("", true))
    }

    @Test fun hostSnapshotNormalizesSkipsMalformedAndDeduplicatesWithoutTargetingSelf() {
        val manager = manager(self.lowercase(), "invalid", other.lowercase(), other, third)
        val packets = manager.createTakeoverPackets(self.lowercase(), false)!!
        assertEquals(5, packets.size)
        assertArrayEquals(manager.createDataPacket(manager.createHijackRequestPacket(other)), packets[3])
        assertArrayEquals(manager.createDataPacket(manager.createHijackRequestPacket(third)), packets[4])
        manager.resetDeviceState()
        assertEquals(5, packets.size)
        val next = manager.createTakeoverPackets(self, false)!!
        assertEquals(1, next.size)
        assertArrayEquals(owns(manager), next.single())
    }

    @Test fun noOtherHostDoesNotInventPrivateRoutingMessages() {
        val manager = manager(self.lowercase(), "invalid")
        for (reverse in listOf(true, false)) {
            val packets = manager.createTakeoverPackets(self, reverse)!!
            assertEquals(1, packets.size)
            assertArrayEquals(owns(manager), packets.single())
        }
    }
}
