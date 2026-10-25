package me.kavishdevar.librepods.data

import kotlin.math.max
import kotlin.math.min

internal enum class BatterySessionEstimateState {
    DISCONNECTED, WAITING, CHARGING, UNAVAILABLE, STALE, CLOCK_CHANGED,
    COLLECTING, INSUFFICIENT_DRAIN, ROUGH_ESTIMATE
}

internal data class BatterySessionSidePresentation(
    val component: Int,
    val state: BatterySessionEstimateState,
    val level: Int? = null,
    val points: List<BatterySessionObservation> = emptyList(),
    val observedMinutes: Int = 0,
    val roughRemainingMinutes: Int? = null
)

internal data class BatterySessionPresentation(
    val left: BatterySessionSidePresentation,
    val right: BatterySessionSidePresentation
)

/** Expiry is evaluated at display time as well as ingestion; a silent connection cannot freeze an estimate. */
internal fun batterySessionPresentation(
    address: String, snapshot: BatterySessionSnapshot, elapsedNowMs: Long
): BatterySessionPresentation {
    val connected = snapshot.connected && batteryHistoryIdentity(address)?.let { it == snapshot.address } == true
    return BatterySessionPresentation(
        presentBatterySessionSide(snapshot.left, connected, elapsedNowMs),
        presentBatterySessionSide(snapshot.right, connected, elapsedNowMs)
    )
}

private fun presentBatterySessionSide(
    series: BatterySessionSeries, connected: Boolean, now: Long
): BatterySessionSidePresentation {
    fun empty(state: BatterySessionEstimateState) = BatterySessionSidePresentation(series.component, state)
    if (!connected) return empty(BatterySessionEstimateState.DISCONNECTED)
    val lastReceived = series.lastReceivedAtElapsedMs
    if (now < 0 || (lastReceived != null && (lastReceived < 0 || lastReceived > now)))
        return empty(BatterySessionEstimateState.CLOCK_CHANGED)
    if (lastReceived == null) return empty(if (series.interruption == BatterySessionInterruption.CLOCK)
        BatterySessionEstimateState.CLOCK_CHANGED else BatterySessionEstimateState.WAITING)
    if (now - lastReceived > BATTERY_SESSION_MAX_GAP_MS) return empty(BatterySessionEstimateState.STALE)
    if (series.state == BatterySessionPartState.UNAVAILABLE) return empty(BatterySessionEstimateState.UNAVAILABLE)
    val level = series.level?.takeIf { it in 0..100 } ?: return empty(BatterySessionEstimateState.WAITING)
    if (series.state == BatterySessionPartState.CHARGING)
        return BatterySessionSidePresentation(series.component, BatterySessionEstimateState.CHARGING, level)
    if (series.state != BatterySessionPartState.DISCHARGING) return empty(BatterySessionEstimateState.WAITING)

    val retained = series.observations.filter { it.segment == series.segment && !it.charging }
    // Retention may omit a recent unchanged report. It is still a real received observation, so
    // use that exact timestamp as the end point without generating periodic synthetic samples.
    val points = if (retained.lastOrNull()?.receivedAtElapsedMs?.let { it < lastReceived } == true)
        (retained + BatterySessionObservation(lastReceived, level, false, series.segment))
            .takeLast(BATTERY_SESSION_MAX_OBSERVATIONS)
    else retained
    if (points.any { it.level !in 0..100 || it.receivedAtElapsedMs < 0 || it.receivedAtElapsedMs > lastReceived } ||
        points.zipWithNext().any { (a, b) -> b.receivedAtElapsedMs <= a.receivedAtElapsedMs || b.level > a.level })
        return empty(BatterySessionEstimateState.UNAVAILABLE)
    val duration = points.firstOrNull()?.let { lastReceived - it.receivedAtElapsedMs } ?: 0L
    val base = BatterySessionSidePresentation(series.component, BatterySessionEstimateState.COLLECTING,
        level, points, (duration / 60_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    if (points.size < 4 || duration < BATTERY_SESSION_MIN_SPAN_MS) return base

    // Integer percentage reports have quantization error. Use the fastest sufficiently observed
    // suffix, allowing two extra percentage points of drain. This is a cautious extrapolation,
    // not a confidence interval or a measure of active listening, capacity, health or cycles.
    var fastestPercentPerMs = 0.0
    var drops = 0
    for (index in points.lastIndex - 1 downTo 0) {
        if (points[index].level > points[index + 1].level) drops++
        val span = lastReceived - points[index].receivedAtElapsedMs
        val decline = points[index].level - level
        if (points.size - index >= 4 && span >= BATTERY_SESSION_MIN_SPAN_MS && decline >= 3 && drops >= 3) {
            fastestPercentPerMs = max(fastestPercentPerMs, (decline + 2).toDouble() / span.toDouble())
        }
    }
    if (fastestPercentPerMs <= 0.0) return base.copy(state = BatterySessionEstimateState.INSUFFICIENT_DRAIN)
    val remainingMs = max(0.0, max(0, level - 1).toDouble() / fastestPercentPerMs - (now - lastReceived).toDouble())
    // Very low observed drain is not enough evidence for a long runtime claim.
    if (!remainingMs.isFinite() || remainingMs > 12.0 * 60 * 60 * 1000)
        return base.copy(state = BatterySessionEstimateState.INSUFFICIENT_DRAIN)
    val minutes = min(720, (remainingMs / 60_000).toInt()) / 5 * 5
    return base.copy(state = BatterySessionEstimateState.ROUGH_ESTIMATE, roughRemainingMinutes = minutes)
}
