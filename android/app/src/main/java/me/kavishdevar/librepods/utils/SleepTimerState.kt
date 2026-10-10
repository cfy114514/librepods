package me.kavishdevar.librepods.utils

import me.kavishdevar.librepods.data.batteryHistoryIdentity

internal data class TimerClock(val wall: Long, val elapsed: Long, val boot: Int)

internal data class SleepTimerRecord(
    val token: String, val address: String, val mode: Int,
    val endWall: Long, val endElapsed: Long, val boot: Int,
    val deliveryWall: Long = 0, val deliveryElapsed: Long = 0
) {
    val delivering: Boolean get() = deliveryWall > 0
    fun valid(): Boolean = token.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
        batteryHistoryIdentity(address) == address && mode in 1..4 && endWall > 0 && endElapsed >= 0
    fun matches(selected: String): Boolean = batteryHistoryIdentity(selected) == address
    fun remaining(clock: TimerClock): Long =
        ((if (boot >= 0 && boot == clock.boot) endElapsed - clock.elapsed else endWall - clock.wall)).coerceAtLeast(0)
    fun deliveryRemaining(clock: TimerClock): Long {
        if (!delivering || (boot >= 0 && clock.boot >= 0 && boot != clock.boot)) return 0
        return ((if (boot >= 0 && boot == clock.boot) deliveryElapsed - clock.elapsed else deliveryWall - clock.wall)).coerceAtLeast(0)
    }
    fun staleAfterReboot(clock: TimerClock, confirmedReboot: Boolean = false): Boolean =
        (confirmedReboot || (boot >= 0 && clock.boot >= 0 && boot != clock.boot)) &&
            (delivering || endWall <= clock.wall)
    fun rebase(clock: TimerClock, confirmedReboot: Boolean = false): SleepTimerRecord =
        if (delivering) this else (if (confirmedReboot) (endWall - clock.wall).coerceAtLeast(0) else remaining(clock)).let {
        copy(endWall = clock.wall + it, endElapsed = clock.elapsed + it, boot = clock.boot)
    }
    fun beginDelivery(clock: TimerClock, graceMillis: Long): SleepTimerRecord {
        require(graceMillis > 0)
        return copy(boot = clock.boot, deliveryWall = clock.wall + graceMillis,
            deliveryElapsed = clock.elapsed + graceMillis)
    }
}

/** The UI displays whole remaining minutes; wake at their boundary instead of every second. */
internal fun sleepTimerRefreshDelay(endAt: Long, now: Long): Long {
    val remaining = (endAt - now).coerceAtLeast(0)
    return if (remaining == 0L) 0 else (remaining - 1) % 60_000 + 1
}
