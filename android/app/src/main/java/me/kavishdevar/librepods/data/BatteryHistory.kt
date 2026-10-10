package me.kavishdevar.librepods.data

internal val batteryComponents = listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE)
internal const val DEFAULT_BATTERY_HISTORY_AGE_MS = 7L * 24 * 60 * 60 * 1000

internal data class BatteryHistoryReading(val level: Int, val observedAt: Long)
internal data class BatteryHistorySnapshot(
    val address: String = "",
    val readings: Map<Int, BatteryHistoryReading> = emptyMap()
) {
    fun merge(address: String, batteries: List<Battery>, now: Long): BatteryHistorySnapshot {
        val identity = batteryHistoryIdentity(address) ?: return this
        if (now <= 0) return this
        val updated = if (identity == this.address) readings.toMutableMap() else mutableMapOf()
        for (battery in batteries) {
            if (battery.component in batteryComponents && battery.isAvailableReading()) {
                updated[battery.component] = BatteryHistoryReading(battery.level, now)
            }
        }
        return BatteryHistorySnapshot(identity, updated.toMap())
    }
}

internal fun batteryHistoryIdentity(address: String): String? =
    address.trim().takeIf { it.matches(batteryAddressPattern) }?.uppercase(java.util.Locale.ROOT)

private val batteryAddressPattern = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")

internal fun canRecordBleBattery(address: String, irkOwner: String?, hasEncryptionKey: Boolean, encryptionOwner: String?): Boolean {
    val identity = batteryHistoryIdentity(address) ?: return false
    return batteryHistoryIdentity(irkOwner ?: "") == identity &&
        (!hasEncryptionKey || batteryHistoryIdentity(encryptionOwner ?: "") == identity)
}

internal fun Battery.isAvailableReading(): Boolean = level in 0..100 &&
    (status == BatteryStatus.NOT_CHARGING || status == BatteryStatus.CHARGING || status == BatteryStatus.OPTIMIZED_CHARGING)

internal data class DisplayBattery(
    val component: Int,
    val level: Int? = null,
    val charging: Boolean = false,
    val historical: Boolean = false,
    val observedAt: Long? = null
)

/** Historical data is display-only: it must never drive charging or audio policy. */
internal fun displayBatteries(
    address: String,
    live: List<Battery>,
    liveAvailable: Boolean,
    history: BatteryHistorySnapshot,
    showHistory: Boolean,
    now: Long,
    maxAge: Long = DEFAULT_BATTERY_HISTORY_AGE_MS
): List<DisplayBattery> = batteryComponents.map { component ->
    val current = live.firstOrNull { it.component == component && it.isAvailableReading() }
    if (liveAvailable && current != null) {
        DisplayBattery(component, current.level,
            current.status == BatteryStatus.CHARGING || current.status == BatteryStatus.OPTIMIZED_CHARGING)
    } else {
        val stored = history.readings[component]?.takeIf {
            showHistory && batteryHistoryIdentity(address) == history.address &&
                it.level in 0..100 && it.observedAt > 0 && it.observedAt <= now &&
                maxAge > 0 && now - it.observedAt <= maxAge
        }
        DisplayBattery(component, stored?.level, historical = stored != null, observedAt = stored?.observedAt)
    }
}

/** Persist at level changes and at most once per interval for unchanged reports. */
internal class BatteryHistoryPersistence(private val intervalMs: Long = 30_000) {
    private var lastSaved: BatteryHistorySnapshot? = null
    private var lastSavedAt = Long.MIN_VALUE

    fun shouldSave(snapshot: BatteryHistorySnapshot, elapsedNow: Long, force: Boolean = false): Boolean {
        val previous = lastSaved
        val levelsChanged = previous == null || previous.address != snapshot.address ||
            previous.readings.size != snapshot.readings.size ||
            batteryComponents.any { previous.readings[it]?.level != snapshot.readings[it]?.level }
        val intervalPassed = lastSavedAt == Long.MIN_VALUE || elapsedNow < lastSavedAt || elapsedNow - lastSavedAt >= intervalMs
        if (snapshot == previous || (!force && !levelsChanged && !intervalPassed)) return false
        return true
    }

    fun saved(snapshot: BatteryHistorySnapshot, elapsedNow: Long) {
        lastSaved = snapshot
        lastSavedAt = elapsedNow
    }

    fun isSaved(snapshot: BatteryHistorySnapshot): Boolean = snapshot == lastSaved
}
