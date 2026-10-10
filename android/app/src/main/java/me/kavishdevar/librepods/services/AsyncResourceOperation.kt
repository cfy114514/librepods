package me.kavishdevar.librepods.services

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** A cancelled/late callback must release its resource even when its owner has stopped. */
internal class AsyncResourceOperation<T : Any>(
    private val resource: T, private val dispatcher: CoroutineDispatcher,
    private val release: (T) -> Unit, private val onError: (Exception) -> Unit = {},
    private val onFinished: () -> Unit = {}
) : Closeable {
    private val lock = Any()
    private val released = AtomicBoolean()
    @Volatile private var closed = false
    private var job: Job? = null

    fun start(scope: CoroutineScope, valid: () -> Boolean, action: (T) -> Unit): Boolean {
        val work = synchronized(lock) {
            if (closed || job != null) return false
            scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                try { if (!closed && valid()) action(resource) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError(error) }
            }.also { job = it }
        }
        // Completion also runs for a lazy coroutine cancelled before its first instruction.
        work.invokeOnCompletion { releaseOnce() }
        work.start()
        return true
    }

    override fun close() {
        val work = synchronized(lock) { closed = true; job }
        if (work == null) releaseOnce() else work.cancel()
    }

    private fun releaseOnce() {
        if (!released.compareAndSet(false, true)) return
        // Do not close a proxy underneath an executing platform call. Completion waits for
        // that call; cleanup has no dependency on the cancelled service coroutine's job.
        CoroutineScope(NonCancellable + dispatcher).launch {
            try { release(resource) }
            catch (error: Exception) { onError(error) }
            finally { onFinished() }
        }
    }
}
