package me.kavishdevar.librepods.presentation.widgets

import android.content.BroadcastReceiver
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.DeferredRegistration
import me.kavishdevar.librepods.utils.CommandCompletionBatch

/** One goAsync result for a burst, released on completion or before the normal receiver deadline. */
internal object WidgetListeningCommands {
    private val lock = Any()
    private val batches = CommandCompletionBatch()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pin: Pin? = null

    private class Pin(val batch: Long) : Closeable {
        private val closed = AtomicBoolean()
        private val pending = AtomicReference<BroadcastReceiver.PendingResult?>()
        private val timeout = AtomicReference<Job?>()
        private val registration = DeferredRegistration {
            pending.getAndSet(null)?.let { result -> scope.launch {
                runCatching { result.finish() }.onFailure { Log.w("WidgetCommands", "Broadcast completion failed", it) }
            } }
        }
        fun attach(result: BroadcastReceiver.PendingResult?) {
            pending.set(result)
            registration.didRegister()
            val job = scope.launch(start = CoroutineStart.LAZY) { delay(8000); expire(batch) }
            timeout.set(job)
            if (closed.get()) timeout.getAndSet(null)?.cancel() else job.start()
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                timeout.getAndSet(null)?.cancel()
                registration.close()
            }
        }
    }

    fun request(service: AirPodsService, mode: Int, acquire: () -> BroadcastReceiver.PendingResult?) {
        if (!service.canRequestListeningMode(mode)) return
        val offered: CommandCompletionBatch.Offer
        val ownedPin: Pin
        synchronized(lock) {
            offered = batches.offer()
            ownedPin = if (offered.created) Pin(offered.ticket.batch).also { pin = it } else requireNotNull(pin)
        }
        if (offered.created) {
            try { ownedPin.attach(acquire()) }
            catch (error: Exception) { expire(offered.ticket.batch); throw error }
        }
        service.enqueueInteractiveCommand(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            byteArrayOf(mode.toByte()), valid = { batches.isCurrent(offered.ticket) },
            onComplete = { complete(offered.ticket) })
    }

    private fun complete(ticket: CommandCompletionBatch.Ticket) {
        val old = synchronized(lock) {
            if (!batches.complete(ticket)) return
            pin.also { pin = null }
        }
        old?.close()
    }
    private fun expire(batch: Long) {
        val old = synchronized(lock) {
            if (!batches.expire(batch)) return
            pin.also { pin = null }
        }
        old?.close()
    }
}
