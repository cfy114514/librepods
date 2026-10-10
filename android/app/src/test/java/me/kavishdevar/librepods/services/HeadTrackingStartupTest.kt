package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class HeadTrackingStartupTest {
    @Test fun silentStartRetriesOnceThenReportsUnavailable() {
        val startup = HeadTrackingStartup<Any>()
        val preferred = Any()
        startup.begin(preferred)
        assertEquals(HeadTrackingStatus.STARTING, startup.status.value)
        assertTrue(startup.timedOut(preferred, canRetry = true))
        assertFalse(startup.timedOut(preferred, canRetry = true))
        val fallback = Any()
        startup.begin(fallback)
        assertFalse(startup.timedOut(fallback, canRetry = false))
        assertEquals(HeadTrackingStatus.UNAVAILABLE, startup.status.value)
        // A late sensor sample is still usable, even after the startup deadline.
        startup.received(fallback)
        assertEquals(HeadTrackingStatus.RECEIVING, startup.status.value)
    }

    @Test fun confirmedSensorStreamDoesNotRetry() {
        val startup = HeadTrackingStartup<Any>()
        val request = Any()
        startup.begin(request)
        startup.received(request)
        assertFalse(startup.timedOut(request, canRetry = true))
        assertEquals(HeadTrackingStatus.RECEIVING, startup.status.value)
    }

    @Test fun failedRetryStopsWaitingButLateSamplesCanRecover() {
        val startup = HeadTrackingStartup<Any>()
        val request = Any()
        startup.begin(request)
        startup.failed()
        assertEquals(HeadTrackingStatus.UNAVAILABLE, startup.status.value)
        assertFalse(startup.timedOut(request, canRetry = true))
        startup.received(request)
        assertEquals(HeadTrackingStatus.RECEIVING, startup.status.value)
    }

    @Test fun replacementRejectsOldTimersAndSamplesEvenForEqualRequests() {
        val startup = HeadTrackingStartup<String>()
        val old = String(charArrayOf('a'))
        val replacement = String(charArrayOf('a'))
        assertEquals(old, replacement)
        startup.begin(old)
        startup.begin(replacement)
        startup.received(old)
        assertEquals(HeadTrackingStatus.STARTING, startup.status.value)
        assertFalse(startup.timedOut(old, canRetry = true))
        assertTrue(startup.timedOut(replacement, canRetry = true))
    }

    @Test fun disconnectOrStopCannotBeResurrectedByOldWork() {
        val startup = HeadTrackingStartup<Any>()
        val request = Any()
        startup.begin(request)
        startup.clear()
        startup.received(request)
        assertFalse(startup.timedOut(request, canRetry = true))
        assertEquals(HeadTrackingStatus.INACTIVE, startup.status.value)
        startup.clear(HeadTrackingStatus.UNAVAILABLE)
        assertEquals(HeadTrackingStatus.UNAVAILABLE, startup.status.value)
    }
}
