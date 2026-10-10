package me.kavishdevar.librepods.services

/** Reuse text for the last identical packet. Callers serialize access with their history. */
internal class PacketLogFormatter(private val maximumMemoizedPacketBytes: Int = 1024) {
    private val hexFormat = HexFormat { upperCase = true; bytes.byteSeparator = " " }
    private var previousHex: String? = null
    private var previousSource: String? = null
    var received: String = ""
        private set
    var entry: String = ""
        private set

    init { require(maximumMemoizedPacketBytes > 0) }

    fun format(packet: ByteArray, source: String) {
        val hex = packet.toHexString(hexFormat)
        if (previousSource == source && previousHex == hex) return
        received = "Data received: $hex"
        entry = "$source: $hex"
        if (packet.size <= maximumMemoizedPacketBytes) {
            previousHex = hex
            previousSource = source
        } else clearIdentity()
    }

    private fun clearIdentity() {
        previousHex = null
        previousSource = null
    }

    fun clear() {
        clearIdentity()
        received = ""
        entry = ""
    }
}
