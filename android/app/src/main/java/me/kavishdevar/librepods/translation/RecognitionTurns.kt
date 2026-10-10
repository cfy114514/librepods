package me.kavishdevar.librepods.translation

/** Each listen owns its callbacks. Cancellation and spoken output invalidate that ownership. */
internal class RecognitionTurns {
    private var nextId = 0L
    private var current: Long? = null
    private var paused = false
    @Synchronized fun begin(): Long? {
        if (paused || current != null) return null
        return (++nextId).also { current = it }
    }
    @Synchronized fun accepts(id: Long): Boolean = current == id
    @Synchronized fun complete(id: Long): Boolean {
        if (current != id) return false
        current = null
        return true
    }
    @Synchronized fun pause() { current = null; paused = true }
    @Synchronized fun resume() { paused = false }
}
