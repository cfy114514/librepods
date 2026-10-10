package me.kavishdevar.librepods.services

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A process-owned history: disk operations never hold the producer/UI monitor. */
internal class PacketLogHistory(
    dispatcher: CoroutineDispatcher, private val load: () -> Set<String>,
    private val save: (Set<String>) -> Unit, capacity: Int = 1000, private val windowMillis: Long = 1000,
    private val onError: (Exception) -> Unit = {}
) : Closeable {
    val buffer = PacketLogBuffer(capacity)
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val flow: StateFlow<Set<String>> get() = state
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var clearedBeforeLoad = false
    private var revision = 0L
    private var persistedRevision = 0L
    private var loaded = false

    init {
        require(windowMillis >= 0)
        scope.launch {
            try {
                val restored = try { load() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError(error); emptySet() }
                synchronized(buffer) {
                    if (!clearedBeforeLoad) {
                        val recent = buffer.snapshot()
                        buffer.clear()
                        restored.forEach(buffer::add)
                        recent.forEach(buffer::add)
                    }
                    loaded = true
                    state.value = buffer.snapshot()
                }
                for (ignored in signals) {
                    delay(windowMillis)
                    val snapshot = synchronized(buffer) {
                        while (signals.tryReceive().isSuccess) { /* included below */ }
                        (revision to buffer.snapshot()).also { state.value = it.second }
                    }
                    try {
                        save(snapshot.second)
                        synchronized(buffer) { persistedRevision = snapshot.first }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError(error) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    fun add(entry: String): Boolean = synchronized(buffer) {
        if (!buffer.add(entry)) false else {
            revision++
            signals.trySend(Unit)
            true
        }
    }

    fun clear() = synchronized(buffer) {
        clearedBeforeLoad = true
        buffer.clear()
        revision++
        state.value = emptySet()
        signals.trySend(Unit)
        Unit
    }

    override fun close() { signals.cancel(); scope.cancel() }
}
