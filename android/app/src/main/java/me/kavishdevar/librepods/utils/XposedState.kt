package me.kavishdevar.librepods.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object XposedState {
    private data class Status(val available: Boolean = false, val bluetoothScopeEnabled: Boolean = false)
    private var status by mutableStateOf(Status())
    val isAvailable: Boolean get() = status.available
    val bluetoothScopeEnabled: Boolean get() = status.bluetoothScopeEnabled

    internal fun publish(available: Boolean, bluetoothScopeEnabled: Boolean) {
        status = Status(available, bluetoothScopeEnabled)
    }
}
