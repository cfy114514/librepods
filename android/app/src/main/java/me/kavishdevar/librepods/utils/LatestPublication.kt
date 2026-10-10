package me.kavishdevar.librepods.utils

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher

/** One process-wide serial slot; a stopped or replaced owner cannot overwrite its successor. */
internal class LatestPublication<V : Any>(
    dispatcher: CoroutineDispatcher,
    private val publish: (V, () -> Boolean) -> Boolean,
    private val clear: () -> Unit,
    onError: (Exception) -> Unit = {}
) : Closeable {
    private data class Request<V>(val owner: Any, val value: V?, val valid: () -> Boolean)
    private val lock = Any()
    private var owner: Any? = null
    private var desired: Request<V>? = null
    private var closed = false
    // Only the worker accesses accepted; no platform call holds lock.
    private var accepted: Pair<Any, V>? = null
    private val worker = KeyedWorkSession<Unit, Request<V>>(setOf(Unit), dispatcher, onError) { request ->
        fun current(): Boolean = synchronized(lock) { !closed && desired === request } && request.valid()
        if (current()) {
            val value = request.value
            if (value == null) {
                clear()
                if (current()) accepted = null
            } else if (accepted?.let { it.first === request.owner && it.second == value } != true) {
                if (publish(value, ::current) && current()) accepted = request.owner to value
            }
        }
        if (request.value != null && !current()) synchronized(lock) {
            // An already entered platform write can finish after its source expires.
            // Clear it only if no newer request or owner has superseded it.
            if (!closed && desired === request && owner === request.owner)
                enqueue(Request(request.owner, null) { true })
        }
    }

    internal class Lease<V : Any>(private val parent: LatestPublication<V>, private val token: Any) : Closeable {
        private val closed = AtomicBoolean()
        fun offer(value: V, valid: () -> Boolean): Boolean = !closed.get() && parent.offer(token, value, valid)
        fun clear(): Boolean = !closed.get() && parent.erase(token)
        override fun close() { if (closed.compareAndSet(false, true)) parent.release(token) }
    }

    fun claim(): Lease<V> = synchronized(lock) {
        check(!closed)
        val token = Any()
        owner = token
        enqueue(Request(token, null) { true })
        Lease(this, token)
    }

    private fun offer(token: Any, value: V, valid: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || owner !== token) false else enqueue(Request(token, value, valid))
    }

    private fun release(token: Any) = synchronized(lock) {
        if (!closed && owner === token) {
            owner = null
            enqueue(Request(token, null) { true })
        }
    }

    private fun erase(token: Any): Boolean = synchronized(lock) {
        if (closed || owner !== token) false else enqueue(Request(token, null) { true })
    }

    private fun enqueue(request: Request<V>): Boolean {
        desired = request
        return worker.offer(Unit, request)
    }

    // This closes the process worker, not an individual service lease.
    override fun close() {
        synchronized(lock) { closed = true; owner = null; desired = null; worker.close() }
    }
}
