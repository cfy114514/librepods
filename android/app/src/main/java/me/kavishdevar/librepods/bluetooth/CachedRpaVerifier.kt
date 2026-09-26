package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.utils.BluetoothCryptography
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** RPA validation depends only on the address and IRK, not on advertisement contents. */
@OptIn(ExperimentalEncodingApi::class)
internal class CachedRpaVerifier(
    private val capacity: Int = 256,
    private val verifyRpa: (String, ByteArray) -> Boolean = BluetoothCryptography::verifyRPA,
    private val onInvalidKey: (Exception) -> Unit = {}
) {
    private var encodedIrk: String? = null
    private var irk: ByteArray? = null
    private val addresses = LinkedHashMap<String, Boolean>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun verify(address: String, currentEncodedIrk: String?): Boolean {
        if (encodedIrk != currentEncodedIrk) {
            encodedIrk = currentEncodedIrk
            addresses.clear()
            irk = try {
                currentEncodedIrk?.let { encoded ->
                    Base64.decode(encoded).also { require(it.size == 16) { "IRK must contain 16 bytes" } }
                }
            } catch (e: Exception) {
                onInvalidKey(e)
                null
            }
        }
        val key = irk ?: return false
        addresses[address]?.let { return it }
        val verified = verifyRpa(address, key)
        addresses[address] = verified
        if (addresses.size > capacity) {
            addresses.entries.iterator().apply { next(); remove() }
        }
        return verified
    }
}
