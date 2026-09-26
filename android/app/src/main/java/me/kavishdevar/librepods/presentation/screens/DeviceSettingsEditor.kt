package me.kavishdevar.librepods.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** All calls, including scheduled callbacks, run on the UI thread. */
internal class DeviceSettingsEditor<T : Any>(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val write: (T) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val settlingMillis: Long = 2500
) {
    private var pending: T? = null
    private var cancelScheduled: (() -> Unit)? = null
    private var generation = 0L
    private var editedAt: Long? = null

    fun userEdited(value: T) {
        editedAt = nowMillis()
        pending = value
        val currentGeneration = ++generation
        cancelScheduled?.invoke()
        cancelScheduled = schedule(100) {
            if (generation == currentGeneration) flush()
        }
    }

    fun applyDeviceUpdate(update: () -> Unit): Boolean {
        // Protect the draft through the 100 ms debounce and the 2 s ATT timeout.
        // Even matching echoes may normalize gain/balance or quantize EQ values.
        // Only user edits extend this window; notifications cannot lock it forever.
        val lastEdit = editedAt
        if (lastEdit != null && nowMillis() - lastEdit < settlingMillis) return false
        update()
        return true
    }

    fun flush() {
        val value = pending ?: return
        pending = null
        generation++
        cancelScheduled?.invoke()
        cancelScheduled = null
        write(value)
    }
}

@Composable
internal fun <T : Any> rememberDeviceSettingsEditor(write: (T) -> Unit): DeviceSettingsEditor<T> {
    val scope = rememberCoroutineScope()
    val currentWrite = rememberUpdatedState(write)
    val editor = remember(scope) {
        DeviceSettingsEditor<T>(
            schedule = { delayMillis, action ->
                val job = scope.launch {
                    delay(delayMillis)
                    action()
                }
                val cancel: () -> Unit = { job.cancel() }
                cancel
            },
            write = { currentWrite.value(it) }
        )
    }
    DisposableEffect(editor) {
        // Preserve the final user edit when navigation cancels the debounce scope.
        onDispose { editor.flush() }
    }
    return editor
}
