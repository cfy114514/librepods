package me.kavishdevar.librepods.bluetooth

import java.io.OutputStream

/** Capture one output; never resolve a replacement connection after waiting for its write lock. */
internal fun writeCurrentPacket(packet: ByteArray, isCurrent: () -> Boolean, output: () -> OutputStream): Boolean {
    return writeCurrentPacket(packet, isCurrent, output, beforeWrite = {})
}

/** The dispatch boundary must be after waiting for the stream lock and its final validity check. */
internal fun writeCurrentPacket(
    packet: ByteArray, isCurrent: () -> Boolean, output: () -> OutputStream, beforeWrite: () -> Unit
): Boolean {
    if (!isCurrent()) return false
    val stream = output()
    synchronized(stream) {
        if (!isCurrent()) return false
        beforeWrite()
        if (!isCurrent()) return false
        stream.write(packet)
        stream.flush()
    }
    return true
}
