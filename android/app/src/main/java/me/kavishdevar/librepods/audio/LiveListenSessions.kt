package me.kavishdevar.librepods.audio

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class LiveListenPhase { IDLE, STARTING, LISTENING, STOPPING }
internal data class LiveListenState(
    val phase: LiveListenPhase = LiveListenPhase.IDLE,
    val error: LiveListenError? = null
)

/** The old worker must release its microphone before another session can start. */
internal class LiveListenSessions {
    internal class Lease(val id: Long) {
        private val running = AtomicBoolean(true)
        val active: Boolean get() = running.get()
        @Volatile var error: LiveListenError? = null
            private set

        @Synchronized fun stop(reason: LiveListenError? = null) {
            if (running.getAndSet(false)) error = reason
        }
    }

    private val mutableState = MutableStateFlow(LiveListenState())
    val state = mutableState.asStateFlow()
    private var current: Lease? = null
    private var nextId = 0L

    @Synchronized fun begin(): Lease? {
        if (current != null) return null
        val lease = Lease(++nextId)
        current = lease
        mutableState.value = LiveListenState(LiveListenPhase.STARTING)
        return lease
    }

    @Synchronized fun find(id: Long): Lease? = current?.takeIf { it.id == id }

    @Synchronized fun listening(lease: Lease) {
        if (current === lease && lease.active) mutableState.value = LiveListenState(LiveListenPhase.LISTENING)
    }

    @Synchronized fun stop(): Lease? {
        current?.stop()
        if (current != null) mutableState.value = LiveListenState(LiveListenPhase.STOPPING)
        return current
    }

    @Synchronized fun stop(lease: Lease, reason: LiveListenError) {
        if (current !== lease) return
        lease.stop(reason)
        mutableState.value = LiveListenState(LiveListenPhase.STOPPING)
    }

    @Synchronized fun finish(lease: Lease) {
        if (current !== lease) return
        current = null
        mutableState.value = LiveListenState(error = lease.error)
    }
}

internal object LiveListenController {
    val sessions = LiveListenSessions()
    val state = sessions.state
    @Volatile var gain = 1f
}
