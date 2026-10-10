package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class LiveListenSessionsTest {
    @Test fun rapidStopAndRestartWaitsForMicrophoneRelease() {
        val sessions = LiveListenSessions()
        val old = sessions.begin()!!
        sessions.stop()
        assertFalse(old.active)
        assertEquals(LiveListenPhase.STOPPING, sessions.state.value.phase)
        assertNull(sessions.begin())
        sessions.listening(old)
        assertEquals(LiveListenPhase.STOPPING, sessions.state.value.phase)
        sessions.finish(old)
        val next = sessions.begin()!!
        assertNotEquals(old.id, next.id)
        sessions.finish(old)
        assertSame(next, sessions.find(next.id))
        assertEquals(LiveListenPhase.STARTING, sessions.state.value.phase)
    }

    @Test fun disconnectReasonSurvivesLaterCleanup() {
        val sessions = LiveListenSessions()
        val lease = sessions.begin()!!
        sessions.listening(lease)
        lease.stop(LiveListenError.ROUTE_CHANGED)
        lease.stop()
        lease.stop(LiveListenError.INITIALIZATION)
        sessions.finish(lease)
        assertEquals(LiveListenPhase.IDLE, sessions.state.value.phase)
        assertEquals(LiveListenError.ROUTE_CHANGED, sessions.state.value.error)
        sessions.begin()
        assertNull(sessions.state.value.error)
    }

    @Test fun explicitStopDoesNotReportLateNativeReadErrors() {
        val sessions = LiveListenSessions()
        val lease = sessions.begin()!!
        sessions.stop()
        lease.stop(LiveListenError.READ_FAILED)
        sessions.finish(lease)
        assertNull(sessions.state.value.error)
    }

    @Test fun staleAudioCallbacksCannotStopReplacementSession() {
        val sessions = LiveListenSessions()
        val old = sessions.begin()!!
        sessions.stop(old, LiveListenError.NO_MICROPHONE)
        assertEquals(LiveListenPhase.STOPPING, sessions.state.value.phase)
        sessions.finish(old)
        val replacement = sessions.begin()!!
        sessions.stop(old, LiveListenError.FOCUS_LOST)
        assertTrue(replacement.active)
        assertEquals(LiveListenPhase.STARTING, sessions.state.value.phase)
        assertNull(sessions.state.value.error)
    }
}
