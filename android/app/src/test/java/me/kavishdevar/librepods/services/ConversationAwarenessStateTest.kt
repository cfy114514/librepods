package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.services.ConversationAwarenessState.Change.*
import org.junit.Assert.*
import org.junit.Test

class ConversationAwarenessStateTest {
    @Test fun documentedEndLevelThreeRestoresWithoutANoiseModeSwitch() {
        val state = ConversationAwarenessState()
        assertEquals(LOWER, state.update(1))
        assertEquals(NONE, state.update(2))
        assertEquals(RESTORE, state.update(3))
        assertFalse(state.isSpeaking)
        assertEquals(NONE, state.update(8))
        assertEquals(NONE, state.update(9))
    }

    @Test fun allExistingFirmwareEndLevelsStillRestore() {
        for (end in listOf(3, 6, 8, 9)) {
            val state = ConversationAwarenessState()
            assertEquals(LOWER, state.update(2))
            assertEquals(RESTORE, state.update(end))
            assertEquals(NONE, state.update(end))
        }
    }

    @Test fun anIntermediateReplacingAQueuedStartStillLowersVolume() {
        val incoming = ConversationAwarenessState()
        val output = ConversationAwarenessState()
        incoming.update(1)
        incoming.update(4)
        incoming.update(5)
        assertTrue(incoming.isSpeaking)
        assertEquals(LOWER, output.applyDesired(incoming.isSpeaking))
        incoming.update(3)
        assertEquals(RESTORE, output.applyDesired(incoming.isSpeaking))
    }

    @Test fun aWholeConversationEndingBeforeTheMainQueueRunsNeedsNoVolumeMutation() {
        val incoming = ConversationAwarenessState()
        val output = ConversationAwarenessState()
        incoming.update(1)
        incoming.update(3)
        assertEquals(NONE, output.applyDesired(incoming.isSpeaking))
    }

    @Test fun newConnectionAndUnknownLevelsCannotInventSpeechOrReplayTheOldSession() {
        val state = ConversationAwarenessState()
        state.update(1)
        state.reset()
        for (level in listOf(0, 4, 5, 7, 10, 127, 255)) assertEquals(NONE, state.update(level))
        assertFalse(state.isSpeaking)
        assertEquals(NONE, state.update(3))
        assertEquals(LOWER, state.update(2))
        assertEquals(NONE, state.update(127))
        assertTrue(state.isSpeaking)
    }
}
