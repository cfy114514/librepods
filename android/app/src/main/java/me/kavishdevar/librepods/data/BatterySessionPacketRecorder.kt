package me.kavishdevar.librepods.data

import me.kavishdevar.librepods.bluetooth.AACPManager

/**
 * Records only a complete incoming battery frame for the connection captured by its reader.
 * Neither the optimistic notification cache nor historical display values enter this path.
 * The repository checks the connection token and monotonic receipt timestamp again at entry.
 */
internal fun recordBatterySessionPacket(
    connection: BatterySessionRepository.Connection?,
    packet: ByteArray,
    receivedAtElapsedMs: Long
): Boolean {
    if (connection == null || packet.size != 22 || !AACPManager.hasExpectedHeader(packet) ||
        packet[4] != AACPManager.Companion.Opcodes.BATTERY_INFO || packet[5] != 0.toByte() ||
        packet[6] != 3.toByte()) return false
    return connection.recordReceived(listOf(7, 12, 17).map { offset ->
        Battery(packet[offset].toInt() and 0xff, packet[offset + 2].toInt() and 0xff,
            packet[offset + 3].toInt() and 0xff)
    }, receivedAtElapsedMs)
}
