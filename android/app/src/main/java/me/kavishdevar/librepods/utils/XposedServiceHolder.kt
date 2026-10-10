package me.kavishdevar.librepods.utils

import io.github.libxposed.service.XposedService
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object XposedServiceHolder {
    private class Connection(val service: XposedService)
    private val lock = Any()
    @Volatile private var connection: Connection? = null
    val service: XposedService? get() = connection?.service
    private val _connectionChanges = MutableStateFlow(0L)
    internal val connectionChanges = _connectionChanges.asStateFlow()
    internal class Lease internal constructor(val service: XposedService, internal val identity: Any)
    internal fun captureConnection(): Lease? = connection?.let { Lease(it.service, it) }
    internal fun isCurrent(lease: Lease): Boolean = connection === lease.identity
    private val scopeRefresh = CoalescedWorkSession(Dispatchers.IO,
        onError = { Log.w("XposedServiceHolder", "Scope refresh failed", it) }, work = ::readScope)

    fun bind(service: XposedService) {
        synchronized(lock) {
            if (connection?.service !== service) {
                connection = Connection(service)
                _connectionChanges.value++
                XposedState.publish(available = true, bluetoothScopeEnabled = false)
            }
        }
        scopeRefresh.request()
    }

    fun died(service: XposedService) {
        synchronized(lock) {
            if (connection?.service !== service) return
            connection = null
            _connectionChanges.value++
            XposedState.publish(available = false, bluetoothScopeEnabled = false)
        }
    }

    fun refreshScope() {
        synchronized(lock) {
            if (connection == null) {
                XposedState.publish(available = false, bluetoothScopeEnabled = false)
                return
            }
        }
        scopeRefresh.request()
    }

    private fun readScope() {
        val source = connection ?: return
        val enabled = try {
            val scope = source.service.scope
            "com.google.android.bluetooth" in scope || "com.android.bluetooth" in scope
        } catch (error: Exception) {
            Log.w("XposedServiceHolder", "Could not read module scope", error)
            false
        }
        synchronized(lock) {
            // Bind/death/rebind can reuse the wrapper; the captured connection owns this reply.
            if (connection === source) XposedState.publish(available = true, bluetoothScopeEnabled = enabled)
        }
    }
}
