package me.kavishdevar.librepods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentGestureExtremesTest {
    @Test
    fun longSessionRetainsOnlyRecentExtremesInMovementOrder() {
        val history = RecentGestureExtremes(4)
        repeat(100_000) { index ->
            history.add(if (index % 2 == 0) 700.0 else -800.0)
            assertTrue("History must stay bounded throughout a session", history.size <= 4)
        }

        // Peaks and troughs must stay interleaved even after the 100-sample sensor buffer fills.
        assertEquals(listOf(700.0, -800.0, 700.0, -800.0), history.recent(4))
        assertEquals(listOf(-800.0, 700.0, -800.0), history.recent(3))
    }

    @Test
    fun switchingBetweenFastAndSlowScoringUsesTheSameChronologicalTail() {
        val history = RecentGestureExtremes(4)
        val reference = mutableListOf<Double>()
        repeat(240) { index ->
            val value = (index + 500.0) * if (index % 2 == 0) 1 else -1
            reference.add(value)
            history.add(value)
            assertEquals(reference.takeLast(3), history.recent(3))
            assertEquals(reference.takeLast(4), history.recent(4))
        }
    }

    @Test
    fun newSessionDoesNotScorePreviousMovements() {
        val history = RecentGestureExtremes(4)
        listOf(700.0, -800.0, 900.0, -1000.0).forEach(history::add)
        history.clear()
        history.add(600.0)

        assertEquals(1, history.size)
        assertEquals(listOf(600.0), history.recent(4))
    }
}
