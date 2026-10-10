package me.kavishdevar.librepods.services

/** Battery updates change audio policy only on a charging transition/session. */
internal class AudioChargingState {
    private var previous: Pair<String, Boolean>? = null

    @Synchronized
    fun changed(address: String?, bothCharging: Boolean): Boolean {
        if (address == null) return false
        val current = address to bothCharging
        if (current == previous) return false
        previous = current
        return true
    }

    @Synchronized
    fun reset() { previous = null }
}
