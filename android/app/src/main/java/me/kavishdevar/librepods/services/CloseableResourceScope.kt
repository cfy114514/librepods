package me.kavishdevar.librepods.services

import java.io.Closeable
import java.util.concurrent.CancellationException

/** Owns blocking resources so cancellation can close them without waiting for their worker. */
internal class CloseableResourceScope : Closeable {
    @Volatile private var closed = false
    private val resources = linkedSetOf<Closeable>()

    // Status checks may happen under a connection-publication lock; do not acquire
    // this scope's lock there and invert the ordering used by whileOpen().
    val isClosed: Boolean get() = closed

    fun <T : Closeable> track(resource: T): T {
        synchronized(this) {
            if (!closed) {
                resources.add(resource)
                return resource
            }
        }
        runCatching { resource.close() }
        throw CancellationException("Resource scope is closed")
    }

    @Synchronized
    fun release(resource: Closeable) {
        resources.remove(resource)
    }

    @Synchronized
    fun ensureOpen() {
        if (closed) throw CancellationException("Resource scope is closed")
    }

    /** For short publication steps only; never perform blocking I/O inside this block. */
    @Synchronized
    fun <T> whileOpen(action: () -> T): T {
        ensureOpen()
        return action()
    }

    override fun close() {
        val toClose = synchronized(this) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        // One broken resource must not prevent cancellation of the others.
        toClose.forEach { runCatching { it.close() } }
    }
}
