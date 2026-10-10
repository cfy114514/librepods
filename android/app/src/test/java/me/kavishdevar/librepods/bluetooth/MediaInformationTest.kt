package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class MediaInformationTest {
    private val self = "AA:BB:CC:DD:EE:FF"
    private val other = "10:20:30:40:50:60"
    private fun manager(vararg peers: String) = AACPManager().apply {
        // Exercise the packet factory with the same host snapshot the parser publishes.
        javaClass.getDeclaredField("connectedDevices").apply { isAccessible = true }.set(this,
            peers.map { AACPManager.Companion.ConnectedDevice(it, 0, 0, null) })
    }

    @Test fun normalizedSelfIsNeverChosenAsTheTargetHost() {
        val manager = manager(self.lowercase(), other)
        assertArrayEquals(manager.createDataPacket(manager.createMediaInformationPacket(self, other, true)),
            manager.createMediaInformationData(self.lowercase(), true))
    }

    @Test fun noOtherHostOrInvalidLocalAddressCannotProducePacket() {
        assertNull(manager().createMediaInformationData(self, true))
        assertNull(manager(self.lowercase()).createMediaInformationData(self, true))
        assertNull(manager(other).createMediaInformationData("invalid", true))
    }

    @Test fun malformedHostIsSkippedWithoutCrashingOrAddressingSelf() {
        val manager = manager("invalid", self, other.lowercase())
        assertArrayEquals(manager.createDataPacket(manager.createMediaInformationPacket(self, other, false)),
            manager.createMediaInformationData(self, false))
    }

    @Test fun changingStreamingStateKeepsExistingWireFormatAndFreshSnapshot() {
        val manager = manager(other)
        val playing = manager.createMediaInformationData(self, true)!!
        val stopped = manager.createMediaInformationData(self, false)!!
        assertFalse(playing.contentEquals(stopped))
        assertArrayEquals(manager.createDataPacket(manager.createMediaInformationPacket(self, other, false)), stopped)
        manager.resetDeviceState()
        assertNull(manager.createMediaInformationData(self, true))
    }
}
