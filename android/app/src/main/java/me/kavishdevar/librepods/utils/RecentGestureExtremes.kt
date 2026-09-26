package me.kavishdevar.librepods.utils

/** Chronological tail used by gesture scoring. Access is guarded by GestureDetector's lock. */
internal class RecentGestureExtremes(private val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val values = ArrayDeque<Double>(capacity)

    val size: Int get() = values.size

    fun add(value: Double) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(value)
    }

    fun recent(count: Int): List<Double> = values.takeLast(count)

    fun clear() = values.clear()
}
