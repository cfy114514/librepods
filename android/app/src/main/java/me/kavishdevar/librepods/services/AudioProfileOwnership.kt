package me.kavishdevar.librepods.services

/** Grants background reconnects only for profiles this process released or explicitly claimed. */
internal class AudioProfileOwnership(profiles: Set<Int>) {
    private val profiles = profiles.toSet()
    // Binder actions remain ordered, but never hold the monitor used by selection/revocation.
    private val policyLock = Any()
    enum class Operation { RELEASE, RESTORE, EXPLICIT_CONNECT }
    class Ticket internal constructor(
        val address: String, val epoch: Long, val operation: Operation,
        val profiles: Set<Int>, val releasedProfiles: Set<Int> = emptySet(),
        val restorable: Boolean = false, internal val grantEpoch: Long = 0
    )

    private var address: String? = null
    private var epoch = 0L
    private var grantEpoch = 0L
    private var ownershipLost = false
    private data class MediaState(val address: String?, val epoch: Long, val lost: Boolean)
    internal class MediaTicket internal constructor(val address: String, val epoch: Long)
    internal class SelectionTicket internal constructor(val address: String, val epoch: Long)
    @Volatile private var mediaState = MediaState(null, 0, false)

    // Hot callbacks read one immutable snapshot without acquiring the state monitor.
    fun captureMedia(peer: String): MediaTicket? {
        val state = mediaState
        return if (!state.lost && state.address?.equals(peer, ignoreCase = true) == true)
            MediaTicket(state.address, state.epoch) else null
    }

    fun isCurrentMedia(ticket: MediaTicket): Boolean {
        val state = mediaState
        return !state.lost && state.epoch == ticket.epoch &&
            state.address?.equals(ticket.address, ignoreCase = true) == true
    }

    fun isCurrentSnapshot(ticket: Ticket): Boolean {
        val state = mediaState
        return state.epoch == ticket.epoch && state.address?.equals(ticket.address, ignoreCase = true) == true
    }

    fun captureSelection(peer: String): SelectionTicket? {
        val state = mediaState
        return if (state.address?.equals(peer, ignoreCase = true) == true)
            SelectionTicket(state.address, state.epoch) else null
    }

    @Synchronized fun beginExplicitConnectIfCurrent(
        selection: SelectionTicket, valid: () -> Boolean = { true }
    ): Ticket? {
        if (!matches(selection.address) || epoch != selection.epoch || !valid()) return null
        // A late task must never select its old peer again.
        return beginExplicitConnect(selection.address)
    }

    private fun publishMediaState() { mediaState = MediaState(address, epoch, ownershipLost) }
    private var latestOperation: Operation? = null
    private val released = mutableSetOf<Int>()
    private val claimed = mutableSetOf<Int>()
    private val pendingReleases = mutableMapOf<Int, Ticket>()

    @Synchronized fun select(peer: String?) {
        if (address.equals(peer, ignoreCase = true)) return
        address = peer
        revoke()
    }

    @Synchronized fun revoke(lost: Boolean = false) {
        epoch++
        grantEpoch++
        pendingReleases.clear()
        released.clear()
        claimed.clear()
        ownershipLost = lost
        latestOperation = null
        publishMediaState()
    }

    @Synchronized fun claim(peer: String) {
        select(peer)
        revoke()
        claimed.addAll(profiles)
    }

    @Synchronized fun socketClosed() {
        epoch++
        claimed.clear()
        latestOperation = null
        // A policy set to FORBIDDEN outlives the control socket. Keep accepted releases.
        publishMediaState()
    }

    @Synchronized fun cancelClaim() {
        if (claimed.isEmpty()) return
        epoch++
        claimed.clear()
        latestOperation = null
        publishMediaState()
    }

    @Synchronized fun cancelPendingRestore() {
        if (latestOperation != Operation.RESTORE) return
        epoch++
        latestOperation = null
        publishMediaState()
    }

    @Synchronized fun localProfileConnected(peer: String, profile: Int) {
        if (!matches(peer)) return
        if (ownershipLost) {
            epoch++
            ownershipLost = false
            publishMediaState()
        }
        released.remove(profile)
        claimed.remove(profile)
    }

