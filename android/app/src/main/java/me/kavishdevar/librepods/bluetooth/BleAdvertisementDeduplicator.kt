package me.kavishdevar.librepods.bluetooth

/** Ignore repeated addresses only while processing a single scan batch. */
internal class BleAdvertisementDeduplicator {
    private var batchAddresses: MutableSet<String>? = null

    fun <T> inBatch(block: () -> T): T {
        val previousBatch = batchAddresses
        batchAddresses = HashSet()
        return try {
            block()
        } finally {
            batchAddresses = previousBatch
        }
    }

    fun wasProcessed(address: String): Boolean = batchAddresses?.contains(address) == true

    fun markProcessed(address: String) {
        batchAddresses?.add(address)
    }
}
