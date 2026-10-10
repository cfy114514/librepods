package me.kavishdevar.librepods.audio

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.roundToInt

/** Bounded streaming reader for PCM WAV produced by Android TTS; output is signed PCM16. */
internal class WavePcmReader(input: InputStream, maxDataBytes: Long = 8L * 1024 * 1024) : Closeable {
    private val input = DataInputStream(input)
    val sampleRate: Int
    val channels: Int
    private val bits: Int
    private var remaining: Long
    private val bytes = ByteArray(4096)
    init {
        require(maxDataBytes > 0)
        require(tag() == "RIFF") { "Not RIFF" }
        val riffSize = u32()
        require(riffSize in 36..(maxDataBytes + 65536)) { "WAV too large" }
        require(tag() == "WAVE") { "Not WAV" }
        var headerBytes = 12L
        var foundFormat: Int? = null
        var foundChannels = 0
        var foundRate = 0
        var foundBits = 0
        var dataSize: Long? = null
        while (dataSize == null) {
            val kind = tag()
            val size = u32()
            headerBytes += 8
            require(headerBytes <= 65536) { "WAV header too large" }
            if (kind == "data") {
                require(foundFormat != null && size in 1..maxDataBytes) { "Invalid WAV data" }
                dataSize = size
            } else {
                require(size <= 65536 - headerBytes) { "WAV header chunk too large" }
                if (kind == "fmt ") {
                    require(foundFormat == null && size >= 16) { "Invalid WAV format" }
                    foundFormat = u16()
                    foundChannels = u16()
                    foundRate = u32().toInt()
                    val byteRate = u32()
                    val blockAlign = u16()
                    foundBits = u16()
                    require(foundChannels in 1..2 && foundRate in 8000..48000) { "Unsupported WAV layout" }
                    require((foundFormat == 1 && foundBits in listOf(8, 16)) ||
                        (foundFormat == 3 && foundBits == 32)) { "Unsupported WAV encoding" }
                    require(blockAlign == foundChannels * (foundBits / 8) && byteRate == foundRate.toLong() * blockAlign) { "Invalid WAV alignment" }
                    skip(size - 16)
                } else skip(size)
                if (size % 2 == 1L) skip(1)
                headerBytes += size + size % 2
            }
        }
        channels = foundChannels
        sampleRate = foundRate
        bits = foundBits
        require(dataSize % (channels * bits / 8) == 0L) { "Truncated WAV frame" }
        remaining = dataSize
    }
    fun read(destination: ShortArray): Int {
        if (remaining == 0L) return 0
        require(destination.size >= channels)
        val bytesPerSample = bits / 8
        val samples = minOf(destination.size, bytes.size / bytesPerSample, (remaining / bytesPerSample).toInt()) / channels * channels
        input.readFully(bytes, 0, samples * bytesPerSample)
        for (index in 0 until samples) {
            val offset = index * bytesPerSample
            destination[index] = when (bits) {
                8 -> (((bytes[offset].toInt() and 255) - 128) shl 8).toShort()
                16 -> ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort()
                else -> {
                    var value = 0
                    for (byte in 0..3) value = value or ((bytes[offset + byte].toInt() and 255) shl (byte * 8))
                    val floating = Float.fromBits(value)
                    if (!floating.isFinite()) 0 else (floating.coerceIn(-1f, 1f) * 32768).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }
        remaining -= samples * bytesPerSample
        return samples
    }
    private fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    private fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
    private fun skip(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped else { if (input.read() < 0) throw EOFException(); left-- }
        }
    }
    override fun close() = input.close()
}
