package me.kavishdevar.librepods.data

/** An explicit device reply takes precedence over a cached preference. */
internal fun isOffListeningModeAllowed(deviceValue: ByteArray?, fallback: Boolean): Boolean =
    deviceValue?.firstOrNull()?.let { it == 0x01.toByte() } ?: fallback

/** A saved option only describes the peer that supplied or received that setting. */
internal fun ownedOffListeningMode(value: Boolean?, owner: String, selected: String): Boolean {
    val address = batteryHistoryIdentity(selected) ?: return true
    return if (batteryHistoryIdentity(owner) == address) value ?: true else true
}

/** Canonical visual/cycle order, independent of the protocol's numeric mode ordering. */
internal fun availableListeningModes(capabilities: Set<Capability>?, allowOff: Boolean): List<Int> {
    if (capabilities == null || Capability.LISTENING_MODE !in capabilities) return emptyList()
    return buildList {
        if (allowOff) add(1)
        add(3)
        if (Capability.ADAPTIVE_AUDIO in capabilities) add(4)
        add(2)
    }
}

internal fun nextListeningMode(current: Int, available: List<Int>): Int? =
    if (available.isEmpty()) null else available[(available.indexOf(current) + 1) % available.size]

/** A cached model cannot authorize controls for a different selected device. */
internal fun ownedListeningCapabilities(model: String, owner: String, selected: String): Set<Capability>? {
    return ownedAirPodsModel(model, owner, selected)?.capabilities
}
