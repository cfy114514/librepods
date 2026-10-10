package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MusicVolumeState(
    val maxVolume: Int = 0, val volume: Int = 0,
    val ready: Boolean = false, val pending: Boolean = false, val failed: Boolean = false
)

/** One process-wide serial worker, with at most one pending read and one pending write. */
internal class MusicVolumeDelivery(dispatcher: CoroutineDispatcher) : Closeable {
    internal enum class Kind { READ, WRITE }
    internal data class Request(val owner: MusicVolumeSession, val kind: Kind, val read: Long, val write: Long, val value: Int = 0)
    @Volatile private var owner: MusicVolumeSession? = null
    private val ownerLock = Any()
    private val worker = KeyedWorkSession(Kind.entries.toSet(), dispatcher) { request: Request ->
        request.owner.consume(request)
    }

    fun attach(session: MusicVolumeSession) = synchronized(ownerLock) {
        owner?.retire()
        owner = session
    }
    fun isCurrent(session: MusicVolumeSession) = owner === session && !worker.isClosed
    internal fun offer(request: Request) = worker.offerIf(request.kind, request) { request.owner.accepts(request) }
    fun detach(session: MusicVolumeSession) = synchronized(ownerLock) { if (owner === session) owner = null }
    override fun close() { synchronized(ownerLock) { owner?.retire(); owner = null }; worker.close() }

    companion object { val shared by lazy { MusicVolumeDelivery(Dispatchers.IO) } }
}

/** Audio calls run outside locks; only a current owner's current revision may publish. */
internal class MusicVolumeSession(
    private val read: () -> Pair<Int, Int>, private val write: (Int) -> Unit,
    private val delivery: MusicVolumeDelivery = MusicVolumeDelivery.shared,
    private val onError: (Exception) -> Unit = {}
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(MusicVolumeState())
    val state: StateFlow<MusicVolumeState> = mutableState.asStateFlow()
    @Volatile private var active = true
    @Volatile private var readRevision = 0L
    @Volatile private var writeRevision = 0L

    init { delivery.attach(this); refresh() }

    fun refresh(): Boolean {
        val request = synchronized(lock) {
            if (!current()) return false
            MusicVolumeDelivery.Request(this, MusicVolumeDelivery.Kind.READ, ++readRevision, writeRevision)
        }
        return delivery.offer(request)
    }

    fun setVolume(value: Int): Boolean {
        val request = synchronized(lock) {
            val known = mutableState.value
            if (!current() || !known.ready || value !in 0..known.maxVolume) return false
            ++readRevision
            ++writeRevision
            mutableState.value = known.copy(volume = value, pending = true, failed = false)
            MusicVolumeDelivery.Request(this, MusicVolumeDelivery.Kind.WRITE, readRevision, writeRevision, value)
        }
        return delivery.offer(request)
    }

    private fun current() = active && delivery.isCurrent(this)
    internal fun retire() { active = false }
    internal fun accepts(request: MusicVolumeDelivery.Request): Boolean =
        current() && request.write == writeRevision &&
            (request.kind == MusicVolumeDelivery.Kind.WRITE || request.read == readRevision)

    internal fun consume(request: MusicVolumeDelivery.Request) {
        if (!accepts(request)) return
        if (request.kind == MusicVolumeDelivery.Kind.READ && mutableState.value.pending) return
        try {
            if (request.kind == MusicVolumeDelivery.Kind.WRITE) write(request.value)
            if (!accepts(request)) return
            val (maximum, volume) = read()
            require(maximum > 0 && volume in 0..maximum) { "Invalid music volume snapshot" }
            synchronized(lock) {
                if (accepts(request)) mutableState.value = MusicVolumeState(maximum, volume, ready = true)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (accepts(request)) mutableState.value = mutableState.value.copy(pending = false, failed = true)
            }
            onError(error)
        }
    }

    override fun close() { synchronized(lock) { active = false }; delivery.detach(this) }
}
