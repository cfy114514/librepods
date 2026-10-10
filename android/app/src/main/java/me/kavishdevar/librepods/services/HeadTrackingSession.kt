package me.kavishdevar.librepods.services

import java.io.Closeable

/** Desired head-tracking state; a delayed resume cannot borrow a newer user's request. */
internal class HeadTrackingSession : Closeable {
    enum class Consumer { MANUAL, PREVIEW, GESTURE }
    data class Lease(val id: Long, val consumer: Consumer, val peer: String?)
    data class Ticket(val id: Long, val enabled: Boolean, val peer: String?)
    private val leases = mutableMapOf<Consumer, Lease>()
    private var leaseId = 0L
    @Volatile private var ticket = Ticket(0, false, null)
    @Volatile private var closed = false

    @Synchronized fun request(enabled: Boolean, peer: String?): Ticket? {
        if (closed) return null
        if (enabled) acquire(Consumer.MANUAL, peer)
        else leases[Consumer.MANUAL]?.takeIf { it.peer == peer }?.let(::release)
        return ticket
    }

    @Synchronized fun acquire(consumer: Consumer, peer: String?): Lease? {
        if (closed) return null
        if (ticket.peer != peer) { leases.clear(); update(false, peer) }
        val lease = Lease(++leaseId, consumer, peer)
        leases[consumer] = lease
        update(true, peer)
        return lease
    }

    @Synchronized fun release(lease: Lease): Ticket? {
        if (closed || leases[lease.consumer] != lease) return null
        leases.remove(lease.consumer)
        update(leases.isNotEmpty(), ticket.peer)
        return ticket
    }

    @Synchronized fun selectPeer(peer: String?) {
        if (!closed && ticket.peer != peer) {
            leases.clear()
            update(false, peer)
        }
    }

    private fun update(enabled: Boolean, peer: String?) {
        if (ticket.enabled != enabled || ticket.peer != peer)
            ticket = Ticket(ticket.id + 1, enabled, peer)
    }
    fun capture(): Ticket = ticket
    fun isCurrent(value: Ticket): Boolean = !closed && value == ticket
    fun currentStart(id: Long): Ticket? = ticket.takeIf { !closed && it.id == id && it.enabled }
    @Synchronized override fun close() {
        closed = true
        leases.clear()
        ticket = Ticket(ticket.id + 1, false, null)
    }
}
