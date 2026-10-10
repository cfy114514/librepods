package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface ConversationVolumeBackend {
    fun volume(): Int
    fun maximum(): Int
    fun set(value: Int)
}

/** One IO owner of attenuation, including restoration before a replacement reads its base. */
internal class ConversationVolumeSession(
    dispatcher: CoroutineDispatcher,
    private val onError: (Exception) -> Unit = {},
    schedule: ((Runnable, Long) -> Unit)? = null,
    unschedule: ((Runnable) -> Unit)? = null
) : Closeable {
    private enum class Kind { INPUT, TICK }
    private sealed interface Action {
        data object Idle : Action
        data class Lower(val readSettings: () -> ConversationVolumeSettings) : Action
        data class Restore(val resume: Boolean) : Action
    }
    private class Input(val lease: Lease?, val action: Action)
    private data class Attenuation(val lease: Lease, val base: Int, var paused: Boolean = false,
        var restoring: Boolean = false, var touched: Boolean = false,
        var endHandled: Boolean = false, var finishedRamp: Boolean = false)
    private val lock = Any()
    private var desired = Input(null, Action.Idle)
    private var shutdown = false
    private var failed = false
    @Volatile private var actual: Attenuation? = null // Mutated only by the IO worker.
    private var activeRequest: Input? = null
    private var fadeInput: Input? = null
    private var fadeTarget = 0
    @Volatile private var currentTick: Runnable? = null
    private val timerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var timer: Pair<Runnable, Job>? = null
    private val worker = KeyedWorkSession<Kind, Runnable>(Kind.entries.toSet(), dispatcher,
        onError = { error ->
            synchronized(lock) { if (desired === activeRequest) failed = true }
            onError(error)
        }) { action -> activeRequest = null; action.run() }
    private val transition = VolumeTransition(
        setVolume = { value ->
            val input = fadeInput
            val state = actual
            if (input != null && state != null && isCurrent(input)) {
                activeRequest = input
                // Retain the base even if an SDK setter changes volume and then throws.
                state.touched = true
                state.lease.backend.set(value)
                if (state.restoring && value == fadeTarget) {
                    state.finishedRamp = true
                    finishIfRestored(state)
                }
            }
        },
        schedule = { action, milliseconds ->
            currentTick = action
            if (schedule != null) schedule(action, milliseconds)
            else timer = action to timerScope.launch {
                delay(milliseconds)
                tick(action)
            }
        },
        unschedule = { action ->
            if (currentTick === action) currentTick = null
            if (unschedule != null) unschedule(action)
            else if (timer?.first === action) { timer?.second?.cancel(); timer = null }
        }
    )

    internal class Lease internal constructor(
        private val parent: ConversationVolumeSession,
        internal val backend: ConversationVolumeBackend,
        internal val valid: () -> Boolean,
        internal val onBegin: (Boolean, () -> Boolean) -> Boolean,
        internal val onEnd: (Boolean, Boolean, () -> Boolean) -> Boolean,
        private val readSettings: () -> ConversationVolumeSettings
    ) : Closeable {
        @Volatile internal var closed = false
        fun start(relative: Boolean, percent: Int, pause: Boolean) {
            parent.offer(this, Action.Lower { ConversationVolumeSettings(relative, percent.coerceIn(0, 100), pause) })
        }
        fun start() { parent.offer(this, Action.Lower(readSettings)) }
        fun stop(resume: Boolean) { parent.offer(this, Action.Restore(resume)) }
        override fun close() { parent.release(this) }
    }

    fun claim(backend: ConversationVolumeBackend, valid: () -> Boolean,
        onBegin: (Boolean, () -> Boolean) -> Boolean = { _, _ -> false },
        onEnd: (Boolean, Boolean, () -> Boolean) -> Boolean = { _, _, _ -> true },
        readSettings: () -> ConversationVolumeSettings = { ConversationVolumeSettings() }): Lease {
        val lease = Lease(this, backend, valid, onBegin, onEnd, readSettings)
        synchronized(lock) {
            check(!shutdown)
            desired.lease?.closed = true
            desired = Input(lease, Action.Idle)
            failed = false
        }
        signal()
        return lease
    }
    private fun offer(lease: Lease, action: Action) {
        synchronized(lock) {
            if (shutdown || lease.closed || desired.lease !== lease || !lease.valid()) return
            // Duplicate speech-start events cannot restart the ramp or recapture its base.
            if (!failed && (desired.action == action || desired.action is Action.Lower && action is Action.Lower)) return
            desired = Input(lease, action); failed = false
        }
        signal()
    }
    private fun release(lease: Lease) {
        synchronized(lock) {
            if (lease.closed) return
            lease.closed = true
            if (desired.lease !== lease) return
            desired = Input(null, Action.Idle)
        }
        signal()
    }
    private fun signal() { worker.offer(Kind.INPUT, Runnable {
        try { consumeInput() }
        finally {
            if (synchronized(lock) { shutdown }) {
                timerScope.cancel(); worker.close(); actual = null
            }
        }
    }) }
    internal fun tick(action: Runnable) {
        worker.offerIf(Kind.TICK, action) { currentTick === action }
    }
    private fun isCurrent(input: Input): Boolean = synchronized(lock) {
        !shutdown && desired === input && input.lease?.let { !it.closed && it.valid() } == true
    }
    private fun consumeInput() {
        transition.cancel(); fadeInput = null
        val input = synchronized(lock) { desired }
        activeRequest = input
        val lease = input.lease
        val previous = actual
        if (previous != null && (previous.lease !== lease || lease.closed || !lease.valid())) {
            if (previous.touched) previous.lease.backend.set(previous.base)
            actual = null
        }
        if (lease == null || !isCurrent(input)) return
        when (val action = input.action) {
            Action.Idle -> Unit
            is Action.Lower -> {
                val settings = action.readSettings()
                if (!isCurrent(input)) return
                val state = actual
                val current = lease.backend.volume()
                if (!isCurrent(input)) return
                val maximum = lease.backend.maximum().coerceAtLeast(0)
                if (!isCurrent(input)) return
                val next = state ?: Attenuation(lease, current.coerceIn(0, maximum)).also { actual = it }
                val target = if (settings.relative) next.base * settings.percent / 100
                    else minOf(next.base, maximum * settings.percent / 100)
                if (state == null || next.restoring) {
                    val captured = next.paused
                    next.paused = lease.onBegin(settings.pause) { isCurrent(input) } || captured
                }
                next.restoring = false
                next.endHandled = false; next.finishedRamp = false
                if (!isCurrent(input)) return
                ramp(input, current.coerceIn(0, maximum), target.coerceIn(0, maximum))
            }
            is Action.Restore -> {
                val state = actual ?: return
                val current = lease.backend.volume()
                if (!isCurrent(input)) return
                val maximum = lease.backend.maximum().coerceAtLeast(0)
                if (!isCurrent(input)) return
                state.restoring = true
                state.endHandled = false; state.finishedRamp = false
                val paused = state.paused
                ramp(input, current.coerceIn(0, maximum), state.base.coerceIn(0, maximum))
                state.endHandled = lease.onEnd(paused, action.resume) { isCurrent(input) }
                if (state.endHandled) state.paused = false
                finishIfRestored(state)
            }
        }
    }
    private fun ramp(input: Input, from: Int, to: Int) {
        fadeInput = input; fadeTarget = to
        if (from == to) {
            actual?.let { if (it.restoring) { it.finishedRamp = true; finishIfRestored(it) } }
            return
        }
        transition.start(from, to)
    }
    private fun finishIfRestored(state: Attenuation) {
        if (actual === state && state.restoring && state.finishedRamp && state.endHandled) actual = null
    }
    override fun close() {
        synchronized(lock) {
            if (shutdown) return
            shutdown = true; desired.lease?.closed = true
            desired = Input(null, Action.Idle)
        }
        signal()
    }
}
