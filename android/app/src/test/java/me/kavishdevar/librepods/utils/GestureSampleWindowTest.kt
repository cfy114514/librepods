package me.kavishdevar.librepods.utils

import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class GestureSampleWindowTest {
    @Test fun wrappedSensorTailMatchesPreviousGestureStatisticsAndSampleOrder() {
        val window = GestureSampleWindow(100)
        val reference = mutableListOf<Double>()
        val random = Random(555)
        repeat(10_000) {
            val value = random.nextDouble() * 12_000 - 6000
            window.add(value)
            reference.add(value)
            if (reference.size > 100) reference.removeAt(0)
            assertEquals(reference.size, window.size)
            assertEquals(reference.first(), window[0], 0.0)
            assertEquals(reference.last(), window[window.size - 1], 0.0)
            val recent = reference.takeLast(4)
            val mean = recent.average()
            val variance = if (recent.size <= 1) 0.0 else recent.map { (it - mean) * (it - mean) }.average()
            assertEquals(mean, window.averageLast(4), 0.0)
            assertEquals(variance, window.varianceLast(4), 0.0)
            assertEquals(reference.takeLast(8).map { abs(it) }.average(), window.absoluteAverageLast(8), 0.0)
        }
    }

    @Test fun smoothingKeepsTheSameInitialZerosAndThreeSampleAverage() {
        val window = GestureSampleWindow(3)
        repeat(3) { window.add(0.0) }
        window.add(900.0)
        assertEquals(300.0, window.averageLast(), 0.0)
        window.add(-600.0)
        assertEquals(100.0, window.averageLast(), 0.0)
        window.add(300.0)
        assertEquals(200.0, window.averageLast(), 0.0)
    }

    @Test fun rhythmConfidencePreservesThePreviousNormalizedVariance() {
        val window = GestureSampleWindow(5)
        val values = mutableListOf<Double>()
        for (value in listOf(0.2, 0.4, 0.3, 0.5, 0.15, 0.21, 0.31, 0.2)) {
            window.add(value)
            values.add(value)
            val recent = values.takeLast(5)
            val mean = recent.average()
            assertEquals(recent.map { (it / mean - 1).pow(2) }.average(), window.normalizedVariance(mean), 0.0)
        }
    }

    @Test fun oneMillionSamplesStayBoundedAndNewSessionCannotReadOldSamples() {
        val window = GestureSampleWindow(100)
        repeat(1_000_000) { window.add(it.toDouble()) }
        assertEquals(100, window.size)
        assertEquals(999_900.0, window[0], 0.0)
        assertEquals(999_999.0, window[99], 0.0)
        window.clear()
        assertTrue(window.isEmpty())
        assertTrue(window.averageLast().isNaN())
        try { window[0]; fail("old sample must be inaccessible") } catch (_: IndexOutOfBoundsException) {}
        window.add(0.0)
        assertEquals(0.0, window[0], 0.0)
    }
}
