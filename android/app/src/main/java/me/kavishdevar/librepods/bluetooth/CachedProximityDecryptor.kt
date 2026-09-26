package me.kavishdevar.librepods.bluetooth

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Reuse the initialized cipher while the configured proximity key remains unchanged. */
@OptIn(ExperimentalEncodingApi::class)
internal class CachedProximityDecryptor(
    private val createCipher: (ByteArray) -> Cipher = { key ->
        // The AirPods proximity wire format encrypts a single AES-ECB block.
        @Suppress("GetInstance")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher
    },
    private val onError: (Exception) -> Unit = {}
) {
    private var encodedKey: String? = null
    private var cipher: Cipher? = null

    @Synchronized
    fun decryptLastBlock(data: ByteArray, currentEncodedKey: String?): ByteArray? {
        if (data.size < 16) return null
        if (encodedKey != currentEncodedKey) {
            encodedKey = currentEncodedKey
            cipher = try {
                currentEncodedKey?.let { createCipher(Base64.decode(it)) }
            } catch (e: Exception) {
                onError(e)
                null
            }
        }
        val currentCipher = cipher ?: return null
        return try {
            // doFinal resets a successful cipher to its initialized state. Its offset
            // overload avoids copying the ciphertext out of the advertisement first.
            currentCipher.doFinal(data, data.size - 16, 16)
        } catch (e: Exception) {
            // A provider failure may leave cipher state undefined; rebuild on the next packet.
            encodedKey = null
            cipher = null
            onError(e)
            null
        }
    }
}
