package me.kavishdevar.librepods.services

import java.io.Closeable

/** One acquisition/operation per profile, with the latest pending action per known purpose. */
internal class PendingProfileRequests<P : Any, K : Any, V : Any>(
    profiles: Set<P>, purposes: Set<K>
) : Closeable {
    data class Ticket<P : Any>(val profile: P, val id: Long)
    data class Offer<P : Any>(val ticket: Ticket<P>, val acquire: Boolean)
    private class Slot<P : Any, K : Any, V : Any>(val ticket: Ticket<P>) {
        var working = false
        val pending = linkedMapOf<K, V>()
    }
    private val profiles = profiles.toSet()
    private val purposes = purposes.toSet()
    private val slots = mutableMapOf<P, Slot<P, K, V>>()
    private var nextId = 0L
    @Volatile var isClosed = false
        private set

    @Synchronized fun offer(profile: P, purpose: K, value: V): Offer<P>? {
        require(profile in profiles && purpose in purposes)
        if (isClosed) return null
        val existing = slots[profile]
        val slot = existing ?: Slot<P, K, V>(Ticket(profile, ++nextId)).also { slots[profile] = it }
        slot.pending[purpose] = value
        return Offer(slot.ticket, existing == null)
    }

    @Synchronized fun take(ticket: Ticket<P>): List<V>? {
        val slot = slots[ticket.profile] ?: return null
        if (isClosed || slot.ticket != ticket || slot.working) return null
        slot.working = true
        return slot.pending.values.toList().also { slot.pending.clear() }
    }

    /** Called after the acquired proxy has been closed, so acquisitions never overlap. */
    @Synchronized fun finished(ticket: Ticket<P>): Ticket<P>? {
        val slot = slots[ticket.profile] ?: return null
        if (isClosed || slot.ticket != ticket) return null
        slots.remove(ticket.profile)
        if (slot.pending.isEmpty()) return null
        val replacement = Slot<P, K, V>(Ticket(ticket.profile, ++nextId))
        replacement.pending.putAll(slot.pending)
        slots[ticket.profile] = replacement
        return replacement.ticket
    }

    /** A rejected acquisition has no proxy to close and must not retry indefinitely. */
    @Synchronized fun rejected(ticket: Ticket<P>) {
        if (slots[ticket.profile]?.ticket == ticket) slots.remove(ticket.profile)
    }

    @Synchronized fun contains(ticket: Ticket<P>): Boolean =
        !isClosed && slots[ticket.profile]?.ticket == ticket

    @Synchronized override fun close() { isClosed = true; slots.clear() }
}
