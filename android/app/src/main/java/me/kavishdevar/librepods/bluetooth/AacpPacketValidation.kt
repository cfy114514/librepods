package me.kavishdevar.librepods.bluetooth

/** Validate fixed offsets before service callbacks touch an incoming payload. */
internal fun hasCompleteAacpPayload(packet: ByteArray): Boolean {
    if (!AACPManager.hasExpectedHeader(packet) || packet.size < 6) return false
    val minimumSize = when (packet[4]) {
        AACPManager.Companion.Opcodes.BATTERY_INFO -> 22
        AACPManager.Companion.Opcodes.CONTROL_COMMAND -> 11
        AACPManager.Companion.Opcodes.EAR_DETECTION,
        AACPManager.Companion.Opcodes.STEM_PRESS -> 8
        AACPManager.Companion.Opcodes.CONVERSATION_AWARENESS -> 10
        AACPManager.Companion.Opcodes.PROXIMITY_KEYS_RSP -> 7
        AACPManager.Companion.Opcodes.AUDIO_SOURCE -> 13
        AACPManager.Companion.Opcodes.CONNECTED_DEVICES -> 9
        AACPManager.Companion.Opcodes.SMART_ROUTING_RESP -> 12
        AACPManager.Companion.Opcodes.INFORMATION -> 7
        AACPManager.Companion.Opcodes.CUSTOM_EQ -> 13
        AACPManager.Companion.Opcodes.HEADTRACKING -> 70
        AACPManager.Companion.Opcodes.HEADPHONE_ACCOMMODATION -> 140
        else -> 6
    }
    return packet.size >= minimumSize
}
