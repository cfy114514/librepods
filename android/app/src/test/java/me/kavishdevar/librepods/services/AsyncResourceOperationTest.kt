package me.kavishdevar.librepods.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class AsyncResourceOperationTest {
    private fun fixture(test: (CoroutineScope, kotlinx.coroutines.CoroutineDispatcher) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { test(scope, dispatcher) } finally { scope.cancel(); dispatcher.close() }
    }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(2, TimeUnit.SECONDS)) }

    @Test fun successfulActionAndReleaseRunOffCallerAndOnlyOnce() = fixture { scope, io ->
        val caller = Thread.currentThread()
        val actions = AtomicInteger(); val closes = AtomicInteger(); val finished = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io,
            release = { assertNotSame(caller, Thread.currentThread()); closes.incrementAndGet() },
            onFinished = { finished.countDown() })
        assertTrue(operation.start(scope, { true }) { assertNotSame(caller, Thread.currentThread()); actions.incrementAndGet() })
        assertFalse(operation.start(scope, { true }) { fail("Duplicate start") })
        await(finished); operation.close(); operation.close()
        assertEquals(1, actions.get()); assertEquals(1, closes.get())
    }

    @Test fun cancelledParentBeforeStartStillReleasesResource() = fixture { scope, io ->
        scope.cancel()
        val closed = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io, release = { closed.countDown() })
        operation.start(scope, { true }) { fail("Cancelled action ran") }
        await(closed)
    }

    @Test fun closeBeforeStartSchedulesCleanupAndRejectsAction() = fixture { scope, io ->
        val closed = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io, release = { closed.countDown() })
        operation.close()
        assertFalse(operation.start(scope, { true }) { fail("Closed action ran") })
        await(closed)
    }

    @Test fun ownerClosedBeforeLateCallbackStillReleasesItsProxy() = fixture { scope, io ->
        val owner = CloseableResourceScope().apply { close() }
        val closed = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io, release = { closed.countDown() })
        assertThrows(java.util.concurrent.CancellationException::class.java) { owner.track(operation) }
        assertFalse(operation.start(scope, { true }) { fail("Late action ran") })
        await(closed)
    }

    @Test fun staleGuardSkipsActionButStillReleases() = fixture { scope, io ->
        val closed = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io, release = { closed.countDown() })
        operation.start(scope, { false }) { fail("Stale action ran") }
        await(closed)
    }

    @Test fun cancellingRunningActionDoesNotCloseProxyUnderneathIt() = fixture { scope, io ->
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val closed = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io, release = { closed.countDown() })
        try {
            operation.start(scope, { true }) { entered.countDown(); await(finish); assertEquals(1L, closed.count) }
            await(entered); operation.close(); scope.cancel()
            assertEquals(1L, closed.count)
            finish.countDown(); await(closed)
        } finally { finish.countDown() }
    }

    @Test fun actionAndCleanupFailuresStillRunCompletion() = fixture { scope, io ->
        val errors = AtomicInteger(); val completed = CountDownLatch(1)
        val operation = AsyncResourceOperation(Any(), io,
            release = { throw IllegalStateException("release") }, onError = { errors.incrementAndGet() },
            onFinished = { completed.countDown() })
        operation.start(scope, { true }) { throw IllegalStateException("action") }
        await(completed); assertEquals(2, errors.get())
    }

    @Test fun concurrentCloseWhileQueuedNeverRunsActionOrDuplicatesRelease() = fixture { scope, io ->
        val occupied = CountDownLatch(1); val releaseWorker = CountDownLatch(1)
        val closed = CountDownLatch(1); val closes = AtomicInteger()
        io.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { occupied.countDown(); await(releaseWorker) })
        await(occupied)
        val operation = AsyncResourceOperation(Any(), io, release = { closes.incrementAndGet(); closed.countDown() })
        val pool = Executors.newFixedThreadPool(4)
        try {
            operation.start(scope, { true }) { fail("Queued cancelled action ran") }
            val tasks = (1..100).map { pool.submit { operation.close() } }
            tasks.forEach { it.get(1, TimeUnit.SECONDS) }
            releaseWorker.countDown(); await(closed); assertEquals(1, closes.get())
        } finally { releaseWorker.countDown(); pool.shutdownNow() }
    }
}
