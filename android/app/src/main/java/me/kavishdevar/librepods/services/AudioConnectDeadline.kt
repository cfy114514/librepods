package me.kavishdevar.librepods.services

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounds explicit audio work even when the platform never delivers a profile. */
internal class AudioConnectDeadline(
    private val owner: AudioProfileOwnership, private val scope: CoroutineScope,
    private val timeoutMillis: Long = 10_000
) : Closeable {
    private class Timer(val ticket: AudioProfileOwnership.Ticket, val job: Job)
    private val lock = Any()
    private var timer: Timer? = null
    private var closed = false

    fun start(ticket: AudioProfileOwnership.Ticket) {
        if (ticket.operation != AudioProfileOwnership.Operation.EXPLICIT_CONNECT) return
        val (previous, current) = synchronized(lock) {
            if (closed || !owner.isCurrent(ticket) || timer?.ticket === ticket) return
            val next = Timer(ticket, scope.launch(start = CoroutineStart.LAZY) { delay(timeoutMillis) })
            (timer to next).also { timer = next }
        }
        previous?.job?.cancel()
        // Register after publication, including when the parent scope was already cancelled.
        current.job.invokeOnCompletion {
            synchronized(lock) {
                if (timer === current) {
                    if (!closed) owner.abandonExplicitConnect(ticket)
                    timer = null
                }
            }
        }
        current.job.start()
    }

    fun complete(ticket: AudioProfileOwnership.Ticket, profile: Int) {
        owner.completeExplicitProfile(ticket, profile)
        cancelFinished(ticket)
    }

    fun abandon(ticket: AudioProfileOwnership.Ticket) {
        owner.abandonExplicitConnect(ticket)
        cancelFinished(ticket)
    }

    private fun cancelFinished(ticket: AudioProfileOwnership.Ticket) {
        val retired = synchronized(lock) {
            val current = timer ?: return
            if (current.ticket !== ticket || owner.isCurrent(ticket)) return
            timer = null
            current.job
        }
        retired.cancel()
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            timer.also { timer = null }
        }
        retired?.let {
            owner.abandonExplicitConnect(it.ticket)
            it.job.cancel()
        }
    }
}
