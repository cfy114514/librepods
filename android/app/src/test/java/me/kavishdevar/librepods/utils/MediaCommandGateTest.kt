package me.kavishdevar.librepods.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class MediaCommandGateTest {
    @Test fun unenteredPauseFailureAndCancellationAreNotReportedAsCapturedPause() {
        val errors = mutableListOf<Exception>(); val queryFailure = IllegalStateException("Query before pause")
        try { captureMediaPause(errors::add) { throw queryFailure }; fail("Query failure swallowed") }
        catch (t: Throwable) { assertSame(queryFailure, t) }
        val cancelled = kotlinx.coroutines.CancellationException("Retired job")
        try { captureMediaPause(errors::add) { entered -> entered(); throw cancelled }; fail("Cancellation swallowed") }
        catch (t: Throwable) { assertSame(cancelled, t) }
        assertTrue(errors.isEmpty())
    }
    @Test fun queuedRetiredCommandIsSkippedWhileNewOwnerWaitsForTheEnteredPair() {
        val gate = MediaCommandGate(); val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val oldValid = AtomicBoolean(true); val waiting = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errors = AtomicReference<Throwable?>()
        val active = thread { try { gate.run({ true }) {
            dispatchMediaKeyPair { down -> events.add(if (down) "old down" else "old up")
                if (down) { entered.countDown(); check(resume.await(3, TimeUnit.SECONDS)) } }
        } } catch (t: Throwable) { errors.set(t) } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val stale = thread { waiting.countDown(); gate.run(oldValid::get) { events.add("stale") } }
            assertTrue(waiting.await(3, TimeUnit.SECONDS)); oldValid.set(false)
            val fresh = thread { gate.run({ true }) { events.add("fresh") } }
            resume.countDown(); active.join(3000); stale.join(3000); fresh.join(3000)
            assertFalse(active.isAlive || stale.isAlive || fresh.isAlive)
            assertNull(errors.get()); assertEquals(listOf("old down", "old up", "fresh"), events)
        } finally { resume.countDown(); active.join(3000) }
    }
    @Test fun mutatedDownFailureStillReleasesKeyAndPreservesOriginalAndSuppressedErrors() {
        val first = IllegalStateException("DOWN mutated then failed")
        val second = IllegalArgumentException("UP failed")
        val events = mutableListOf<Boolean>()
        try {
            dispatchMediaKeyPair { down -> events.add(down); throw if (down) first else second }
            fail("Dispatch should fail")
        } catch (t: Throwable) { assertSame(first, t); assertEquals(listOf(second), t.suppressed.toList()) }
        assertEquals(listOf(true, false), events)
        val gate = MediaCommandGate()
        try { gate.run({ true }) { throw first } } catch (_: IllegalStateException) { }
        assertEquals("recovered", gate.run({ true }) { "recovered" })
    }
    @Test fun successfulDownWithFailingUpPropagatesFailureAndRetiredCommandsNeverDispatch() {
        val failure = IllegalStateException("UP")
        try { dispatchMediaKeyPair { if (!it) throw failure }; fail("UP failure missing") }
        catch (t: Throwable) { assertSame(failure, t) }
        var calls = 0
        assertNull(MediaCommandGate().run({ false }) { calls++ })
        assertEquals(0, calls)
    }
}
