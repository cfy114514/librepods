package me.kavishdevar.librepods.presentation.widgets

/** Phone widgets display only percentage and charging, not temperature, voltage or health. */
internal class PhoneBatteryUpdateGate {
    private data class State(val percentage: Int?, val charging: Boolean)
    private var previous: State? = null

    fun changed(level: Int, scale: Int, charging: Boolean): Boolean {
        val percentage = if (scale > 0 && level in 0..scale) (level * 100L / scale).toInt() else null
        val state = State(percentage, charging)
        if (state == previous) return false
        previous = state
        return true
    }

    fun reset() { previous = null }
}
