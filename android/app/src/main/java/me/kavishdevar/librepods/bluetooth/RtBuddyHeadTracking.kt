package me.kavishdevar.librepods.bluetooth

/** SensorDataWX uses protobuf lengths/varints; absolute AACP byte offsets are not stable. */
internal object RtBuddyHeadTracking {
    data class Report(val discoveredService: Int?, val motion: HeadTrackingMotion?)
    private data class Field(val number: Int, val value: Int? = null, val bytes: ByteArray? = null)
    private val serviceSignature = byteArrayOf(16, 0, 0, 9) + "AccessoryService".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(10, 0, 0, 9) + "devmotion6".toByteArray(Charsets.US_ASCII)

    fun complete(packet: ByteArray): Boolean = packet.size >= 12 &&
        AACPManager.hasExpectedHeader(packet) && packet[4] == 0x17.toByte() && packet[5] == 0.toByte() &&
        u16(packet, 10) == packet.size - 12

    private fun wx(packet: ByteArray): List<Field>? {
        if (!complete(packet) || packet[6] != 0.toByte() || packet[7] != 0.toByte() ||
            packet[8] != 0x10.toByte() || packet[9] != 0.toByte()) return null
        return fields(packet.copyOfRange(12, packet.size))
    }

    /** A frame may contain both a descriptor and motion; decode their shared envelope once. */
    fun parse(packet: ByteArray): Report? {
        val envelope = wx(packet) ?: return null
        return Report(discoverService(envelope), motion(envelope))
    }

    private fun discoverService(envelope: List<Field>): Int? {
        val signature = serviceSignature
        for (field in envelope) {
            if (field.number != 5 || field.bytes == null) continue
            val descriptor = fields(field.bytes) ?: continue
            val service = descriptor.singleOrNull { it.number == 1 }?.value ?: continue
            val data = descriptor.singleOrNull { it.number == 2 }?.bytes ?: continue
            if (service in 1..255 && data.indices.any { start ->
                    start + signature.size <= data.size && signature.indices.all { data[start + it] == signature[it] }
                }) return service
        }
        return null
    }

    private fun motion(envelope: List<Field>): HeadTrackingMotion? {
        val command = fields(envelope.singleOrNull { it.number == 7 }?.bytes ?: return null) ?: return null
        val service = command.singleOrNull { it.number == 1 }?.value ?: return null
        val payload = command.singleOrNull { it.number == 3 }?.bytes ?: return null
        // The live A3441 devmotion6 report is 58 bytes, version 1, fused-motion format 3.
        if (payload.size != 58 || payload[0] != 1.toByte() || payload[9] != 3.toByte()) return null
        return HeadTrackingMotion(service, s16(payload, 20), s16(payload, 22), s16(payload, 24), s16(payload, 28), s16(payload, 30))
    }

    fun discoveryPacket(): ByteArray = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, 4, 0, 8, 1, 0x22, 0)

    fun settingPacket(service: Int, enabled: Boolean): ByteArray {
        require(service in 1..255)
        val id = varint(service)
        val setting = byteArrayOf(8) + id + byteArrayOf(0x10, 2, 0x1a, 5, 1) +
            if (enabled) byteArrayOf(0x40, 0x9c.toByte(), 0, 0) else byteArrayOf(0, 0, 0, 0)
        val payload = byteArrayOf(8, 1, 0x42, setting.size.toByte()) + setting
        return byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, payload.size.toByte(), 0) + payload
    }

    private fun varint(value: Int): ByteArray = if (value < 128) byteArrayOf(value.toByte())
        else byteArrayOf(((value and 127) or 128).toByte(), (value ushr 7).toByte())
    private fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
    private fun s16(data: ByteArray, at: Int) = u16(data, at).toShort().toInt()

    /** Bounded wire reader; malformed lengths, duplicate fields and unsupported wire types never escape. */
    private fun fields(data: ByteArray): List<Field>? {
        var at = 0
        fun readVarint(): Int? {
            var value = 0L
            for (shift in 0..28 step 7) {
                if (at >= data.size) return null
                val byte = data[at++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
            }
            return null
        }
        val result = mutableListOf<Field>()
        while (at < data.size) {
            val key = readVarint() ?: return null
            val number = key ushr 3
            if (number == 0) return null
            when (key and 7) {
                0 -> result.add(Field(number, value = readVarint() ?: return null))
                2 -> {
                    val length = readVarint() ?: return null
                    if (length > data.size - at) return null
                    result.add(Field(number, bytes = data.copyOfRange(at, at + length)))
                    at += length
                }
                1, 5 -> { at += if (key and 7 == 1) 8 else 4; if (at > data.size) return null }
                else -> return null
            }
        }
        return result
    }
}
