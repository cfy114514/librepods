package me.kavishdevar.librepods.services

import java.io.Closeable
import java.util.concurrent.CancellationException

/** Owns blocking resources so cancellation can close them without waiting for their worker. */
internal class CloseableResourceScope(
    private val closeResources: (List<Closeable>) -> Unit = { list -> list.forEach { runCatching { it.close() } } },
    private val parent: CloseableResourceScope? = null
) : Closeable {
    @Volatile private var closed = false
    private val resources = linkedSetOf<Closeable>()

    // Status checks may happen under a connection-publication lock; do not acquire
    // this scope's lock there and invert the ordering used by whileOpen().
    val isClosed: Boolean get() = closed || parent?.isClosed == true

    fun createChildScope() = CloseableResourceScope(closeResources, this)

    fun <T : Closeable> track(resource: T): T {
        synchronized(this) {
            if (!isClosed) {
                resources.add(resource)
                return resource
            }
        }
        dispose(listOf(resource))
        throw CancellationException("Resource scope is closed")
    }

    @Synchronized
    fun release(resource: Closeable) {
        resources.remove(resource)
    }

    @Synchronized
    fun ensureOpen() {
        if (isClosed) throw CancellationException("Resource scope is closed")
    }

    /** For short publication steps only; never perform blocking I/O inside this block. */
    fun <T> whileOpen(action: () -> T): T {
        // Lock ancestors before children, matching connection publication order.
        val owner = parent
        return if (owner == null) whileSelfOpen(action) else owner.whileOpen { whileSelfOpen(action) }
    }

    @Synchronized
    private fun <T> whileSelfOpen(action: () -> T): T {
        ensureOpen()
        return action()
    }

    override fun close() {
        val toClose = synchronized(this) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        dispose(toClose)
    }

    private fun dispose(resources: List<Closeable>) {
        if (resources.isEmpty()) return
        // A failed handoff must not orphan the already revoked snapshot.
        try { closeResources(resources) }
        catch (_: Throwable) { resources.forEach { runCatching { it.close() } } }
    }
}
