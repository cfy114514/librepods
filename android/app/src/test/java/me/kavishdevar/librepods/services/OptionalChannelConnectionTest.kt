package me.kavishdevar.librepods.services

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OptionalChannelConnectionTest {
    private class Resource : Closeable {
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        override fun close() { closes.incrementAndGet(); closed.countDown() }
        fun blockIgnoringInterrupt() {
            while (true) {
                try { if (closed.await(20, TimeUnit.MILLISECONDS)) throw IOException("Channel closed") }
                catch (_: InterruptedException) { /* Native connects may ignore interruption. */ }
            }
        }
    }
    @Test fun timeoutClosesOnlyTheOptionalChannelAndLeavesTheOwnerUsable() = runBlocking {
        val owner = CloseableResourceScope()
        val primary = owner.track(Resource())
        val auxiliary = Resource()
        var publishes = 0
        val result = connectOptionalChannel(owner, 40,
            connect = { it.track(auxiliary).also { resource -> resource.blockIgnoringInterrupt() } },
            publish = { publishes++; true })
        assertNull(result)
        assertEquals(0, publishes)
        assertEquals(1, auxiliary.closes.get())
        assertEquals(0, primary.closes.get())
        assertFalse(owner.isClosed)
        val later = Resource()
        assertSame(later, connectOptionalChannel(owner, 1000, { it.track(later) }, { true }))
        owner.close()
        assertEquals(1, primary.closes.get())
        assertEquals(1, later.closes.get())
    }
    @Test fun successfulChannelFollowsThePrimaryLifetimeAndClosesOnce() = runBlocking {
        val owner = CloseableResourceScope()
        val resource = Resource()
        assertSame(resource, connectOptionalChannel(owner, 1000, { it.track(resource) }, { true }))
        assertEquals(0, resource.closes.get())
        owner.close(); owner.close()
        assertEquals(1, resource.closes.get())
    }
    @Test fun rejectedPublicationClosesTheAuxiliaryWithoutClosingThePrimary() = runBlocking {
        val owner = CloseableResourceScope()
        val primary = owner.track(Resource())
        val auxiliary = Resource()
        assertNull(connectOptionalChannel(owner, 1000, { it.track(auxiliary) }, { false }))
        assertEquals(1, auxiliary.closes.get())
        assertEquals(0, primary.closes.get())
        owner.close()
    }
    @Test fun closingOwnerDuringConnectPreventsAnyLatePublication() = runBlocking {
        val owner = CloseableResourceScope()
        val auxiliary = Resource()
        val entered = CountDownLatch(1)
        val publishes = AtomicInteger()
        val work = async(Dispatchers.Default) {
            connectOptionalChannel(owner, 5000, { resources ->
                resources.track(auxiliary).also { entered.countDown(); it.blockIgnoringInterrupt() }
            }, { publishes.incrementAndGet(); true })
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        owner.close()
        assertNull(work.await())
        assertEquals(0, publishes.get())
        assertEquals(1, auxiliary.closes.get())
    }
    @Test fun cancellationUnblocksAnInterruptIgnoringConnectBeforeItsDeadline() = runBlocking {
        val owner = CloseableResourceScope()
        val primary = owner.track(Resource())
        val auxiliary = Resource()
        val entered = CountDownLatch(1)
        val work = async(Dispatchers.Default) {
            connectOptionalChannel(owner, 5000, { resources ->
                resources.track(auxiliary).also { entered.countDown(); it.blockIgnoringInterrupt() }
            }, { fail("Canceled channel was published"); true })
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val started = System.nanoTime()
        work.cancelAndJoin()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
        assertEquals(1, auxiliary.closes.get())
        assertEquals(0, primary.closes.get())
        owner.close()
    }
    @Test fun closedOwnerDoesNotStartAnotherConnection() = runBlocking {
        val owner = CloseableResourceScope().also { it.close() }
        assertNull(connectOptionalChannel(owner, 1000, { fail("Connector ran"); Resource() }, { true }))
    }
}
