package me.kavishdevar.librepods.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import java.io.Closeable
import kotlinx.coroutines.Dispatchers

internal class BatteryHistoryStore @JvmOverloads constructor(context: Context, writable: Boolean = false) : Closeable {
    private val repository = BatteryHistoryRepositories.open(context.applicationContext)
    private val lease = if (writable) repository.claim() else null
    val snapshots get() = repository.snapshots

    fun read(): BatteryHistorySnapshot = repository.snapshot()
    fun snapshot(): BatteryHistorySnapshot = repository.snapshot()
    fun loadedSnapshot(): BatteryHistorySnapshot = repository.loadedSnapshot()
    fun record(address: String, batteries: List<Battery>) { lease?.record(address, batteries) }
    fun flush() { lease?.flush() }
    override fun close() { lease?.close() }
}

private object BatteryHistoryRepositories {
    @Volatile private var repository: BatteryHistoryRepository? = null
    fun open(context: Context): BatteryHistoryRepository {
        repository?.let { it.requestInitialLoad(); return it }
        var reused = false
        val opened = synchronized(this) {
            repository?.also { reused = true } ?: run {
                // Both opening preferences and awaiting XML loading happen only in IO.
                val preferences by lazy { context.getSharedPreferences("battery_history", Context.MODE_PRIVATE) }
                BatteryHistoryRepository(BatteryHistorySnapshot(), Dispatchers.IO,
                save = { snapshot -> preferences.edit {
                    clear()
                    putInt("version", 1)
                    putString("address", snapshot.address)
                    snapshot.readings.forEach { (component, reading) ->
                        putInt("level_$component", reading.level)
                        putLong("seen_$component", reading.observedAt)
                    }
                } }, elapsed = SystemClock::elapsedRealtime, wallTime = System::currentTimeMillis,
                onError = { Log.w("BatteryHistory", "Could not load or save battery history", it) },
                load = { decode(preferences.all) }).also { repository = it }
            }
        }
        if (reused) opened.requestInitialLoad()
        return opened
    }

    private fun decode(values: Map<String, *>): BatteryHistorySnapshot {
        if (values["version"] != 1) return BatteryHistorySnapshot()
        val address = batteryHistoryIdentity(values["address"] as? String ?: "") ?: return BatteryHistorySnapshot()
        val readings = batteryComponents.mapNotNull { component ->
            val level = values["level_$component"] as? Int
            val observedAt = values["seen_$component"] as? Long
            if (level != null && level in 0..100 && observedAt != null && observedAt > 0)
                component to BatteryHistoryReading(level, observedAt) else null
        }.toMap()
        return BatteryHistorySnapshot(address, readings)
    }
}
