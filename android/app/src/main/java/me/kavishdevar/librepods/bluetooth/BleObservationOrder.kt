package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.TimeUnit

/** One selected AirPods identity can advertise through multiple rotating addresses. */
internal class BleObservationOrder(timeoutMillis: Long) {
    private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private var newestTimestampNanos: Long? = null

    fun accept(timestampNanos: Long, nowNanos: Long): Boolean {
        if (timestampNanos < 0 || timestampNanos > nowNanos || nowNanos - timestampNanos > timeoutNanos) return false
        if (newestTimestampNanos?.let { timestampNanos <= it } == true) return false
        newestTimestampNanos = timestampNanos
        return true
    }

    fun reset() {
        newestTimestampNanos = null
    }
}
