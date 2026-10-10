package me.kavishdevar.librepods.bluetooth

import java.io.OutputStream

/** Capture one output; never resolve a replacement connection after waiting for its write lock. */
internal fun writeCurrentPacket(packet: ByteArray, isCurrent: () -> Boolean, output: () -> OutputStream): Boolean {
    if (!isCurrent()) return false
    val stream = output()
    synchronized(stream) {
        if (!isCurrent()) return false
        stream.write(packet)
        stream.flush()
    }
    return true
}
