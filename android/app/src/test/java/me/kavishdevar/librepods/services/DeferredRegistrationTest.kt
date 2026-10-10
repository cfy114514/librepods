package me.kavishdevar.librepods.services

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DeferredRegistrationTest {
    @Test fun successfulRegistrationUnregistersOnceAfterClose() {
        val count = AtomicInteger(); val registration = DeferredRegistration { count.incrementAndGet() }
        assertTrue(registration.didRegister()); assertEquals(0, count.get())
        registration.close(); registration.close()
        assertTrue(registration.isClosed); assertEquals(1, count.get())
    }
    @Test fun closeDuringRegistrationDisposesItsLateResult() {
        val count = AtomicInteger(); val registration = DeferredRegistration { count.incrementAndGet() }
        registration.close(); assertEquals(0, count.get())
        assertFalse(registration.didRegister()); registration.close(); assertEquals(1, count.get())
    }
    @Test fun failedRegistrationDoesNotPretendThereIsANativeResource() {
        val count = AtomicInteger(); val registration = DeferredRegistration { count.incrementAndGet() }
        registration.close(); registration.close(); assertEquals(0, count.get())
    }
    @Test fun concurrentRetirementAndRegistrationStillUnregisterOnlyOnce() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(100) {
                val count = AtomicInteger(); val registration = DeferredRegistration { count.incrementAndGet() }
                val tasks = (1..20).map { pool.submit { registration.close() } }
                registration.didRegister(); tasks.forEach { it.get(1, TimeUnit.SECONDS) }
                assertTrue(registration.isClosed); assertEquals(1, count.get())
            }
        } finally { pool.shutdownNow() }
    }
    @Test fun cleanupCanReenterLifecycleWithoutHoldingStateLock() {
        lateinit var registration: DeferredRegistration
        val count = AtomicInteger()
        registration = DeferredRegistration { count.incrementAndGet(); registration.close() }
        registration.didRegister(); registration.close(); assertEquals(1, count.get())
    }
}
