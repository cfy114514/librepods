package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class PacketLogBufferTest {
    @Test fun longSessionRetainsOnlyTheLatestDistinctPackets() {
        val history = PacketLogBuffer(1000)
        repeat(100_000) { history.add("packet $it") }
        assertEquals((99_000 until 100_000).map { "packet $it" }.toSet(), history.snapshot())
    }

    @Test fun repeatedPacketsDoNotEvictOtherDiagnosticsOrRequestAnotherWrite() {
        val history = PacketLogBuffer(2)
        history.add("first")
        history.add("second")
        repeat(10_000) { assertFalse(history.add("first")) }
        assertEquals(linkedSetOf("first", "second"), history.snapshot())
        history.add("third")
        assertEquals(linkedSetOf("second", "third"), history.snapshot())
    }

    @Test fun publishedSnapshotIsUnaffectedByNewPacketsAndClear() {
        val history = PacketLogBuffer(2)
        history.add("first")
        history.add("second")
        val snapshot = history.snapshot()
        history.add("third")
        history.clear()
        assertTrue(history.snapshot().isEmpty())
        assertEquals(linkedSetOf("first", "second"), snapshot)
        assertTrue(history.add("first"))
    }
}
