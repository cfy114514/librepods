package me.kavishdevar.librepods.utils

import kotlin.math.abs
import kotlin.math.pow

/** Bounded primitive history. The owning GestureDetector serializes access. */
internal class GestureSampleWindow(private val capacity: Int) {
    init { require(capacity > 0) }
    private val values = DoubleArray(capacity)
    private var start = 0
    var size = 0
        private set

    fun add(value: Double) {
        if (size == capacity) {
            values[start] = value
            start = (start + 1) % capacity
        } else {
            values[(start + size) % capacity] = value
            size++
        }
    }

    operator fun get(index: Int): Double {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException(index.toString())
        return values[(start + index) % capacity]
    }

    fun clear() { start = 0; size = 0 }
    fun isEmpty(): Boolean = size == 0

    fun averageLast(count: Int = size): Double {
        require(count >= 0)
        val n = minOf(count, size)
        if (n == 0) return Double.NaN
        var sum = 0.0
        for (index in size - n until size) sum += this[index]
        return sum / n
    }

    fun absoluteAverageLast(count: Int): Double {
        require(count >= 0)
        val n = minOf(count, size)
        if (n == 0) return Double.NaN
        var sum = 0.0
        for (index in size - n until size) sum += abs(this[index])
        return sum / n
    }

    fun varianceLast(count: Int): Double {
        require(count >= 0)
        val n = minOf(count, size)
        if (n <= 1) return 0.0
        val mean = averageLast(n)
        var sum = 0.0
        for (index in size - n until size) {
            val difference = this[index] - mean
            sum += difference * difference
        }
        return sum / n
    }

    fun normalizedVariance(mean: Double): Double {
        if (size == 0) return Double.NaN
        var sum = 0.0
        for (index in 0 until size) sum += (this[index] / mean - 1.0).pow(2)
        return sum / size
    }
}
