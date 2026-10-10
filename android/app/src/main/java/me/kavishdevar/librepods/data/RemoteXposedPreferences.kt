package me.kavishdevar.librepods.data

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.utils.CoalescedWorkSession
import me.kavishdevar.librepods.utils.XposedServiceHolder

/** The UI reads memory; opening a framework preference group happens only on IO. */
internal object RemoteXposedPreferences {
    enum class Key(val value: String) {
        VENDOR_ID("vendor_id_hook"), LEGACY_L2CAP("force_legacy_l2cap_workaround")
    }
    data class State(val lease: XposedServiceHolder.Lease? = null, val ready: Boolean = false, val failed: Boolean = false,
        val vendorIdHook: Boolean = false, val forceLegacyL2capWorkaround: Boolean = false)
    private class Entry(val lease: XposedServiceHolder.Lease, val prefs: SharedPreferences) {
        val revision = AtomicLong()
        lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    }
    private val lock = Any()
    @Volatile private var entry: Entry? = null
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val refresh = CoalescedWorkSession(Dispatchers.IO,
        onError = { Log.w("RemoteXposedPreferences", "Preference refresh failed", it) }, work = ::read)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            XposedServiceHolder.connectionChanges.collect { refresh.request() }
        }
    }

    fun requestRefresh() { refresh.request() }
    fun currentState(): State = _state.value.takeIf { it.lease?.let(XposedServiceHolder::isCurrent) == true } ?: State()

    private fun retire(previous: Entry?) {
        previous?.prefs?.unregisterOnSharedPreferenceChangeListener(previous.listener)
    }

    private fun read() {
        val lease = XposedServiceHolder.captureConnection()
        if (lease == null) {
            val old = synchronized(lock) { entry.also { entry = null; _state.value = State() } }
            retire(old)
            return
        }
        try {
            var source = entry
            if (source?.lease?.identity !== lease.identity) {
                val old = synchronized(lock) { entry.also { entry = null; _state.value = State(lease) } }
                retire(old)
                val prefs = lease.service.getRemotePreferences("me.kavishdevar.librepods")
                if (!XposedServiceHolder.isCurrent(lease)) { refresh.request(); return }
                val next = Entry(lease, prefs)
                next.listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || Key.entries.any { it.value == key }) synchronized(lock) {
                        if (entry === next && XposedServiceHolder.isCurrent(next.lease)) {
                            next.revision.incrementAndGet()
                            refresh.request()
                        }
                    }
                }
                synchronized(lock) { entry = next }
                prefs.registerOnSharedPreferenceChangeListener(next.listener)
                source = next
            }
            val active = source ?: return
            val revision = active.revision.get()
            val vendor = active.prefs.getBoolean(Key.VENDOR_ID.value, false)
            val legacy = active.prefs.getBoolean(Key.LEGACY_L2CAP.value, false)
            synchronized(lock) {
                if (entry === active && XposedServiceHolder.isCurrent(active.lease) && active.revision.get() == revision) {
                    _state.value = State(active.lease, ready = true, vendorIdHook = vendor, forceLegacyL2capWorkaround = legacy)
                } else refresh.request()
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (XposedServiceHolder.isCurrent(lease)) _state.value = State(lease, failed = true)
            }
            Log.w("RemoteXposedPreferences", "Could not load framework preferences", error)
        }
    }

    /** SDK101 apply updates this loaded in-memory group, then commits on its own executor. */
    fun setBoolean(key: Key, value: Boolean): Boolean = synchronized(lock) {
        val active = entry ?: return@synchronized false
        val current = _state.value
        if (!current.ready || current.lease?.identity !== active.lease.identity || !XposedServiceHolder.isCurrent(active.lease)) return@synchronized false
        active.prefs.edit { putBoolean(key.value, value) }
        if (XposedServiceHolder.isCurrent(active.lease)) {
            _state.update { latest ->
                if (latest.lease?.identity !== active.lease.identity || !latest.ready) latest else when (key) {
                    Key.VENDOR_ID -> latest.copy(vendorIdHook = value)
                    Key.LEGACY_L2CAP -> latest.copy(forceLegacyL2capWorkaround = value)
                }
            }
        }
        true
    }
}
