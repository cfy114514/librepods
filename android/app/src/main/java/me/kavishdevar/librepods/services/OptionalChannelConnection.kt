package me.kavishdevar.librepods.services

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** An auxiliary channel owns its deadline; failure cannot close the primary connection. */
internal suspend fun <T : Closeable> connectOptionalChannel(
    owner: CloseableResourceScope,
    timeoutMillis: Long,
    connect: (CloseableResourceScope) -> T,
    publish: (T) -> Boolean,
    onError: (Exception) -> Unit = {}
): T? = coroutineScope {
    require(timeoutMillis > 0)
    val channel = try { owner.track(owner.createChildScope()) }
        catch (_: CancellationException) { return@coroutineScope null }
    val published = AtomicBoolean(false)
    val connectionFinished = AtomicBoolean(false)
    // Cancellation must close native connects that ignore thread interruption.
    val cancellationCloser = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!published.get()) channel.close() }
    }
    val deadline = launch {
        delay(timeoutMillis)
        if (connectionFinished.compareAndSet(false, true)) channel.close()
    }
    try {
        val resource = runInterruptible(Dispatchers.IO) { connect(channel) }
        if (!connectionFinished.compareAndSet(false, true)) return@coroutineScope null
        deadline.cancel()
        val accepted = owner.whileOpen {
            channel.ensureOpen()
            publish(resource).also { if (it) published.set(true) }
        }
        if (accepted) resource else null
    } catch (error: CancellationException) {
        if (!currentCoroutineContext().isActive || !channel.isClosed) throw error
        null
    } catch (error: Exception) {
        onError(error)
        null
    } finally {
        deadline.cancel()
        cancellationCloser.cancel()
        if (!published.get()) {
            channel.close()
            owner.release(channel)
        }
    }
}
