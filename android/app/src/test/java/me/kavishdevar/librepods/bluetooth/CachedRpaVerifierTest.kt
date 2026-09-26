package me.kavishdevar.librepods.bluetooth

import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.kavishdevar.librepods.utils.BluetoothCryptography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedRpaVerifierTest {
    // Bluetooth ah vector, with the IRK in the existing verifier's little-endian order.
    private val key = "9b7d390aa610103405adc857a33402ec".hexToByteArray()
    private val encodedKey = Base64.getEncoder().encodeToString(key)
    private val address = "70:81:94:0D:FB:AA"

    @Test fun aRealRpaVectorRetainsItsValidationResult() {
        var validations = 0
        val verifier = CachedRpaVerifier(verifyRpa = { value, irk ->
            validations++
            BluetoothCryptography.verifyRPA(value, irk)
        })
        repeat(1_000) { assertTrue(verifier.verify(address, encodedKey)) }
        assertFalse(verifier.verify("70:81:94:0D:FB:AB", encodedKey))
        assertEquals(2, validations)
    }

    @Test fun aHundredThousandUnrelatedAdvertisementsNeedOneVerification() {
        var validations = 0
        val verifier = CachedRpaVerifier(verifyRpa = { value, irk ->
            validations++
            BluetoothCryptography.verifyRPA(value, irk)
        })
        repeat(100_000) { assertFalse(verifier.verify("70:81:94:0D:FB:AB", encodedKey)) }
        assertEquals(1, validations)
    }

    @Test fun rotatingOrRemovingIrkInvalidatesBothPositiveAndNegativeResults() {
        var validations = 0
        val verifier = CachedRpaVerifier(verifyRpa = { value, irk ->
            validations++
            BluetoothCryptography.verifyRPA(value, irk)
        })
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertTrue(verifier.verify(address, encodedKey))
        assertFalse(verifier.verify(address, otherKey))
        assertTrue(verifier.verify(address, encodedKey))
        assertFalse(verifier.verify(address, null))
        assertTrue(verifier.verify(address, encodedKey))
        assertEquals(4, validations)
    }

    @Test fun addressRetentionIsBoundedAndRecentEntriesRemainCached() {
        var validations = 0
        val verifier = CachedRpaVerifier(capacity = 3, verifyRpa = { _, _ ->
            validations++
            false
        })
        repeat(10_000) { verifier.verify("address-$it", encodedKey) }
        assertEquals(10_000, validations)
        (9_997..9_999).forEach { verifier.verify("address-$it", encodedKey) }
        assertEquals(10_000, validations)
        verifier.verify("address-0", encodedKey)
        assertEquals(10_001, validations)
        // The touch above keeps 9999 recent when address-0 evicts 9997.
        verifier.verify("address-9999", encodedKey)
        assertEquals(10_001, validations)
        verifier.verify("address-9997", encodedKey)
        assertEquals(10_002, validations)
    }

    @Test fun anInvalidKeyIsReportedOnceAndNeverReusesPreviouslyTrustedAddresses() {
        var errors = 0
        var validations = 0
        val verifier = CachedRpaVerifier(
            verifyRpa = { _, _ -> validations++; true },
            onInvalidKey = { errors++ }
        )
        assertTrue(verifier.verify(address, encodedKey))
        repeat(1_000) { assertFalse(verifier.verify(address, "!not-base64!")) }
        assertEquals(1, errors)
        assertEquals(1, validations)
        repeat(1_000) { assertFalse(verifier.verify(address, Base64.getEncoder().encodeToString(byteArrayOf(1)))) }
        assertEquals(2, errors)
        assertTrue(verifier.verify(address, encodedKey))
        assertEquals(2, validations)
    }

    @Test fun concurrentCallbacksShareOneValidationForTheSameAddressAndKey() {
        val validations = AtomicInteger()
        val verifier = CachedRpaVerifier(verifyRpa = { value, irk ->
            validations.incrementAndGet()
            BluetoothCryptography.verifyRPA(value, irk)
        })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = List(4) {
                executor.submit {
                    repeat(2_500) { assertTrue(verifier.verify(address, encodedKey)) }
                }
            }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, validations.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentKeyChangesCannotReuseAnotherKeysTrustDecision() {
        val verifier = CachedRpaVerifier()
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(16))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = List(4) { worker ->
                executor.submit {
                    repeat(250) {
                        if (worker % 2 == 0) assertTrue(verifier.verify(address, encodedKey))
                        else assertFalse(verifier.verify(address, otherKey))
                    }
                }
            }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
