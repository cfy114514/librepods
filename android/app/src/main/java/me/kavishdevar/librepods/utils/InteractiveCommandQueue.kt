package me.kavishdevar.librepods.utils

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher

/** One active command plus one latest pending command per fixed key. */
internal class InteractiveCommandQueue<K : Any, V : Any>(
    keys: Set<K>, dispatcher: CoroutineDispatcher,
    private val onError: (Exception) -> Unit = {},
    private val write: (V, () -> Boolean) -> Unit
) : Closeable {
    private class Request<K, V>(val key: K, val revision: Long, val value: V,
        val valid: () -> Boolean, val onComplete: () -> Unit) {
        val completed = AtomicBoolean()
    }
    private val revisions = keys.associateWith { AtomicLong() }
    private val worker = KeyedWorkSession<K, Request<K, V>>(keys, dispatcher,
        onError = onError, onDiscard = ::complete) { request ->
        try {
            val current = { !workerClosed() && revisions.getValue(request.key).get() == request.revision && request.valid() }
            if (current()) write(request.value, current)
        } finally { complete(request) }
    }

    private fun workerClosed(): Boolean = worker.isClosed

    fun offer(key: K, value: V, valid: () -> Boolean = { true }, onComplete: () -> Unit = {}): Boolean {
        val revision = requireNotNull(revisions[key]) { "Unknown command key" }.incrementAndGet()
        val request = Request(key, revision, value, valid, onComplete)
        // A producer paused after taking a revision must not overwrite a later producer's value.
        val accepted = worker.offerIf(key, request) { revisions.getValue(key).get() == revision }
        if (!accepted) complete(request)
        return accepted
    }

    private fun complete(request: Request<K, V>) {
        if (request.completed.compareAndSet(false, true)) {
            try { request.onComplete() } catch (error: Exception) { onError(error) }
        }
    }

    override fun close() = worker.close()
}
