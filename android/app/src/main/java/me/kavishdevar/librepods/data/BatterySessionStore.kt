package me.kavishdevar.librepods.data

import android.os.SystemClock

/** Process lifetime, memory only. A replacement service must claim a new exclusive lease. */
internal object BatterySessionStore {
    private val repository = BatterySessionRepository(SystemClock::elapsedRealtime)
    val snapshots get() = repository.snapshots
    fun claim(): BatterySessionRepository.Lease = repository.claim()
}
