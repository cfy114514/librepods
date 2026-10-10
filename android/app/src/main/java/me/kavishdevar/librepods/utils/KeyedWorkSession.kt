package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Serial work with one pending value per known key; replacement preserves FIFO between keys. */
internal class KeyedWorkSession<K : Any, V : Any>(
    allowedKeys: Set<K>, dispatcher: CoroutineDispatcher,
    private val onError: (Exception) -> Unit = {}, private val onDiscard: (V) -> Unit = {},
    private val consume: (V) -> Unit
) : Closeable {
    private val keys = allowedKeys.toSet()
    private val lock = Any()
    private val entries = linkedMapOf<K, V>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile var isClosed = false
        private set

    init {
        require(keys.isNotEmpty())
        scope.launch {
            try {
                for (signal in signals) {
                    while (isActive) {
                        val next = synchronized(lock) {
                            if (isClosed) null else entries.entries.firstOrNull()?.let {
                                val value = it.value
                                entries.remove(it.key)
                                value
                            }
                        } ?: break
                        try { consume(next) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { onError(error) }
                        yield()
                    }
                }
            } finally {
                val discarded = synchronized(lock) { isClosed = true; entries.values.toList().also { entries.clear() } }
                discarded.forEach(::discard)
                signals.cancel()
            }
        }
    }

    fun offer(key: K, value: V): Boolean = offerIf(key, value) { true }

    /** The predicate must be quick and side-effect free; rejection leaves the pending value intact. */
    fun offerIf(key: K, value: V, accept: () -> Boolean): Boolean {
        require(key in keys)
        val replaced: V?
        val offered = synchronized(lock) {
            if (isClosed || !accept()) return false
            replaced = entries.put(key, value)
            signals.trySend(Unit).isSuccess
        }
        replaced?.let(::discard)
        return offered
    }

    override fun close() {
        val discarded = synchronized(lock) { isClosed = true; entries.values.toList().also { entries.clear() } }
        signals.cancel()
        scope.cancel()
        discarded.forEach(::discard)
    }

    private fun discard(value: V) {
        try { onDiscard(value) } catch (error: Exception) { onError(error) }
    }
}
