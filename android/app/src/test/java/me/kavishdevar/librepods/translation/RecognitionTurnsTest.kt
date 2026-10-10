package me.kavishdevar.librepods.translation

import org.junit.Assert.*
import org.junit.Test

class RecognitionTurnsTest {
    @Test fun spokenOutputInvalidatesOldRecognitionAndBlocksNewListening() {
        val turns = RecognitionTurns()
        val original = turns.begin()!!
        turns.pause()
        assertFalse(turns.complete(original))
        assertNull(turns.begin())
        turns.resume()
        val next = turns.begin()!!
        assertFalse(turns.accepts(original))
        assertFalse(turns.complete(original))
        assertTrue(turns.accepts(next))
    }
    @Test fun duplicateResultsAndErrorCannotCompleteAnotherTurn() {
        val turns = RecognitionTurns()
        val first = turns.begin()!!
        assertNull(turns.begin())
        assertTrue(turns.complete(first))
        val second = turns.begin()!!
        assertFalse(turns.complete(first))
        assertTrue(turns.complete(second))
        assertFalse(turns.complete(second))
    }
    @Test fun watchdogFromCancelledListeningCannotStopReplacement() {
        val turns = RecognitionTurns()
        val first = turns.begin()!!
        turns.pause()
        turns.resume()
        val replacement = turns.begin()!!
        assertFalse(turns.accepts(first))
        assertTrue(turns.accepts(replacement))
    }
}
