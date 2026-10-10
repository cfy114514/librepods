package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** Process-wide serial reads and bounded delivery, retaining only two fixed kinds of work. */
internal class PlaybackStateQueries(
    io: CoroutineDispatcher, main: CoroutineDispatcher,
    private val onError: (Exception) -> Unit = {}
) : Closeable {
    enum class Kind { CALLBACK, REFRESH }
    private data class Request(
        val lease: Lease, val kind: Kind, val epoch: Long, val version: Long,
        val query: () -> Boolean, val publish: (Boolean, () -> Boolean) -> Unit
    )
    private data class Reply(val request: Request, val value: Boolean)
    private val lock = Any()
    private var owner: Lease? = null
    private var closed = false
    private val replies = KeyedWorkSession<Kind, Reply>(Kind.entries.toSet(), main, onError) { reply ->
        val request = reply.request
        if (current(request)) request.publish(reply.value) { current(request) }
    }
    private val reads = KeyedWorkSession<Kind, Request>(Kind.entries.toSet(), io, onError) { request ->
        if (current(request)) {
            try {
                val value = request.query()
                replies.offerIf(request.kind, Reply(request, value)) { current(request) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(request)) onError(error) }
        }
    }

    internal class Lease internal constructor(
        private val parent: PlaybackStateQueries, internal val valid: () -> Boolean
    ) : Closeable {
        internal var epoch = 0L
        internal val versions = LongArray(Kind.entries.size)
        /** A newer raw event invalidates earlier queries even during the throttle interval. */
        fun advance(): Long? = parent.advance(this)
        fun isCurrent(epoch: Long): Boolean = parent.current(this, epoch)
        fun offer(kind: Kind, epoch: Long, query: () -> Boolean, publish: (Boolean, () -> Boolean) -> Unit): Boolean =
            parent.offer(this, kind, epoch, query, publish)
        override fun close() = parent.release(this)
    }

    /** valid must be a quick, side-effect-free check, without a platform call or caller lock. */
    fun claim(valid: () -> Boolean): Lease = synchronized(lock) {
        check(!closed)
        Lease(this, valid).also { owner = it }
    }
    private fun advance(lease: Lease): Long? = synchronized(lock) {
        if (closed || owner !== lease || !lease.valid()) null else ++lease.epoch
    }
    private fun current(lease: Lease, epoch: Long): Boolean = synchronized(lock) {
        !closed && owner === lease && lease.epoch == epoch && lease.valid()
    }
    private fun current(request: Request): Boolean = synchronized(lock) {
        !closed && owner === request.lease && request.lease.epoch == request.epoch &&
            request.lease.versions[request.kind.ordinal] == request.version && request.lease.valid()
    }
    private fun offer(lease: Lease, kind: Kind, epoch: Long, query: () -> Boolean, publish: (Boolean, () -> Boolean) -> Unit): Boolean {
        val request = synchronized(lock) {
            if (!current(lease, epoch)) return false
            Request(lease, kind, epoch, ++lease.versions[kind.ordinal], query, publish)
        }
        // No parent lock is held while entering either worker's queue.
        return reads.offerIf(kind, request) { current(request) }
    }
    private fun release(lease: Lease) { synchronized(lock) { if (owner === lease) owner = null } }
    override fun close() {
        synchronized(lock) { closed = true; owner = null }
        reads.close()
        replies.close()
    }
}
