package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class PacketNotificationsTest {
    @Test fun invalidBatteryReadingsPreserveLastValidLevelAndComponentOrder() {
        val battery = AirPodsNotifications.BatteryNotification()
        battery.setBatteryDirect(58, false, 65, false, 42, false)
        val packet = byteArrayOf(4, 0, 4, 0, 4, 0, 3,
            2, 1, 127, 2, 1, 4, 1, 60, 2, 1, 8, 1, 0xFF.toByte(), 2, 1)
        battery.setBattery(packet)
        assertEquals(listOf(60, 65, 42), battery.getBattery().map { it.level })
        assertEquals(BatteryStatus.DISCONNECTED, battery.getBattery()[1].status)
        assertEquals(BatteryStatus.DISCONNECTED, battery.getBattery()[2].status)
        packet[9] = 75
        packet[19] = 100
        battery.setBattery(packet)
        assertEquals(listOf(60, 75, 100), battery.getBattery().map { it.level })
    }
    @Test fun packetTypesRequireTheirHeaderAndFullPayload() {
        val battery = AirPodsNotifications.BatteryNotification()
        val ear = AirPodsNotifications.EarDetection()
        val anc = AirPodsNotifications.ANC()
        val convo = AirPodsNotifications.ConversationalAwarenessNotification()
        val packets = listOf(
            (byteArrayOf(4, 0, 4, 0, 4, 0) + ByteArray(16)) to battery::isBatteryData,
            (byteArrayOf(4, 0, 4, 0, 6, 0, 0, 1)) to ear::isEarDetectionData,
            (byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 2, 0, 0, 0)) to anc::isANCData,
            (byteArrayOf(4, 0, 4, 0, 0x4B, 0, 2, 0, 0, 1)) to convo::isConversationalAwarenessData
        )
        for ((packet, matches) in packets) {
            assertTrue(matches(packet))
            for (length in packet.indices) assertFalse(matches(packet.copyOf(length)))
            for (index in 0..4) {
                val wrong = packet.copyOf()
                wrong[index] = (wrong[index].toInt() xor 0x80).toByte()
                assertFalse(matches(wrong))
            }
        }
    }

    @Test fun previousConnectionValuesRemainUnavailableUntilEachComponentReportsAgain() {
        val battery = AirPodsNotifications.BatteryNotification()
        battery.setBatteryDirect(58, true, 65, true, 42, true)
        battery.invalidate()
        assertEquals(listOf(58, 65, 42), battery.getBattery().map { it.level })
        assertTrue(battery.getBattery().all { it.status == BatteryStatus.DISCONNECTED })
        val packet = byteArrayOf(4, 0, 4, 0, 4, 0, 3,
            4, 1, 60, 2, 1, 2, 1, 127, 2, 1, 8, 1, 127, 2, 1)
        battery.setBattery(packet)
        assertEquals(BatteryStatus.NOT_CHARGING, battery.getBattery()[0].status)
        assertEquals(BatteryStatus.DISCONNECTED, battery.getBattery()[1].status)
        assertEquals(BatteryStatus.DISCONNECTED, battery.getBattery()[2].status)
    }

    @Test fun ancNameTracksChangesAndShortEventsPreserveState() {
        val anc = AirPodsNotifications.ANC()
        anc.setStatus(byteArrayOf(2))
        assertEquals("ON", anc.name)
        anc.setStatus(byteArrayOf(3))
        assertEquals("TRANSPARENCY", anc.name)
        val ear = AirPodsNotifications.EarDetection()
        ear.setStatus(byteArrayOf())
        assertEquals(listOf<Byte>(1, 1), ear.status)
        val conversation = AirPodsNotifications.ConversationalAwarenessNotification()
        conversation.setData(byteArrayOf())
        assertEquals(0.toByte(), conversation.status)
    }

    @Test fun deviceOffReplyOverridesStalePreferenceInEitherDirection() {
        assertFalse(isOffListeningModeAllowed(byteArrayOf(2), true))
        assertTrue(isOffListeningModeAllowed(byteArrayOf(1), false))
        assertFalse(isOffListeningModeAllowed(null, false))
        assertTrue(isOffListeningModeAllowed(byteArrayOf(), true))
    }
}
