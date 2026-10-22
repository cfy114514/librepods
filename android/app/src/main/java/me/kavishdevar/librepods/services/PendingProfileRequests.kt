package me.kavishdevar.librepods.services

import java.io.Closeable

/** One acquisition/operation per profile, with the latest pending action per known purpose. */
internal class PendingProfileRequests<P : Any, K : Any, V : Any>(
    profiles: Set<P>, purposes: Set<K>, private val onDiscard: (V) -> Unit = {}
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

    /** The admission predicate must be quick and side-effect free; rejection retains pending work. */
    fun offer(profile: P, purpose: K, value: V, valid: () -> Boolean = { true }): Offer<P>? {
        require(profile in profiles && purpose in purposes)
        var discarded: V? = null
        val offered = synchronized(this) {
            if (isClosed || !valid()) {
                discarded = value
                return@synchronized null
            }
            val existing = slots[profile]
            val slot = existing ?: Slot<P, K, V>(Ticket(profile, ++nextId)).also { slots[profile] = it }
            discarded = slot.pending.put(purpose, value)?.takeUnless { it === value }
            Offer(slot.ticket, existing == null)
        }
        // Completion can take another owner's lock or reenter this queue.
        discarded?.let(::discard)
        return offered
    }

    /** Transfers the delivered values to the caller, including responsibility for completion. */
    @Synchronized fun take(ticket: Ticket<P>): List<V>? {
        val slot = slots[ticket.profile] ?: return null
        if (isClosed || slot.ticket != ticket || slot.working) return null
        slot.working = true
        return slot.pending.values.toList().also { slot.pending.clear() }
    }

    /** Called after the acquired proxy has been closed, so acquisitions never overlap. */
    @Synchronized fun finished(ticket: Ticket<P>): Ticket<P>? {
        val slot = slots[ticket.profile] ?: return null
        if (isClosed || slot.ticket != ticket || !slot.working) return null
        slots.remove(ticket.profile)
        if (slot.pending.isEmpty()) return null
        val replacement = Slot<P, K, V>(Ticket(ticket.profile, ++nextId))
        replacement.pending.putAll(slot.pending)
        slots[ticket.profile] = replacement
        return replacement.ticket
    }

    /** A rejected acquisition has no proxy to close and must not retry indefinitely. */
    fun rejected(ticket: Ticket<P>) {
        val discarded = synchronized(this) {
            val slot = slots[ticket.profile] ?: return
            // A callback may have taken the proxy before registration reports failure.
            if (slot.ticket != ticket || slot.working) return
            slots.remove(ticket.profile)
            slot.pending.values.toList()
        }
        discarded.forEach(::discard)
    }

    @Synchronized fun contains(ticket: Ticket<P>): Boolean =
        !isClosed && slots[ticket.profile]?.ticket == ticket

    override fun close() {
        val discarded = synchronized(this) {
            if (isClosed) return
            isClosed = true
            slots.values.flatMap { it.pending.values }.also { slots.clear() }
        }
        discarded.forEach(::discard)
    }

    private fun discard(value: V) {
        // A failed completion must not prevent the remaining requests from settling.
        runCatching { onDiscard(value) }
    }
}
