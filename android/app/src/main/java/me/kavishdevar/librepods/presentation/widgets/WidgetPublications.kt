package me.kavishdevar.librepods.presentation.widgets

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import me.kavishdevar.librepods.utils.KeyedWorkSession

/** One IO publisher for live and offline views, one pending request per provider. */
internal object WidgetPublications {
    private val lock = Any()
    private class Request(val valid: () -> Boolean, val publish: () -> Unit, private val onFinished: () -> Unit) {
        private val finished = AtomicBoolean()
        fun finish() {
            if (finished.compareAndSet(false, true)) runCatching(onFinished)
                .onFailure { Log.w("WidgetPublications", "Widget completion failed", it) }
        }
    }
    private val worker = KeyedWorkSession<OfflineWidgetUpdates.Kind, Request>(
        OfflineWidgetUpdates.Kind.entries.toSet(), Dispatchers.IO,
        onError = { Log.w("WidgetPublications", "Widget publication failed", it) },
        onDiscard = { it.finish() }) { request ->
        try { if (request.valid()) request.publish() } finally { request.finish() }
    }
    fun offer(kind: OfflineWidgetUpdates.Kind, valid: () -> Boolean, publish: () -> Unit, onFinished: () -> Unit = {}) {
        val request = Request(valid, publish, onFinished)
        val accepted = synchronized(lock) { valid() && worker.offer(kind, request) }
        if (!accepted) request.finish()
    }
}
