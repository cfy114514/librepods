package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ExclusiveMicrophoneTest {
    @Test fun oldServiceCleanupNeverFreesNewServiceMicrophone() {
        val microphone = ExclusiveMicrophone()
        val first = Any()
        val second = Any()
        assertTrue(microphone.acquire(first))
        assertFalse(microphone.acquire(second))
        microphone.release(Any())
        assertFalse(microphone.acquire(second))
        microphone.release(first)
        assertTrue(microphone.acquire(second))
        microphone.release(first)
        assertFalse(microphone.acquire(Any()))
    }
    @Test fun stoppingBeforeServiceDeliveryDoesNotStrandCaptureOwnership() {
        val microphone = ExclusiveMicrophone()
        val cancelled = Any()
        val replacement = Any()
        assertTrue(microphone.acquire(cancelled))
        assertTrue(microphone.cancelPending(cancelled))
        assertTrue(microphone.acquire(replacement))
        assertFalse(microphone.claim(cancelled))
        assertTrue(microphone.claim(replacement))
        assertFalse(microphone.cancelPending(replacement))
        assertFalse(microphone.acquire(Any()))
        microphone.release(replacement)
        assertTrue(microphone.acquire(Any()))
    }
    @Test fun concurrentListenAndTranslationStartOnlyOneCaptureOwner() {
        val microphone = ExclusiveMicrophone()
        val gate = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) { workers.submit<Boolean> { gate.await(); microphone.acquire(Any()) } }
            gate.countDown()
            assertEquals(1, attempts.count { it.get() })
        } finally { workers.shutdownNow() }
    }
}
