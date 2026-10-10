package me.kavishdevar.librepods.utils

import java.io.Closeable

/** Caller serializes offer, scheduled actions and close; retain one latest pending value. */
internal class LatestCallbackThrottle<T : Any>(
    private val intervalMillis: Long,
    private val nowMillis: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val consume: (T) -> Unit
) : Closeable {
    private var lastDispatchAt: Long? = null
    private var pending: T? = null
    private var cancelScheduled: (() -> Unit)? = null
    private var generation = 0L
    private var closed = false

    init { require(intervalMillis > 0) }

    fun offer(value: T): Boolean {
        if (closed) return false
        pending = value
        val remaining = remainingDelay()
        if (remaining == 0L) {
            cancelPendingAction()
            dispatch()
        } else if (cancelScheduled == null) schedulePending(remaining)
        return true
    }

    private fun remainingDelay(): Long {
        val previous = lastDispatchAt ?: return 0
        val elapsed = nowMillis() - previous
        return if (elapsed < 0 || elapsed >= intervalMillis) 0 else intervalMillis - elapsed
    }

    private fun schedulePending(delay: Long) {
        val token = ++generation
        cancelScheduled = schedule(delay) {
            if (!closed && token == generation) {
                cancelScheduled = null
                val remaining = remainingDelay()
                if (remaining > 0) schedulePending(remaining) else dispatch()
            }
        }
    }

    private fun dispatch() {
        val value = pending ?: return
        pending = null
        lastDispatchAt = nowMillis()
        consume(value)
    }

    private fun cancelPendingAction() {
        generation++
        cancelScheduled?.invoke()
        cancelScheduled = null
    }

    override fun close() {
        closed = true
        pending = null
        cancelPendingAction()
    }
}
