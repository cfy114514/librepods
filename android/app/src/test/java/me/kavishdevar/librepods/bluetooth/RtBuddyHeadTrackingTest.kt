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

    private fun descriptor(service: Int): ByteArray {
        val properties = byteArrayOf(16, 0, 0, 9) + "AccessoryService".toByteArray() +
            byteArrayOf(10, 0, 0, 9) + "devmotion6".toByteArray()
        val descriptor = byteArrayOf(8, service.toByte(), 0x12, properties.size.toByte()) + properties
        return byteArrayOf(0x2a, descriptor.size.toByte()) + descriptor
    }

    private fun motionPacket(service: Int) = live.copyOf().apply { this[19] = service.toByte() }

    private fun AACPManager.observe(
        discovery: () -> Unit = {},
        motion: (HeadTrackingMotion) -> Unit = {}
    ) {
        setPacketCallback(Proxy.newProxyInstance(AACPManager.PacketCallback::class.java.classLoader,
            arrayOf(AACPManager.PacketCallback::class.java)) { _, method, args ->
            when (method.name) {
                "onHeadTrackingServiceDiscovered" -> discovery()
                "onHeadTrackingReceived" -> motion(args!![0] as HeadTrackingMotion)
            }
            null
        } as AACPManager.PacketCallback)
    }

    @Test fun capturedMotionAndSequenceVarintGrowthHaveIdenticalAxes() {
        val expected = HeadTrackingMotion(15, -20877, 17088, -1412, 76, -2)
        assertEquals(expected, RtBuddyHeadTracking.parse(live)?.motion)
        val sequence128 = frame(byteArrayOf(8, 0x80.toByte(), 1) + live.copyOfRange(14, live.size))
        assertEquals(expected, RtBuddyHeadTracking.parse(sequence128)?.motion)
        val sequence16384 = frame(byteArrayOf(8, 0x80.toByte(), 0x80.toByte(), 1) + live.copyOfRange(14, live.size))
        assertEquals(expected, RtBuddyHeadTracking.parse(sequence16384)?.motion)
    }

    @Test fun acknowledgementsAndDescriptorsNeverBecomeMotion() {
        val ack = frame(byteArrayOf(8, 1, 0x10, 1, 0x4a, 2, 8, 15))
        assertTrue(hasCompleteAacpPayload(ack))
        assertNull(RtBuddyHeadTracking.parse(ack)?.motion)
        for (id in listOf(6, 15, 16)) {
            val packet = frame(byteArrayOf(8, 1, 0x10, 1) + descriptor(id))
            val report = RtBuddyHeadTracking.parse(packet)
            assertEquals(id, report?.discoveredService)
            assertNull(report?.motion)
        }
    }

    @Test fun malformedOuterAndNestedLengthsAndUnexpectedReportFormatsAreRejected() {
        for (length in live.indices) assertNull(RtBuddyHeadTracking.parse(live.copyOf(length))?.motion)
        assertNull(RtBuddyHeadTracking.parse(live.copyOf().apply { this[10] = 1 })?.motion)
        assertNull(RtBuddyHeadTracking.parse(live.copyOf().apply { this[21] = 0x7f })?.motion)
        assertNull(RtBuddyHeadTracking.parse(live.copyOf().apply { this[31] = 2 })?.motion)
        assertNull(RtBuddyHeadTracking.parse(live.copyOf() + 0)?.motion)
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
        val packet = frame(byteArrayOf(8, 1, 0x10, 1) + descriptor(15))
        val manager = AACPManager()
        var discoveries = 0
        val samples = mutableListOf<HeadTrackingMotion>()
        manager.observe(discovery = { discoveries++ }, motion = { samples.add(it) })
        val retired = manager.beginDeviceSession()
        val current = manager.beginDeviceSession()
        manager.receivePacket(packet, retired)
        manager.receivePacket(packet, current) { false }
        assertNull(manager.headTrackingService)
        manager.receivePacket(packet, current)
        manager.receivePacket(packet, current)
        assertEquals(15, manager.headTrackingService)
        assertEquals(1, discoveries)
        assertTrue(samples.isEmpty())
        manager.receivePacket(live, current)
        manager.receivePacket(motionPacket(16), current)
        assertEquals(listOf(HeadTrackingMotion(15, -20877, 17088, -1412, 76, -2)), samples)
        manager.beginDeviceSession()
        assertNull(manager.headTrackingService)
        manager.receivePacket(live, current)
        assertEquals(1, samples.size)
    }

    @Test fun aCombinedReportDiscoversBeforeFilteringAndDeliveringItsMotion() {
        for (service in listOf(15, 16)) {
            // Field order must not turn a combined report into a descriptor-only or motion-only event.
            for (descriptorFirst in listOf(true, false)) {
                val command = motionPacket(service).copyOfRange(12, live.size)
                val packet = frame(if (descriptorFirst) descriptor(15) + command else command + descriptor(15))
                val report = RtBuddyHeadTracking.parse(packet)
                assertEquals(15, report?.discoveredService)
                assertEquals(service, report?.motion?.service)
                val manager = AACPManager()
                val events = mutableListOf<String>()
                manager.observe(discovery = {
                    assertEquals(15, manager.headTrackingService)
                    events.add("discovery")
                }, motion = {
                    assertEquals(HeadTrackingMotion(15, -20877, 17088, -1412, 76, -2), it)
                    events.add("motion")
                })
                manager.receivePacket(packet, manager.beginDeviceSession())
                assertEquals(if (service == 15) listOf("discovery", "motion") else listOf("discovery"), events)
            }
        }
    }

    @Test fun onlyLegacyServicesAreAcceptedUntilDiscoveryAndAfterSessionReset() {
        val manager = AACPManager()
        val services = mutableListOf<Int>()
        manager.observe(motion = { services.add(it.service) })
        val generation = manager.beginDeviceSession()
        for (service in listOf(6, 14, 15, 16, 17)) manager.receivePacket(motionPacket(service), generation)
        assertEquals(listOf(14, 16), services)
        manager.receivePacket(frame(descriptor(15)), generation)
        for (service in listOf(14, 15, 16)) manager.receivePacket(motionPacket(service), generation)
        assertEquals(listOf(14, 16, 15), services)
        val replacement = manager.beginDeviceSession()
        for (service in listOf(14, 15, 16)) manager.receivePacket(motionPacket(service), replacement)
        assertEquals(listOf(14, 16, 15, 14, 16), services)
    }

    @Test fun discoveryCallbackCannotDeliverCombinedMotionAfterInvalidatingTheSessionOrSocketGuard() {
        val packet = frame(descriptor(15) + live.copyOfRange(12, live.size))
        for (resetSession in listOf(true, false)) {
            val manager = AACPManager()
            var canReceive = true
            var discoveries = 0
            var samples = 0
            manager.observe(discovery = {
                discoveries++
                if (resetSession) manager.beginDeviceSession() else canReceive = false
            }, motion = { samples++ })
            val generation = manager.beginDeviceSession()
            manager.receivePacket(packet, generation) { canReceive }
            assertEquals(1, discoveries)
            assertEquals(0, samples)
            if (resetSession) assertNull(manager.headTrackingService)
        }
    }
}
