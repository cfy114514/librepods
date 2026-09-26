package me.kavishdevar.librepods.presentation.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HeadTrackingPlotHistoryTest {
    @Test fun emptyHistoryHasNoMagnitude() {
        val history = HeadTrackingPlotHistory(100)

        assertEquals(0, history.size)
        assertEquals(0f, history.maxMagnitude(), 0f)
    }

    @Test fun wrappedHistoryRetainsChronologicalOrderAndFloatPrecision() {
        val history = HeadTrackingPlotHistory(3)
        for (value in 1..5) history.add(value + 0.125f, -value - 0.75f)

        assertEquals(3, history.size)
        for (index in 0..2) {
            assertEquals(index + 3.125f, history.horizontalAt(index), 0f)
            assertEquals(-index - 3.75f, history.verticalAt(index), 0f)
        }
    }

    @Test fun evictingLargestSampleUpdatesAutomaticScale() {
        val history = HeadTrackingPlotHistory(2)
        history.add(-20_000f, 0f)
        history.add(100f, 200f)
        assertEquals(20_000f, history.maxMagnitude(), 0f)

        history.add(-300f, 100f)

        assertEquals(300f, history.maxMagnitude(), 0f)
    }

    @Test fun longStreamMatchesPreviousLimitedListBehavior() {
        val history = HeadTrackingPlotHistory(100)
        val reference = ArrayDeque<Pair<Float, Float>>()
        val random = Random(42)

        repeat(2000) {
            val horizontal = random.nextInt(-32_768, 32_768).toFloat()
            val vertical = random.nextInt(-32_768, 32_768).toFloat()
            reference.addLast(horizontal to vertical)
            if (reference.size > 100) reference.removeFirst()
            history.add(horizontal, vertical)

            assertEquals(reference.size, history.size)
            reference.forEachIndexed { index, point ->
                assertEquals(point.first, history.horizontalAt(index), 0f)
                assertEquals(point.second, history.verticalAt(index), 0f)
            }
            val expectedMaximum = reference.maxOf { maxOf(abs(it.first), abs(it.second)) }
            assertEquals(expectedMaximum, history.maxMagnitude(), 0f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityIsRejected() {
        HeadTrackingPlotHistory(0)
    }
}
