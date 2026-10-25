package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.data.HealthAudioProfile
import me.kavishdevar.librepods.data.HealthProfileState
import me.kavishdevar.librepods.data.HealthServiceState
import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthMonitorTest {
    private val first = "AA:BB:CC:DD:EE:01"
    private val second = "AA:BB:CC:DD:EE:02"
    private var now = 100L
    private fun monitor() = ConnectionHealthMonitor { now }

    @Test fun startsWithNoInventedProfileOrControlEvidence() {
        val monitor = monitor()
        monitor.selectPeer(first)
        monitor.started()
        val value = monitor.snapshot()
        assertEquals(HealthServiceState.STARTED, value.service)
        assertTrue(value.profiles.isEmpty())
        assertNull(value.control)
    }

    @Test fun selectingAnotherPeerAndReturningRetiresEarlierCallbacks() {
        val monitor = monitor()
        val old = monitor.selectPeer(first)!!
        val socket = monitor.controlOpened(old)!!
        monitor.selectPeer(second)
        val current = monitor.selectPeer(first)!!
        assertFalse(monitor.profileChanged(old, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED))
        assertFalse(monitor.controlPacket(socket))
        assertTrue(monitor.profileChanged(current, HealthAudioProfile.CALLS, HealthProfileState.CONNECTED))
        assertEquals(setOf(HealthAudioProfile.CALLS), monitor.snapshot().profiles.keys)
    }

    @Test fun newControlSessionCannotInheritPacketsOrDisconnectFromOldSession() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first)!!
        val old = monitor.controlOpened(selected)!!
        monitor.controlPacket(old)
        val current = monitor.controlOpened(selected)!!
        assertFalse(monitor.controlPacket(old))
        assertFalse(monitor.controlClosed(old))
        assertNull(monitor.snapshot().control!!.lastPacketAt)
        assertTrue(monitor.controlPacket(current))
        now = 200
        assertTrue(monitor.controlClosed(current))
        assertFalse(monitor.snapshot().control!!.connected)
        assertEquals(now, monitor.snapshot().control!!.changedAt)
    }

    @Test fun delayedQueryCannotOverwriteNewerProfileBroadcast() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first)!!
        val query = monitor.beginProfileObservation(selected, HealthAudioProfile.MEDIA)!!
        monitor.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.DISCONNECTED)
        assertFalse(monitor.profileObserved(query, HealthProfileState.CONNECTED))
        assertEquals(HealthProfileState.DISCONNECTED, monitor.snapshot().profiles[HealthAudioProfile.MEDIA]!!.state)
    }

    @Test fun profileQuerySettlesOnlyOnceAndDoesNotAffectOtherProfile() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first)!!
        val media = monitor.beginProfileObservation(selected, HealthAudioProfile.MEDIA)!!
        val calls = monitor.beginProfileObservation(selected, HealthAudioProfile.CALLS)!!
        assertTrue(monitor.profileObserved(media, HealthProfileState.CONNECTED))
        assertFalse(monitor.profileObserved(media, HealthProfileState.DISCONNECTED))
        assertTrue(monitor.profileObserved(calls, HealthProfileState.DISCONNECTED))
        assertEquals(HealthProfileState.CONNECTED, monitor.snapshot().profiles[HealthAudioProfile.MEDIA]!!.state)
    }

    @Test fun adapterOrPermissionLossRetiresSamePeerEvidenceAndTickets() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first)!!
        val channel = monitor.controlOpened(selected)!!
        monitor.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
        val query = monitor.beginProfileObservation(selected, HealthAudioProfile.CALLS)!!
        monitor.invalidateBluetooth()
        val generation = monitor.snapshot().selectionGeneration
        repeat(1000) { monitor.invalidateBluetooth() }
        assertEquals(generation, monitor.snapshot().selectionGeneration)
        assertFalse(monitor.controlPacket(channel))
        assertFalse(monitor.profileObserved(query, HealthProfileState.CONNECTED))
        assertFalse(monitor.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED))
        assertNull(monitor.snapshot().control)
        assertTrue(monitor.snapshot().profiles.isEmpty())
        assertEquals(first, monitor.snapshot().peer)
        assertTrue(monitor.profileChanged(monitor.selection()!!, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED))
    }

    @Test fun closeRejectsAllLateCallbacksAndRemainsStopped() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first)!!
        val channel = monitor.controlOpened(selected)!!
        monitor.close()
        monitor.started()
        assertNull(monitor.selectPeer(second))
        assertNull(monitor.selection())
        assertFalse(monitor.controlPacket(channel))
        assertFalse(monitor.controlClosed(channel))
        assertFalse(monitor.profileChanged(selected, HealthAudioProfile.CALLS, HealthProfileState.CONNECTED))
        assertEquals(HealthServiceState.STOPPED, monitor.snapshot().service)
    }

    @Test fun snapshotsAreImmutableCopiesAndServiceReplacementCannotBorrowTickets() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first)!!
        monitor.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
        val before = monitor.snapshot()
        monitor.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.DISCONNECTED)
        assertEquals(HealthProfileState.CONNECTED, before.profiles[HealthAudioProfile.MEDIA]!!.state)
        val replacement = monitor()
        replacement.selectPeer(first)
        assertFalse(replacement.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED))
        assertNull(replacement.controlOpened(selected))
    }

    @Test fun normalizedSamePeerPreservesEvidenceAndInvalidPeerClearsIt() {
        val monitor = monitor()
        val selected = monitor.selectPeer(first.lowercase())!!
        monitor.profileChanged(selected, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
        assertSame(selected, monitor.selectPeer(" $first "))
        assertNull(monitor.selectPeer("invalid"))
        assertTrue(monitor.snapshot().profiles.isEmpty())
        assertNull(monitor.snapshot().peer)
    }
}
