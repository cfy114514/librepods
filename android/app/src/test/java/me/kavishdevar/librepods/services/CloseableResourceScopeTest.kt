package me.kavishdevar.librepods.services

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloseableResourceScopeTest {
    @Test
    fun closingScopeUnblocksAnInFlightBlockingOperation() {
        val scope = CloseableResourceScope()
        val entered = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        scope.track(Closeable { connectionClosed.countDown() })
        val worker = Thread {
            entered.countDown()
            connectionClosed.await()
            finished.countDown()
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            scope.close()
            assertTrue("Closing the socket/process must release its blocked worker", finished.await(5, TimeUnit.SECONDS))
        } finally {
            scope.close()
            worker.join(5000)
        }
    }

    @Test
    fun resourceCreatedAfterCancellationIsImmediatelyClosed() {
        val scope = CloseableResourceScope()
        var closes = 0
        scope.close()
        try {
            scope.track(Closeable { closes++ })
            fail("A stopped service cannot accept a new resource")
        } catch (_: CancellationException) {
            assertEquals(1, closes)
        }
    }

    @Test
    fun completingOldAttemptDoesNotCloseANewerConnection() {
        val service = CloseableResourceScope()
        val oldAttempt = service.track(CloseableResourceScope())
        val newAttempt = service.track(CloseableResourceScope())
        var oldCloses = 0
        var newCloses = 0
        oldAttempt.track(Closeable { oldCloses++ })
        newAttempt.track(Closeable { newCloses++ })

        oldAttempt.close()
        service.release(oldAttempt)
        oldAttempt.close() // A late worker completion repeats cleanup.
        assertEquals(1, oldCloses)
        assertEquals(0, newCloses)
        assertFalse(newAttempt.isClosed)

        service.close()
        assertEquals(1, oldCloses)
        assertEquals(1, newCloses)
    }

    @Test
    fun oneFailingCloseDoesNotLeakOtherPendingResources() {
        val scope = CloseableResourceScope()
        var closed = false
        scope.track(Closeable { error("Broken socket close") })
        scope.track(Closeable { closed = true })
        scope.close()
        assertTrue(closed)
    }

    @Test
    fun cancelledAttemptCannotPublishItsConnection() {
        val scope = CloseableResourceScope()
        var published = false
        scope.close()
        try {
            scope.whileOpen { published = true }
            fail("Publishing after cancellation must be rejected")
        } catch (_: CancellationException) {
            assertFalse(published)
        }
    }

    @Test
    fun finishedProcessesCanBeReleasedBeforeServiceDestruction() {
        val scope = CloseableResourceScope()
        var closes = 0
        val process = scope.track(Closeable { closes++ })
        process.close()
        scope.release(process)
        scope.close()
        assertEquals(1, closes)
    }
}
