package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class PacketLogFormatterTest {
    @Test fun repeatsReuseBothFormattedStringsWithoutChangingRawText() {
        val formatter = PacketLogFormatter()
        val bytes = byteArrayOf(0, 1, 0x7F, 0x80.toByte(), 0xFF.toByte())
        formatter.format(bytes, "AirPods")
        val received = formatter.received
        val entry = formatter.entry
        assertEquals("Data received: 00 01 7F 80 FF", received)
        assertEquals("AirPods: 00 01 7F 80 FF", entry)
        repeat(100_000) {
            formatter.format(bytes.copyOf(), "AirPods")
            assertSame(received, formatter.received)
            assertSame(entry, formatter.entry)
        }
    }
    @Test fun callerMutationAndChangedLengthsCannotHideChangedBytes() {
        val formatter = PacketLogFormatter()
        val bytes = byteArrayOf(1, 2)
        formatter.format(bytes, "AirPods")
        val first = formatter.entry
        bytes[1] = 3
        formatter.format(bytes, "AirPods")
        assertNotSame(first, formatter.entry)
        assertEquals("AirPods: 01 02", first)
        assertEquals("AirPods: 01 03", formatter.entry)
        formatter.format(byteArrayOf(1), "AirPods")
        assertEquals("AirPods: 01", formatter.entry)
        formatter.format(byteArrayOf(1, 0), "AirPods")
        assertEquals("AirPods: 01 00", formatter.entry)
        assertEquals("Data received: 01 00", formatter.received)
    }
    @Test fun changedSourcesCannotReuseAnotherSourcesHistoryLabel() {
        val formatter = PacketLogFormatter()
        val bytes = byteArrayOf(1)
        formatter.format(bytes, "AirPods")
        val first = formatter.entry
        formatter.format(bytes, "Other")
        assertNotSame(first, formatter.entry)
        assertEquals("Data received: 01", formatter.received)
        assertEquals("Other: 01", formatter.entry)
    }
    @Test fun oversizedPacketsAreFormattedButNotRetainedAsMemoizedHistory() {
        val formatter = PacketLogFormatter(2)
        formatter.format(byteArrayOf(1), "AirPods")
        val small = formatter.entry
        val large = byteArrayOf(1, 2, 3)
        formatter.format(large, "AirPods")
        val first = formatter.entry
        assertEquals("AirPods: 01 02 03", first)
        formatter.format(large, "AirPods")
        assertNotSame(first, formatter.entry)
        formatter.format(byteArrayOf(1), "AirPods")
        assertNotSame(small, formatter.entry)
    }
    @Test fun clearReleasesTheMemoizedTextAndEmptyPacketsKeepTheirOriginalText() {
        val formatter = PacketLogFormatter()
        formatter.format(byteArrayOf(), "AirPods")
        val empty = formatter.entry
        assertEquals("Data received: ", formatter.received)
        assertEquals("AirPods: ", empty)
        formatter.clear()
        assertEquals("", formatter.received)
        assertEquals("", formatter.entry)
        formatter.format(byteArrayOf(), "AirPods")
        assertNotSame(empty, formatter.entry)
    }
    @Test fun serializedConcurrentCallersAlwaysReceiveTheirOwnPacketAndSource() {
        val formatter = PacketLogFormatter()
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { index -> workers.submit {
                repeat(5_000) {
                    val entry = synchronized(formatter) {
                        formatter.format(byteArrayOf(index.toByte()), "Source-$index")
                        formatter.entry
                    }
                    assertEquals("Source-$index: 0$index", entry)
                }
            } }
            jobs.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { workers.shutdownNow() }
    }
}
