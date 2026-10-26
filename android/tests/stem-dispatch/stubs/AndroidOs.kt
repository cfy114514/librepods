package android.os
class Bundle {
    private val values = mutableMapOf<String, Any>()
    fun getBoolean(key: String) = values[key] == true
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
}
object SystemClock { var now = 100L; fun elapsedRealtime() = now }
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    // Expiry is always checked from elapsedRealtime by the production registry, even before cleanup runs.
    fun postDelayed(action: Runnable, delay: Long) = true
    fun removeCallbacks(action: Runnable) = Unit
}
