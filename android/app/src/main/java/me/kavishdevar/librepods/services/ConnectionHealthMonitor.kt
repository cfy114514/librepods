package me.kavishdevar.librepods.services

import java.io.Closeable
import me.kavishdevar.librepods.data.ConnectionHealthSnapshot
import me.kavishdevar.librepods.data.HealthAudioProfile
import me.kavishdevar.librepods.data.HealthControlEvidence
import me.kavishdevar.librepods.data.HealthProfileEvidence
import me.kavishdevar.librepods.data.HealthProfileState
import me.kavishdevar.librepods.data.HealthServiceState
import me.kavishdevar.librepods.data.connectionHealthIdentity

/** Receives existing service events only. It never binds a profile, connects, or changes policy. */
internal class ConnectionHealthMonitor(private val clock: () -> Long) : Closeable {
    class Selection internal constructor(val peer: String, internal val generation: Long, internal val owner: Any)
    class ControlTicket internal constructor(internal val selection: Selection, internal val id: Long)
    class ProfileTicket internal constructor(internal val selection: Selection,
        internal val profile: HealthAudioProfile, internal val version: Long, internal val observedAt: Long)

    private val owner = Any()
    private var selected: Selection? = null
    private var generation = 0L
    private var nextControl = 0L
    private var activeControl: ControlTicket? = null
    private var control: HealthControlEvidence? = null
    private val profiles = mutableMapOf<HealthAudioProfile, HealthProfileEvidence>()
    private val profileVersions = mutableMapOf<HealthAudioProfile, Long>()
    private var service = HealthServiceState.STARTING
    private var closed = false
    private var bluetoothInvalidated = false

    @Synchronized fun selectPeer(address: String?): Selection? {
        if (closed) return null
        val peer = connectionHealthIdentity(address)
        if (selected?.peer != peer) {
            clearBluetoothEvidence()
            selected = peer?.let { Selection(it, generation, owner) }
        }
        return selected
    }

    @Synchronized fun selection(): Selection? = selected.takeUnless { closed }

    @Synchronized fun started() {
        if (!closed) service = HealthServiceState.STARTED
    }

    /** Permission loss or adapter shutdown retires same-peer observations and all in-flight work. */
    @Synchronized fun invalidateBluetooth() {
        if (closed || bluetoothInvalidated) return
        val peer = selected?.peer
        clearBluetoothEvidence()
        selected = peer?.let { Selection(it, generation, owner) }
        bluetoothInvalidated = true
    }

    @Synchronized fun profileChanged(selection: Selection, profile: HealthAudioProfile, state: HealthProfileState,
                                     observedAt: Long = clock()): Boolean {
        if (!current(selection)) return false
        if (observedAt < 0 || observedAt > clock() || observedAt < (profiles[profile]?.observedAt ?: 0L)) return false
        profileVersions[profile] = (profileVersions[profile] ?: 0L) + 1L
        profiles[profile] = HealthProfileEvidence(state, observedAt)
        bluetoothInvalidated = false
        return true
    }

    /** Optional piggyback on an already-owned profile query; never acquire a proxy for this. */
    @Synchronized fun beginProfileObservation(selection: Selection, profile: HealthAudioProfile): ProfileTicket? =
        if (current(selection)) ProfileTicket(selection, profile, profileVersions[profile] ?: 0L, clock()) else null

    @Synchronized fun profileObserved(ticket: ProfileTicket, state: HealthProfileState): Boolean {
        if (!current(ticket.selection) || ticket.version != (profileVersions[ticket.profile] ?: 0L)) return false
        // The remote read can block after obtaining its result. Completion cannot renew its age.
        return profileChanged(ticket.selection, ticket.profile, state, ticket.observedAt)
    }

    @Synchronized fun controlOpened(selection: Selection): ControlTicket? {
        if (!current(selection)) return null
        val ticket = ControlTicket(selection, ++nextControl)
        activeControl = ticket
        control = HealthControlEvidence(connected = true, changedAt = clock())
        bluetoothInvalidated = false
        return ticket
    }

    @Synchronized fun controlPacket(ticket: ControlTicket, receivedAt: Long = clock()): Boolean {
        if (!current(ticket.selection) || activeControl !== ticket) return false
        val previous = control ?: return false
        val now = clock()
        if (receivedAt < 0 || receivedAt > now || receivedAt < previous.changedAt ||
            receivedAt < (previous.lastPacketAt ?: 0L)) return false
        // Sensor streams can deliver hundreds of packets per second. One timestamp update per
        // second is enough for the two-minute display window and avoids per-packet UI allocation.
        // Keep the socket reader's receipt time even when publication waits on another lock.
        if (previous.lastPacketAt == null || receivedAt - previous.lastPacketAt >= 1_000L) {
            control = previous.copy(lastPacketAt = receivedAt)
        }
        return true
    }

    @Synchronized fun controlClosed(ticket: ControlTicket): Boolean {
        if (!current(ticket.selection) || activeControl !== ticket) return false
        activeControl = null
        control = HealthControlEvidence(connected = false, changedAt = clock())
        return true
    }

    /** Calling this on the bound live service is itself a response, not an OS liveness guarantee. */
    @Synchronized fun snapshot(): ConnectionHealthSnapshot = ConnectionHealthSnapshot(
        selected?.peer, generation, service, clock(), profiles.toMap(), control)

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        service = HealthServiceState.STOPPED
        clearBluetoothEvidence()
    }

    private fun current(selection: Selection): Boolean = !closed && selection.owner === owner && selected === selection

    private fun clearBluetoothEvidence() {
        generation++
        profiles.clear()
        profileVersions.clear()
        activeControl = null
        control = null
        bluetoothInvalidated = false
    }
}
