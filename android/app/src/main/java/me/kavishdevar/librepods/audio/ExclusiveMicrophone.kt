package me.kavishdevar.librepods.audio

/** A stop request does not free ownership until that service releases its audio resources. */
internal class ExclusiveMicrophone {
    private var owner: Any? = null
    private var claimed = false
    @Synchronized fun acquire(candidate: Any): Boolean {
        if (owner != null) return owner === candidate
        owner = candidate
        claimed = false
        return true
    }
    /** Service acceptance and pending cancellation are mutually exclusive. */
    @Synchronized fun claim(candidate: Any): Boolean {
        if (owner !== candidate) return false
        claimed = true
        return true
    }
    @Synchronized fun cancelPending(candidate: Any): Boolean {
        if (owner !== candidate || claimed) return false
        owner = null
        return true
    }
    @Synchronized fun release(candidate: Any) {
        if (owner === candidate) { owner = null; claimed = false }
    }
}

internal val microphoneOwnership = ExclusiveMicrophone()
