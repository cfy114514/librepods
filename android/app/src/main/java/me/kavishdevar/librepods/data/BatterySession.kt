package me.kavishdevar.librepods.data

internal const val BATTERY_SESSION_MAX_OBSERVATIONS = 240
internal const val BATTERY_SESSION_WINDOW_MS = 4L * 60 * 60 * 1000
internal const val BATTERY_SESSION_SAMPLE_INTERVAL_MS = 60_000L
internal const val BATTERY_SESSION_MAX_GAP_MS = 5L * 60 * 1000
internal const val BATTERY_SESSION_MAX_DELIVERY_AGE_MS = 15_000L
internal const val BATTERY_SESSION_MIN_SPAN_MS = 15L * 60 * 1000

/** Every point comes from an incoming report, never from a timer or a cached percentage. */
internal data class BatterySessionObservation(
    val receivedAtElapsedMs: Long,
    val level: Int,
    val charging: Boolean,
    val segment: Long
)

internal enum class BatterySessionPartState { WAITING, DISCHARGING, CHARGING, UNAVAILABLE }
internal enum class BatterySessionInterruption { CONNECTION, CHARGING, UNAVAILABLE, LEVEL_CHANGE, GAP, CLOCK }

internal data class BatterySessionSeries(
    val component: Int,
    val observations: List<BatterySessionObservation> = emptyList(),
    val state: BatterySessionPartState = BatterySessionPartState.WAITING,
    val segment: Long = 0,
    val interruption: BatterySessionInterruption = BatterySessionInterruption.CONNECTION,
    val lastReceivedAtElapsedMs: Long? = null,
    val level: Int? = null
)

/** Memory-only measurements from one control connection; unrelated to saved BatteryHistory. */
internal data class BatterySessionSnapshot(
    val address: String = "",
    val connected: Boolean = false,
    val left: BatterySessionSeries = BatterySessionSeries(BatteryComponent.LEFT),
    val right: BatterySessionSeries = BatterySessionSeries(BatteryComponent.RIGHT)
)
