package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class RenamePacketsTest {
    private val manager = AACPManager()
    @Test fun asciiKeepsTheDocumentedWireFormat() {
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x1A, 0, 1, 3, 0, 65, 66, 67),
            manager.createDataPacket(manager.createRenamePacket("ABC")))
    }
    @Test fun unicodeLengthMatchesActualUtf8Payload() {
        val name = "名称🎧"
        val bytes = name.toByteArray(Charsets.UTF_8)
        val packet = manager.createRenamePacket(name)
        assertEquals(bytes.size, packet[3].toInt() and 255)
        assertArrayEquals(bytes, packet.copyOfRange(5, packet.size))
    }
    @Test fun maximumSizeByteDoesNotOverflowPayloadLength() {
        val packet = manager.createRenamePacket("x".repeat(255))
        assertEquals(255, packet[3].toInt() and 255)
        assertEquals(260, packet.size)
        assertEquals(0, packet[4].toInt())
    }
    @Test fun invalidNamesAreRejectedBeforePacketConstruction() {
        for (name in listOf("", "a".repeat(256), "名".repeat(86), "bad\uD800"))
            assertThrows(IllegalArgumentException::class.java) { manager.createRenamePacket(name) }
    }
}
