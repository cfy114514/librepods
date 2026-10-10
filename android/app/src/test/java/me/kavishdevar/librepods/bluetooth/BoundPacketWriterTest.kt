package me.kavishdevar.librepods.bluetooth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BoundPacketWriterTest {
    @Test fun aRetiredConnectionIsRejectedBeforeOpeningItsStream() {
        var opened = false
        assertFalse(writeCurrentPacket(byteArrayOf(1), { false }) { opened = true; ByteArrayOutputStream() })
        assertFalse(opened)
    }

    @Test fun replacementWhileWaitingForOutputLockDoesNotWriteToEitherConnection() {
        val old = ByteArrayOutputStream()
        val replacement = ByteArrayOutputStream()
        val current = AtomicBoolean(true)
        val resolved = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = synchronized(old) {
                val pending = executor.submit<Boolean> {
                    writeCurrentPacket(byteArrayOf(1, 2, 3), current::get) { resolved.countDown(); old }
                }
                assertTrue(resolved.await(3, TimeUnit.SECONDS))
                current.set(false)
                pending
            }
            assertFalse(result.get(3, TimeUnit.SECONDS))
            assertEquals(0, old.size())
            assertEquals(0, replacement.size())
        } finally { executor.shutdownNow() }
    }

    @Test fun concurrentCommandsRemainWholePacketsEvenWithByteAtATimeOutput() {
        val bytes = ByteArrayOutputStream()
        val stream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value); Thread.yield() }
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val work = (1..200).map { value -> executor.submit<Boolean> {
                writeCurrentPacket(ByteArray(4) { value.toByte() }, { true }) { stream }
            } }
            work.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            val chunks = bytes.toByteArray().toList().chunked(4)
            assertEquals(200, chunks.size)
            assertTrue(chunks.all { it.size == 4 && it.distinct().size == 1 })
            assertEquals((1..200).toSet(), chunks.map { it.first().toInt() and 0xFF }.toSet())
        } finally { executor.shutdownNow() }
    }

    @Test fun outputFailureIsNotReportedAsASuccessfulWrite() {
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("closed") } }
        assertThrows(IOException::class.java) { writeCurrentPacket(byteArrayOf(1), { true }) { broken } }
    }
}
