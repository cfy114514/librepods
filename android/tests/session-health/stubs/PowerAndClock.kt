package android.os

object SystemClock {
    var now: Long = 0
    fun elapsedRealtime(): Long = now
}
open class PowerManager {
    open fun isIgnoringBatteryOptimizations(packageName: String): Boolean = false
}
