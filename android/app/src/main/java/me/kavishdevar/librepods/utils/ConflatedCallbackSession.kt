package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Keeps only the latest pending sample and cancels both callbacks and child work on close. */
internal class ConflatedCallbackSession<T>(
    dispatcher: CoroutineDispatcher,
    consume: (T) -> Unit
) : Closeable {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (sample in pending) consume(sample)
        }
    }

    fun offer(sample: T) = pending.trySend(sample).isSuccess

    override fun close() {
        pending.cancel()
        scope.cancel()
    }
}
