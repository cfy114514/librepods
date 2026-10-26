package android.os
interface Parcelable
object Build {
    object VERSION { var SDK_INT = 35 }
    object VERSION_CODES { const val TIRAMISU = 33 }
}
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun postDelayed(runnable: Runnable, delay: Long): Boolean {
        if (rejectPosts) return false
        pending.add(runnable to (now + delay))
        return true
    }
    fun removeCallbacks(runnable: Runnable) { pending.removeAll { it.first === runnable } }
    companion object {
        var now = 0L
        var rejectPosts = false
        val pending = mutableListOf<Pair<Runnable, Long>>()
        fun advanceBy(delta: Long) {
            now += delta
            val due = pending.filter { it.second <= now }
            pending.removeAll(due.toSet())
            due.forEach { it.first.run() }
        }
        fun reset() { now = 0L; rejectPosts = false; pending.clear() }
    }
}
