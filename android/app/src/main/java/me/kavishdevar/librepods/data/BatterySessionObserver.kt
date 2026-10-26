package me.kavishdevar.librepods.data

import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

internal const val BATTERY_SESSION_DISPLAY_INTERVAL_MS = 15_000L
internal const val BATTERY_SESSION_AVAILABILITY_MAX_AGE_MS = 30_000L

/** Availability reads may block in Binder; neither display expiry nor stop waits for them. */
internal class BatterySessionObserver(private val address: String) : Closeable {
    private class Read(val generation: Any, val startedAt: Long)
    private data class Availability(val available: Boolean, val startedAt: Long)
    private val availability = MutableStateFlow<Availability?>(null)
    private var generation = Any()
    private var active = false
    private var closed = false
    private var reading: Read? = null

    @Synchronized fun start() {
        if (!closed) active = true
    }

    @Synchronized fun stop() {
        active = false
        generation = Any()
        availability.value = null
    }

    @Synchronized override fun close() {
        closed = true
        stop()
    }

    @Synchronized private fun beginRead(now: Long): Read? {
        if (!active || closed || reading != null) return null
        return Read(generation, now).also { reading = it }
    }

    @Synchronized private fun publish(read: Read, available: Boolean) {
        if (active && !closed && generation === read.generation && reading === read)
            availability.value = Availability(available, read.startedAt)
    }

    @Synchronized private fun finishRead(read: Read) {
        if (reading === read) reading = null
    }

    /** At most one read is in flight, including across stop/start while an old read finishes. */
    suspend fun refreshAvailability(elapsedRealtime: () -> Long, readAvailability: () -> Boolean) {
        val read = beginRead(elapsedRealtime()) ?: return
        try {
            val available = withContext(Dispatchers.IO) { runCatching(readAvailability).getOrDefault(false) }
            publish(read, available)
        } finally {
            finishRead(read)
        }
    }

    @Synchronized private fun presentation(snapshot: BatterySessionSnapshot, now: Long): BatterySessionPresentation {
        val evidence = availability.value
        val available = active && !closed && evidence?.available == true &&
            evidence.startedAt >= 0 && evidence.startedAt <= now &&
            now - evidence.startedAt <= BATTERY_SESSION_AVAILABILITY_MAX_AGE_MS
        return batterySessionPresentation(address, if (available) snapshot else snapshot.copy(connected = false), now)
    }

    /** The clock and raw repository continue to publish even when refreshAvailability is blocked. */
    fun presentations(snapshots: Flow<BatterySessionSnapshot>, clock: Flow<Long>, elapsedRealtime: () -> Long):
        Flow<BatterySessionPresentation> = combine(snapshots, clock, availability) { snapshot, _, _ ->
            presentation(snapshot, elapsedRealtime())
        }.distinctUntilChanged()
}
