package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class RtBuddyHeadTrackingTest {
    // Actual A3441 USB capture, sequence 127, command service 15 / 58-byte devmotion6 report.
    private val live = "04 00 04 00 17 00 00 00 10 00 44 00 08 7f 10 01 3a 3e 08 0f 1a 3a 01 80 ed 93 5e 12 01 00 00 03 00 83 1f 76 07 46 00 00 00 00 73 ae c0 42 7c fa 92 00 4c 00 fe ff ab 84 00 e5 73 0e e4 ff 21 00 19 00 43 02 f2 02 62 01 14 e8 84 3c a1 02 00 00"
        .split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun frame(payload: ByteArray): ByteArray = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0,
        (payload.size and 255).toByte(), (payload.size ushr 8).toByte()) + payload

    @Test fun capturedMotionAndSequenceVarintGrowthHaveIdenticalAxes() {
        val expected = RtBuddyHeadTracking.Motion(15, -20877, 17088, -1412, 76, -2)
        assertEquals(expected, RtBuddyHeadTracking.motion(live))
        val sequence128 = frame(byteArrayOf(8, 0x80.toByte(), 1) + live.copyOfRange(14, live.size))
        assertEquals(expected, RtBuddyHeadTracking.motion(sequence128))
        val sequence16384 = frame(byteArrayOf(8, 0x80.toByte(), 0x80.toByte(), 1) + live.copyOfRange(14, live.size))
        assertEquals(expected, RtBuddyHeadTracking.motion(sequence16384))
    }

    @Test fun acknowledgementsAndDescriptorsNeverBecomeMotion() {
        val ack = frame(byteArrayOf(8, 1, 0x10, 1, 0x4a, 2, 8, 15))
        assertTrue(hasCompleteAacpPayload(ack))
        assertNull(RtBuddyHeadTracking.motion(ack))
        val properties = byteArrayOf(16, 0, 0, 9) + "AccessoryService".toByteArray() +
            byteArrayOf(10, 0, 0, 9) + "devmotion6".toByteArray()
        for (id in listOf(6, 15, 16)) {
            val descriptor = byteArrayOf(8, id.toByte(), 0x12, properties.size.toByte()) + properties
            val packet = frame(byteArrayOf(8, 1, 0x10, 1, 0x2a, descriptor.size.toByte()) + descriptor)
            assertEquals(id, RtBuddyHeadTracking.discoverService(packet))
            assertNull(RtBuddyHeadTracking.motion(packet))
        }
    }

    @Test fun malformedOuterAndNestedLengthsAndUnexpectedReportFormatsAreRejected() {
        for (length in live.indices) assertNull(RtBuddyHeadTracking.motion(live.copyOf(length)))
        assertNull(RtBuddyHeadTracking.motion(live.copyOf().apply { this[10] = 1 }))
        assertNull(RtBuddyHeadTracking.motion(live.copyOf().apply { this[21] = 0x7f }))
        assertNull(RtBuddyHeadTracking.motion(live.copyOf().apply { this[31] = 2 }))
        assertNull(RtBuddyHeadTracking.motion(live.copyOf() + 0))
    }

    @Test fun dynamicallyDiscoveredStartAndStopUseTheSameServiceAndValidEnvelope() {
        for (service in listOf(6, 15, 16, 128, 255)) {
            val start = RtBuddyHeadTracking.settingPacket(service, true)
            val stop = RtBuddyHeadTracking.settingPacket(service, false)
            assertTrue(RtBuddyHeadTracking.complete(start))
            assertTrue(RtBuddyHeadTracking.complete(stop))
            assertArrayEquals(start.copyOfRange(0, start.size - 4), stop.copyOfRange(0, stop.size - 4))
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), stop.takeLast(4).toByteArray())
        }
        val manager = AACPManager()
        assertNull(manager.headTrackingService)
        manager.resetDeviceState()
        assertNull(manager.headTrackingService)
    }

    @Test fun discoveryIsSessionBoundAndOnlyMatchingMotionReachesTheCallback() {
        val properties = byteArrayOf(16, 0, 0, 9) + "AccessoryService".toByteArray() +
            byteArrayOf(10, 0, 0, 9) + "devmotion6".toByteArray()
        val descriptor = byteArrayOf(8, 15, 0x12, properties.size.toByte()) + properties
        val packet = frame(byteArrayOf(8, 1, 0x10, 1, 0x2a, descriptor.size.toByte()) + descriptor)
        val manager = AACPManager()
        var discoveries = 0
        var samples = 0
        manager.setPacketCallback(Proxy.newProxyInstance(AACPManager.PacketCallback::class.java.classLoader,
            arrayOf(AACPManager.PacketCallback::class.java)) { _, method, _ ->
            when (method.name) {
                "onHeadTrackingServiceDiscovered" -> discoveries++
                "onHeadTrackingReceived" -> samples++
            }
            null
        } as AACPManager.PacketCallback)
        val retired = manager.beginDeviceSession()
        val current = manager.beginDeviceSession()
        manager.receivePacket(packet, retired)
        manager.receivePacket(packet, current) { false }
        assertNull(manager.headTrackingService)
        manager.receivePacket(packet, current)
        manager.receivePacket(packet, current)
        assertEquals(15, manager.headTrackingService)
        assertEquals(1, discoveries)
        assertEquals(0, samples)
        manager.receivePacket(live, current)
        manager.receivePacket(live.copyOf().apply { this[19] = 16 }, current)
        assertEquals(1, samples)
        manager.beginDeviceSession()
        assertNull(manager.headTrackingService)
        manager.receivePacket(live, current)
        assertEquals(1, samples)
    }
}