    @Synchronized fun hasReleased(peer: String): Boolean =
        matches(peer) && !ownershipLost && released.isNotEmpty()

    fun allowsMediaControl(peer: String): Boolean {
        val state = mediaState
        return !state.lost && state.address?.equals(peer, ignoreCase = true) == true
    }

    @Synchronized fun beginRelease(peer: String, restorable: Boolean): Ticket? {
        if (!matches(peer) || (restorable && ownershipLost)) return null
        epoch++
        latestOperation = Operation.RELEASE
        publishMediaState()
        return Ticket(peer, epoch, Operation.RELEASE, profiles, restorable = restorable, grantEpoch = grantEpoch)
    }

    @Synchronized fun beginRestore(peer: String): Ticket? {
        if (!matches(peer) || ownershipLost || latestOperation == Operation.EXPLICIT_CONNECT) return null
        // Invalidate a pending release even when it has not yet granted a restore.
        epoch++
        latestOperation = Operation.RESTORE
        publishMediaState()
        // A policy call already in flight may forbid this profile after the wearer returns.
        // Its queued restore will run only if that call actually grants a release.
        val pending = pendingReleases.filterValues { it.restorable && it.grantEpoch == grantEpoch }.keys
        val requested = released + claimed + pending
        if (requested.isEmpty()) return null
        return Ticket(peer, epoch, Operation.RESTORE, requested, (released + pending).toSet(), grantEpoch = grantEpoch)
    }

    @Synchronized fun beginExplicitConnect(peer: String): Ticket {
        select(peer)
        revoke()
        latestOperation = Operation.EXPLICIT_CONNECT
        return Ticket(peer, epoch, Operation.EXPLICIT_CONNECT, profiles, grantEpoch = grantEpoch)
    }

    @Synchronized fun isCurrent(ticket: Ticket): Boolean =
        matches(ticket.address) && ticket.epoch == epoch

    /** Order Binder actions; recheck state at entry and after the uninterruptible action. */
    fun withCurrent(
        ticket: Ticket, profile: Int, valid: () -> Boolean = { true }, action: () -> Unit
    ): Boolean = synchronized(policyLock) {
        if (!valid() || !eligible(ticket, profile)) return@synchronized false
        action()
        isCurrent(ticket) && valid()
    }

    @Synchronized private fun eligible(ticket: Ticket, profile: Int): Boolean =
        isCurrent(ticket) && profile in ticket.profiles &&
            (ticket.operation != Operation.RESTORE || profile in released || profile in claimed)

    /** Register only a policy write about to enter Binder, not a pending profile lookup. */
    fun applyReleasePolicy(
        ticket: Ticket, profile: Int, valid: () -> Boolean = { true }, changePolicy: () -> Boolean
    ): Boolean = synchronized(policyLock) {
        if (!valid()) return@synchronized false
        val entered = synchronized(this) {
            if (!isCurrent(ticket) || ticket.operation != Operation.RELEASE || profile !in ticket.profiles) false
            else { pendingReleases[profile] = ticket; true }
        }
        if (!entered) return@synchronized false
        var accepted = false
        try {
            accepted = changePolicy()
            accepted
        } finally { recordReleased(ticket, profile, accepted) }
    }

    @Synchronized fun recordReleased(ticket: Ticket, profile: Int, accepted: Boolean) {
        val inFlight = pendingReleases[profile] === ticket
        if (inFlight) pendingReleases.remove(profile)
        val belongsToGrant = inFlight && matches(ticket.address) && ticket.grantEpoch == grantEpoch
        if ((isCurrent(ticket) || belongsToGrant) && ticket.operation == Operation.RELEASE && ticket.restorable &&
            !ownershipLost && profile in ticket.profiles && accepted) released.add(profile)
    }

    @Synchronized fun recordRestored(ticket: Ticket, profile: Int) {
        if (!isCurrent(ticket) || ticket.operation != Operation.RESTORE) return
        released.remove(profile)
        claimed.remove(profile)
    }

    private fun matches(peer: String): Boolean = address?.equals(peer, ignoreCase = true) == true
}
