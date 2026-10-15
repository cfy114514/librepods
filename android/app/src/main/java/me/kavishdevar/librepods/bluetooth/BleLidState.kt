package me.kavishdevar.librepods.bluetooth

/** Tracks the global lid state using the newest scan observation across addresses. */
internal class BleLidState {
    private var current: Boolean? = null
    private var newestTimestampNanos: Long? = null

    fun observe(lidOpen: Boolean, timestampNanos: Long): Boolean {
        val newest = newestTimestampNanos
        if (newest != null && timestampNanos <= newest) return false
        newestTimestampNanos = timestampNanos
        if (current == lidOpen) return false
        current = lidOpen
        return true
    }

    fun closeIfTimedOut(now: Long, lastBroadcastTime: Long, timeoutMillis: Long): Boolean {
        if (now - lastBroadcastTime <= timeoutMillis || current != true) return false
        current = false
        return true
    }

    fun reset() {
        current = null
        newestTimestampNanos = null
    }
}
