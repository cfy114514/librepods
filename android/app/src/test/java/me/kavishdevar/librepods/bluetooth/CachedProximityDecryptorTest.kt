package me.kavishdevar.librepods.bluetooth

import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedProximityDecryptorTest {
    private val key = "000102030405060708090a0b0c0d0e0f".hexToByteArray()
    private val encodedKey = Base64.getEncoder().encodeToString(key)
    private val plaintext = "00112233445566778899aabbccddeeff".hexToByteArray()
    private val ciphertext = "69c4e0d86a7b0430d8cdb78070b4c55a".hexToByteArray()

    private fun cipher(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }

    @Test fun repeatedAdvertisementsDecryptTheRealAesVectorWithOneCipherInitialization() {
        var initializations = 0
        val decryptor = CachedProximityDecryptor(createCipher = {
            initializations++
            cipher(Cipher.DECRYPT_MODE, it)
        })
        val advertisement = ByteArray(11) { 0x07 } + ciphertext
        val original = advertisement.copyOf()
        repeat(10_000) { assertArrayEquals(plaintext, decryptor.decryptLastBlock(advertisement, encodedKey)) }
        assertEquals(1, initializations)
        assertArrayEquals(original, advertisement)
    }

    @Test fun keyRotationAndRemovalDoNotReuseThePreviousCipher() {
        var initializations = 0
        val decryptor = CachedProximityDecryptor(createCipher = {
            initializations++
            cipher(Cipher.DECRYPT_MODE, it)
        })
        val nextKey = ByteArray(16) { (it + 16).toByte() }
        val nextEncodedKey = Base64.getEncoder().encodeToString(nextKey)
        val nextCiphertext = cipher(Cipher.ENCRYPT_MODE, nextKey).doFinal(plaintext)
        assertArrayEquals(plaintext, decryptor.decryptLastBlock(ciphertext, encodedKey))
        assertArrayEquals(plaintext, decryptor.decryptLastBlock(nextCiphertext, nextEncodedKey))
        assertNull(decryptor.decryptLastBlock(nextCiphertext, null))
        assertArrayEquals(plaintext, decryptor.decryptLastBlock(ciphertext, encodedKey))
        assertEquals(3, initializations)
    }

    @Test fun invalidKeysFallBackWithoutRepeatedInitializationOrRepeatedErrors() {
        var initializations = 0
        var errors = 0
        val decryptor = CachedProximityDecryptor(
            createCipher = { initializations++; cipher(Cipher.DECRYPT_MODE, it) },
            onError = { errors++ }
        )
        assertArrayEquals(plaintext, decryptor.decryptLastBlock(ciphertext, encodedKey))
        repeat(1_000) { assertNull(decryptor.decryptLastBlock(ciphertext, "!not-base64!")) }
        assertEquals(1, errors)
        assertEquals(1, initializations)
        val shortKey = Base64.getEncoder().encodeToString(byteArrayOf(1))
        repeat(1_000) { assertNull(decryptor.decryptLastBlock(ciphertext, shortKey)) }
        assertEquals(2, errors)
        assertEquals(2, initializations)
        assertArrayEquals(plaintext, decryptor.decryptLastBlock(ciphertext, encodedKey))
        assertEquals(3, initializations)
    }

    @Test fun incompletePacketsAndMissingKeysDoNotInitializeCrypto() {
        var initializations = 0
        val decryptor = CachedProximityDecryptor(createCipher = {
            initializations++
            cipher(Cipher.DECRYPT_MODE, it)
        })
        for (length in 0 until 16) {
            assertNull(decryptor.decryptLastBlock(ByteArray(length), encodedKey))
        }
        assertNull(decryptor.decryptLastBlock(ciphertext, null))
        assertEquals(0, initializations)
    }

    @Test fun concurrentDifferentPacketsReuseTheCipherWithoutMixingTheirPlaintext() {
        val initializations = AtomicInteger()
        val decryptor = CachedProximityDecryptor(createCipher = {
            initializations.incrementAndGet()
            cipher(Cipher.DECRYPT_MODE, it)
        })
        val messages = List(4) { worker -> ByteArray(16) { (it + worker).toByte() } }
        val packets = messages.map { cipher(Cipher.ENCRYPT_MODE, key).doFinal(it) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = List(4) { worker ->
                executor.submit {
                    repeat(1_000) {
                        assertArrayEquals(messages[worker], decryptor.decryptLastBlock(packets[worker], encodedKey))
                    }
                }
            }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, initializations.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentKeyChangesCannotDecryptUsingAnotherCallsKey() {
        val decryptor = CachedProximityDecryptor()
        val nextKey = ByteArray(16) { (it + 16).toByte() }
        val keys = listOf(encodedKey, Base64.getEncoder().encodeToString(nextKey))
        val packets = listOf(ciphertext, cipher(Cipher.ENCRYPT_MODE, nextKey).doFinal(plaintext))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = List(4) { worker ->
                executor.submit {
                    repeat(250) {
                        val index = worker % 2
                        assertArrayEquals(plaintext, decryptor.decryptLastBlock(packets[index], keys[index]))
                    }
                }
            }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
