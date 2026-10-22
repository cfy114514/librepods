package me.kavishdevar.librepods.services

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectionAttemptsTest {
    @Test fun disconnectBeforeQueuedWorkerStartsRejectsItsSocket() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val queued = attempts.begin()!!
        var closes = 0

        attempts.cancel()
        try {
            queued.resources.track(Closeable { closes++ })
            fail("A disconnected queued worker must not acquire a socket")
        } catch (_: CancellationException) { }
        assertEquals(1, closes)
        assertFalse(attempts.inProgress)
        assertFalse(attempts.isCurrent(queued))
        owner.close()
    }

    @Test fun disconnectUnblocksConnectAndRejectsLatePublication() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val connecting = attempts.begin()!!
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val published = AtomicBoolean()
        connecting.resources.track(Closeable { closed.countDown() })
        val worker = Thread {
            try {
                entered.countDown()
                closed.await()
                attempts.whileCurrent(connecting) { published.set(true) }
            } catch (_: CancellationException) { }
            finally { attempts.finish(connecting); finished.countDown() }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            attempts.cancel()
            assertTrue("Disconnect must unblock native connect", finished.await(5, TimeUnit.SECONDS))
            assertFalse(published.get())
        } finally {
            owner.close()
            worker.join(5000)
        }
    }

    @Test fun reconnectStartsBeforeCancelledWorkerFinishes() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val old = attempts.begin()!!
        assertNull(attempts.begin())

        attempts.cancel()
        val replacement = attempts.begin()
        assertNotNull("A cancelled connect must not consume the next reconnect", replacement)
        attempts.finish(old)
        assertTrue(attempts.inProgress)
        assertTrue(attempts.isCurrent(replacement!!))
        assertNull("Old completion must not release the replacement admission", attempts.begin())
        owner.close()
    }

    @Test fun deferredSocketCloseStillRejectsOldPublication() {
        val pending = mutableListOf<List<Closeable>>()
        val owner = CloseableResourceScope(closeResources = { pending.add(it) })
        val attempts = ConnectionAttempts(owner)
        val old = attempts.begin()!!
        var oldCloses = 0
        var newCloses = 0
        old.resources.track(Closeable { oldCloses++ })

        attempts.cancel()
        assertEquals(0, oldCloses)
        try {
            attempts.whileCurrent(old) { fail("Retired connect must not publish before deferred close") }
            fail("Retired connect was accepted")
        } catch (_: CancellationException) { }
        val replacement = attempts.begin()!!
        replacement.resources.track(Closeable { newCloses++ })
        while (pending.isNotEmpty()) pending.removeAt(0).forEach { it.close() }
        assertEquals(1, oldCloses)
        assertEquals(0, newCloses)
        assertTrue(attempts.isCurrent(replacement))
        owner.close()
        while (pending.isNotEmpty()) pending.removeAt(0).forEach { it.close() }
    }

    @Test fun finishedConnectionKeepsItsReaderUntilOwnerCloses() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val connected = attempts.begin()!!
        var closes = 0
        connected.resources.track(Closeable { closes++ })
        var published = false
        attempts.whileCurrent(connected) { published = true }

        attempts.finish(connected)
        attempts.cancel()
        assertTrue(published)
        assertFalse(attempts.inProgress)
        assertEquals(0, closes)
        owner.close()
        assertEquals(1, closes)
    }

    @Test fun serviceShutdownRejectsQueuedPublicationAndNewAdmission() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val queued = attempts.begin()!!
        owner.close()

        assertNull(attempts.begin())
        assertFalse(attempts.isCurrent(queued))
        try {
            attempts.whileCurrent(queued) { fail("Stopped service must not publish") }
            fail("Stopped service accepted publication")
        } catch (_: CancellationException) { }
    }

    @Test fun queuedConnectRechecksThePreferenceBeforePublication() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val enabled = AtomicBoolean(true)
        val queued = attempts.begin { enabled.get() }!!
        assertTrue(attempts.isCurrent(queued))

        enabled.set(false)
        assertFalse(attempts.isCurrent(queued))
        try {
            attempts.whileCurrent(queued) { fail("Disabled automatic connect must not publish") }
            fail("Queued connect ignored its revoked preference")
        } catch (_: CancellationException) { }
        owner.close()
    }

    @Test fun oldTakeoverCannotEnterAfterItsRequestWasRevoked() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val requests = AtomicLong()
        val oldRequest = requests.get()
        val current = { requests.get() == oldRequest }
        assertTrue(current()) // The source checked before a concurrent disconnect.

        attempts.cancel()
        requests.incrementAndGet()
        assertNull("Admission must retain the source request's guard", attempts.begin(current))
        assertFalse(attempts.inProgress)
        assertNotNull(attempts.begin { requests.get() == 1L })
        owner.close()
    }

    @Test fun transportFailureKeepsManualRoutingUntilTheUserRequestIsRevoked() {
        val owner = CloseableResourceScope()
        val attempts = ConnectionAttempts(owner)
        val requests = AtomicLong()
        val manualRequest = requests.get()
        val connection = attempts.begin { requests.get() == manualRequest }!!

        // EOF/failed startup retires transport resources, independently of user intent.
        connection.resources.close()
        assertTrue("Manual audio continuation must survive transport failure", attempts.isCurrent(connection))
        requests.incrementAndGet()
        assertFalse("Disconnect, peer change or ownership loss retires the user request", attempts.isCurrent(connection))
        attempts.finish(connection)
        owner.close()
    }

}
