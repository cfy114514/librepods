package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class WavePcmReaderTest {
    private fun ByteArrayOutputStream.u16(value: Int) { write(value); write(value shr 8) }
    private fun ByteArrayOutputStream.u32(value: Int) { u16(value); u16(value shr 16) }
    private fun wav(data: ByteArray, bits: Int = 16, format: Int = 1, channels: Int = 1, extra: Boolean = false): ByteArray {
        val chunks = ByteArrayOutputStream().apply {
            if (extra) { write("JUNK".toByteArray()); u32(3); write(byteArrayOf(1, 2, 3, 0)) }
            write("fmt ".toByteArray()); u32(16); u16(format); u16(channels); u32(24000)
            u32(24000 * channels * bits / 8); u16(channels * bits / 8); u16(bits)
            write("data".toByteArray()); u32(data.size); write(data)
            if (data.size % 2 == 1) write(0)
        }.toByteArray()
        return ByteArrayOutputStream().apply { write("RIFF".toByteArray()); u32(chunks.size + 4); write("WAVE".toByteArray()); write(chunks) }.toByteArray()
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Invalid WAV accepted") } catch (_: IllegalArgumentException) {} catch (_: EOFException) {}
    }
    @Test fun signedPcm16AndOddUnknownHeaderChunksPreserveSamples() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val data = ByteArrayOutputStream().apply { samples.forEach { u16(it.toInt()) } }.toByteArray()
        WavePcmReader(ByteArrayInputStream(wav(data, extra = true))).use { reader ->
            val output = ShortArray(10)
            assertEquals(24000, reader.sampleRate)
            assertEquals(1, reader.channels)
            assertEquals(samples.size, reader.read(output))
            assertArrayEquals(samples, output.copyOf(samples.size))
            assertEquals(0, reader.read(output))
        }
    }
    @Test fun unsignedPcm8AndFloatSynthesisAreConvertedWithoutOverflow() {
        WavePcmReader(ByteArrayInputStream(wav(byteArrayOf(0, 128.toByte(), 255.toByte()), bits = 8))).use { reader ->
            val output = ShortArray(3); assertEquals(3, reader.read(output))
            assertArrayEquals(shortArrayOf(-32768, 0, 32512), output)
        }
        val floats = floatArrayOf(-2f, -1f, 0f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        val bytes = ByteArrayOutputStream().apply { floats.forEach { u32(it.toBits()) } }.toByteArray()
        WavePcmReader(ByteArrayInputStream(wav(bytes, bits = 32, format = 3))).use { reader ->
            val output = ShortArray(floats.size); reader.read(output)
            assertArrayEquals(shortArrayOf(-32768, -32768, 0, 32767, 32767, 0, 0), output)
        }
    }
    @Test fun streamingStereoChunksNeverSplitFramesOrAllocateTheWholeUtterance() {
        val data = ByteArrayOutputStream().apply { repeat(10_000) { u16(it); u16(-it) } }.toByteArray()
        WavePcmReader(ByteArrayInputStream(wav(data, channels = 2))).use { reader ->
            val buffer = ShortArray(33)
            var frames = 0
            while (true) {
                val count = reader.read(buffer)
                if (count == 0) break
                assertEquals(0, count % 2)
                for (i in 0 until count step 2) {
                    assertEquals(frames.toShort(), buffer[i])
                    assertEquals((-frames).toShort(), buffer[i + 1]); frames++
                }
            }
            assertEquals(10_000, frames)
        }
    }
    @Test fun malformedSizesEncodingAndTruncatedFramesAreRejected() {
        val valid = wav(byteArrayOf(0, 1, 2, 3))
        for (length in 0 until 44) invalid { WavePcmReader(ByteArrayInputStream(valid.copyOf(length))) }
        invalid { WavePcmReader(ByteArrayInputStream(wav(byteArrayOf(1), channels = 2))) }
        invalid { WavePcmReader(ByteArrayInputStream(wav(byteArrayOf(1, 2), format = 6))) }
        invalid { WavePcmReader(ByteArrayInputStream(wav(byteArrayOf(1, 2, 3, 4))), maxDataBytes = 2) }
        val corrupted = valid.copyOf().apply { this[40] = 255.toByte(); this[41] = 255.toByte(); this[42] = 255.toByte(); this[43] = 127 }
        invalid { WavePcmReader(ByteArrayInputStream(corrupted)) }
        invalid { WavePcmReader(ByteArrayInputStream(valid.copyOf(valid.size - 1))).read(ShortArray(4)) }
    }
}
