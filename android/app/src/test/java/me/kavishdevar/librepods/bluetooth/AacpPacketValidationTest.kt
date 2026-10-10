package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class AacpPacketValidationTest {
    private val manager = AACPManager()

    @Test fun truncatedPacketsNeverReachFixedOffsetCallbacks() {
        val minimums = mapOf(0x04 to 22, 0x09 to 11, 0x06 to 8, 0x19 to 8,
            0x4B to 10, 0x31 to 7, 0x0E to 13, 0x2E to 9, 0x11 to 12,
            0x1D to 7, 0x63 to 13, 0x53 to 140)
        for ((opcode, minimum) in minimums) {
            val packet = manager.createDataPacket(byteArrayOf(opcode.toByte(), 0) + ByteArray(minimum - 6))
            for (length in 0 until minimum) {
                assertFalse("opcode=$opcode length=$length", hasCompleteAacpPayload(packet.copyOf(length)))
            }
            assertTrue(hasCompleteAacpPayload(packet))
        }
    }

    @Test fun badHeaderAndEmptyInputAreRejected() {
        assertFalse(hasCompleteAacpPayload(byteArrayOf()))
        assertFalse(hasCompleteAacpPayload(ByteArray(200)))
    }

    @Test fun validProximityKeysAndFirmwareSingleKeyReplyAreAccepted() {
        val irk = ByteArray(16) { it.toByte() }
        val enc = ByteArray(16) { (it + 16).toByte() }
        val header = byteArrayOf(4, 0, 4, 0, 0x31, 0, 2)
        val first = byteArrayOf(1, 0, 16, 0) + irk
        val second = byteArrayOf(4, 0, 16, 0) + enc
        val keys = manager.parseProximityKeysResponse(header + first + second)
        assertArrayEquals(irk, keys[AACPManager.Companion.ProximityKeyType.IRK])
        assertArrayEquals(enc, keys[AACPManager.Companion.ProximityKeyType.ENC_KEY])
        val firmwareReply = manager.parseProximityKeysResponse(header + first)
        assertEquals(1, firmwareReply.size)
        assertArrayEquals(irk, firmwareReply[AACPManager.Companion.ProximityKeyType.IRK])
    }

    @Test fun truncatedKeyFieldsDoNotPublishPartialOrInvalidKeyMaterial() {
        val packet = byteArrayOf(4, 0, 4, 0, 0x31, 0, 1, 1, 0, 16, 0) + ByteArray(16)
        for (length in 0 until packet.size) {
            try {
                manager.parseProximityKeysResponse(packet.copyOf(length))
                fail("Accepted truncated key of length $length")
            } catch (_: IllegalArgumentException) { }
        }
        packet[9] = 0xFF.toByte()
        try {
            manager.parseProximityKeysResponse(packet)
            fail("Accepted unsigned 255-byte key from a 16-byte payload")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun shortAudioSourceAndEmptyInformationFailPredictably() {
        for (length in 0..12) {
            try {
                manager.parseAudioSourceResponse(ByteArray(length))
                fail("Accepted short audio source")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            manager.parseInformationPacket(byteArrayOf(4, 0, 4, 0, 0x1D, 0, 0))
            fail("Accepted information with no fields")
        } catch (_: IllegalArgumentException) { }
    }
}
