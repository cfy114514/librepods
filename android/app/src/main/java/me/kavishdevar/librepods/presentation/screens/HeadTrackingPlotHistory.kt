package me.kavishdevar.librepods.presentation.screens

import kotlin.math.abs

/** Fixed-size sample history, accessed by the plot's main-thread collector and draw pass. */
internal class HeadTrackingPlotHistory(val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val horizontal = FloatArray(capacity)
    private val vertical = FloatArray(capacity)
    private var nextIndex = 0

    var size = 0
        private set

    fun add(horizontalValue: Float, verticalValue: Float) {
        horizontal[nextIndex] = horizontalValue
        vertical[nextIndex] = verticalValue
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
    }

    fun horizontalAt(index: Int): Float = horizontal[storageIndex(index)]

    fun verticalAt(index: Int): Float = vertical[storageIndex(index)]

    fun maxMagnitude(): Float {
        var maximum = 0f
        for (index in 0 until size) {
            maximum = maxOf(maximum, abs(horizontal[index]), abs(vertical[index]))
        }
        return maximum
    }

    private fun storageIndex(index: Int): Int {
        require(index in 0 until size)
        return if (size < capacity) index else (nextIndex + index) % capacity
    }
}
