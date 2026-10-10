package me.kavishdevar.librepods.presentation.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
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
import me.kavishdevar.librepods.data.BatteryHistoryStore
import me.kavishdevar.librepods.services.DeferredRegistration
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.CoalescedRequests
import me.kavishdevar.librepods.utils.CoalescedWorkSession

internal object OfflineWidgetUpdates {
    enum class Kind { BATTERY, NOISE }
    private data class Input(val context: Context, val serviceVersion: Long)
    private val lock = Any()
    private val requests = CoalescedRequests<Kind, Input>(Kind.entries.toSet())
    private val pins = mutableMapOf<Kind, Pin>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = CoalescedWorkSession(Dispatchers.IO,
        onError = { Log.w("OfflineWidgets", "Widget refresh failed", it) }, work = ::publish)

    private class Pin(val ticket: CoalescedRequests.Ticket<Kind>) : Closeable {
        private val closed = AtomicBoolean()
        private val pending = AtomicReference<BroadcastReceiver.PendingResult?>()
        private val timeout = AtomicReference<Job?>()
        private val registration = DeferredRegistration {
            pending.getAndSet(null)?.let { result -> scope.launch {
                runCatching { result.finish() }.onFailure { Log.w("OfflineWidgets", "Could not finish widget broadcast", it) }
            } }
        }
        fun attach(result: BroadcastReceiver.PendingResult?) {
            pending.set(result)
            registration.didRegister()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(8000)
                retire(ticket)
            }
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

    fun request(context: Context, kind: Kind, acquire: () -> BroadcastReceiver.PendingResult?) {
        val state = ServiceManager.captureState()
        state.service?.let {
            if (kind == Kind.BATTERY) it.updateBatteryWidget() else it.updateNoiseControlWidget()
            return
        }
        val offered: CoalescedRequests.Offer<Kind>
        val pin: Pin
        synchronized(lock) {
            offered = requests.offer(kind, Input(context.applicationContext, state.version))
            pin = if (offered.created) Pin(offered.ticket).also { pins[kind] = it } else pins.getValue(kind)
        }
        if (offered.created) {
            try { pin.attach(acquire()) }
            catch (error: Exception) { retire(offered.ticket); throw error }
        }
        worker.request()
    }

    private fun retire(ticket: CoalescedRequests.Ticket<Kind>) {
        val pin = synchronized(lock) {
            if (!requests.expire(ticket)) return
            pins.remove(ticket.key)?.takeIf { it.ticket == ticket }
        }
        pin?.close()
    }

    private fun publish() {
        for (kind in Kind.entries) {
            val work = synchronized(lock) { requests.take(kind) } ?: continue
            try {
                val valid = { requests.isCurrent(work) && ServiceManager.isCurrentIdle(work.value.serviceVersion) }
                WidgetPublications.offer(kind, valid, publish = {
                    val context = work.value.context
                    val manager = AppWidgetManager.getInstance(context)
                    val provider = if (kind == Kind.BATTERY) BatteryWidget::class.java else NoiseControlWidget::class.java
                    val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                    if (kind == Kind.BATTERY) {
                        val history = BatteryHistoryStore(context).loadedSnapshot()
                        for (id in ids) {
                            if (!valid()) break
                            val views = BatteryWidget.createViews(context, id, history = history)
                            if (valid()) manager.updateAppWidget(id, views)
                        }
                    } else if (ids.isNotEmpty()) {
                        val views = NoiseControlWidget.createViews(context, modes = emptyList())
                        if (valid()) manager.updateAppWidget(ids, views)
                    }
                }, onFinished = { complete(work) })
            } catch (error: Exception) {
                Log.w("OfflineWidgets", "Could not queue widget", error)
                complete(work)
            }
        }
    }

    private fun complete(work: CoalescedRequests.Work<Kind, Input>) {
        val pin = synchronized(lock) {
            if (requests.finished(work)) pins.remove(work.ticket.key)?.takeIf { it.ticket == work.ticket } else null
        }
        pin?.close()
        // Requests may have arrived while the process publisher was busy after the
        // coalescing worker already consumed its signal. Re-check retained dirty batches.
        worker.request()
    }
}

