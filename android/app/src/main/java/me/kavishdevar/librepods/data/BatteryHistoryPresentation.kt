package me.kavishdevar.librepods.data

/** Equality describes displayed text only; the repository retains full observation precision. */
internal data class HistoricalBatteryText(val component: Int, val level: Int?, val reportedAt: String?)
internal data class BatteryHistoryPresentation(
    val readings: List<HistoricalBatteryText>, val enabled: Boolean, val days: Int,
    val oldestReportedAt: String?
)

/** Owned by one sequential presentation collector; at most one formatted time per part. */
internal class HistoryTimeFormatter(private val format: (Long) -> String) {
    private data class Cached(val observedAt: Long, val text: String)
    private val cache = mutableMapOf<Int, Cached>()
    fun format(component: Int, observedAt: Long): String {
        require(component in batteryComponents)
        return cache[component]?.takeIf { it.observedAt == observedAt }?.text
            ?: format(observedAt).also { cache[component] = Cached(observedAt, it) }
    }
    fun clear() { cache.clear() }
}

internal fun batteryHistoryPresentation(
    address: String, history: BatteryHistorySnapshot, enabled: Boolean, days: Int, now: Long,
    includeReadingTimes: Boolean = true, formatTime: (Int, Long) -> String
): BatteryHistoryPresentation {
    val boundedDays = days.coerceIn(1, 30)
    // Eligibility uses original millisecond timestamps, before any display formatting.
    val available = displayBatteries(address, emptyList(), false, history, enabled, now,
        boundedDays * 86_400_000L)
    val readings = available.map { HistoricalBatteryText(it.component, it.level,
        if (includeReadingTimes) it.observedAt?.let { time -> formatTime(it.component, time) } else null) }
    val oldestIndex = available.indices.filter { available[it].observedAt != null }
        .minByOrNull { available[it].observedAt!! }
    val oldest = oldestIndex?.let { index ->
        if (includeReadingTimes) readings[index].reportedAt
        else formatTime(available[index].component, available[index].observedAt!!)
    }
    return BatteryHistoryPresentation(readings, enabled, boundedDays, oldest)
}
