package me.kavishdevar.librepods.utils

import java.io.Closeable

/** Caller serializes samples, scheduled actions and close with the sample-state monitor. */
internal class SampleDetectionSchedule(
    private val intervalMillis: Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val consume: () -> Unit
) : Closeable {
    private var pending = false
    private var closed = false
    private var generation = 0L
    private var cancelScheduled: (() -> Unit)? = null

    init { require(intervalMillis > 0) }

    fun offer(): Boolean {
        if (closed || pending) return false
        pending = true
        val token = ++generation
        try {
            cancelScheduled = schedule(intervalMillis) {
                if (!closed && pending && token == generation) {
                    pending = false
                    cancelScheduled = null
                    consume()
                }
            }
        } catch (error: Exception) {
            pending = false
            cancelScheduled = null
            generation++
            throw error
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = false
        generation++
        val cancel = cancelScheduled
        cancelScheduled = null
        cancel?.invoke()
    }
}
