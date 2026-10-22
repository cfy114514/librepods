package android.os

object SystemClock {
    var now: Long = 0
    fun elapsedRealtime() = now
    fun elapsedRealtimeNanos() = now * 1_000_000
}
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    companion object {
        private val pending = mutableListOf<Runnable>()
        fun runPending() {
            val dispatched = pending.toList()
            pending.clear()
            dispatched.forEach { it.run() }
        }
    }
    fun postDelayed(task: Runnable, millis: Long): Boolean { pending.add(task); return true }
    fun removeCallbacks(task: Runnable) { pending.removeAll { it === task } }
}
