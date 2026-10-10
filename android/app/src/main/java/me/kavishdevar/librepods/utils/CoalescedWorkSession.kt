package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One worker and one dirty flag; requests do not extend the coalescing window. */
internal class CoalescedWorkSession(
    dispatcher: CoroutineDispatcher,
    private val windowMillis: Long = 100,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onError: (Exception) -> Unit = {},
    private val work: () -> Unit
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = Channel<Unit>(Channel.CONFLATED)

    init {
        require(windowMillis >= 0)
        scope.launch {
            for (request in pending) {
                pause(windowMillis)
                // Work reads current state, so requests already in this window are satisfied too.
                pending.tryReceive()
                if (!isActive) break
                try {
                    work()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onError(error) // A transient host/permission failure must not kill future refreshes.
                }
            }
        }
    }

    fun request(): Boolean = pending.trySend(Unit).isSuccess

    override fun close() {
        pending.cancel()
        scope.cancel()
    }
}
