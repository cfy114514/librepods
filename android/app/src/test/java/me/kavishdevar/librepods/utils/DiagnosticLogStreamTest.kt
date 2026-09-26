package me.kavishdevar.librepods.utils

import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogStreamTest {
    @Test fun preservesUnicodeAndMarkersAndReportsConnectionOnlyOnce() {
        val input = "中文日志\n<LogCollector:Start>\n<LogCollector:Complete:Success>\n<LogCollector:Complete:Failed>\nlast\n"
        val output = StringWriter()
        var callbacks = 0
        streamDiagnosticLog(StringReader(input).buffered(), output, { true }) { callbacks++ }
        assertEquals(1, callbacks)
        assertEquals(
            "中文日志\n\n=============\n<LogCollector:Start>\n=============\n\n" +
                "\n=============\n<LogCollector:Complete:Success>\n=============\n\n" +
                "\n=============\n<LogCollector:Complete:Failed>\n=============\n\nlast\n",
            output.toString()
        )
    }

    @Test fun disconnectDoesNotFinishCollectionAndStopPreventsMoreWrites() {
        val output = StringWriter()
        var collecting = true
        var callbacks = 0
        val input = "BluetoothService CONNECTION_STATE_DISCONNECTED\nAirPodsService Connected to device\nignored\n"
        streamDiagnosticLog(StringReader(input).buffered(), output, { collecting }) {
            callbacks++
            collecting = false
        }
        assertEquals(1, callbacks)
        assertEquals("BluetoothService CONNECTION_STATE_DISCONNECTED\nAirPodsService Connected to device\n", output.toString())
    }

    @Test fun veryLongSessionsAreWrittenIncrementallyWithoutDroppingLines() {
        val line = "AirPods packet 1234\n"
        val totalCharacters = line.length * 100_000
        var readCharacters = 0
        val input = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (readCharacters == totalCharacters) return -1
                val count = minOf(length, totalCharacters - readCharacters)
                repeat(count) { buffer[offset + it] = line[(readCharacters + it) % line.length] }
                readCharacters += count
                return count
            }
            override fun close() = Unit
        }
        var writtenCharacters = 0
        var largestWrite = 0
        val output = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                largestWrite = maxOf(largestWrite, length)
                writtenCharacters += length
                // Only the reader's buffer may be ahead, never the whole collection.
                assertTrue(readCharacters - writtenCharacters <= 8192)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        streamDiagnosticLog(input.buffered(), output, { true }) { fail("Unexpected callback") }
        assertEquals(totalCharacters, writtenCharacters)
        assertTrue(largestWrite <= line.length)
    }
}
