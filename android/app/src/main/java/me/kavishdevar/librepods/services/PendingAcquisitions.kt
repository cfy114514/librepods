package me.kavishdevar.librepods.services

/** One pending or executing platform resource per key; callers retain their own actions. */
internal class PendingAcquisitions<K : Any, V : Any>(keys: Set<K>) {
    data class Ticket<K>(val key: K, val id: Long)
    data class Offer<K, V>(val ticket: Ticket<K>, val acquire: Boolean, val previous: V? = null)
    data class Start<V>(val ready: Boolean, val rejected: V? = null)
    private class Slot<K, V>(val ticket: Ticket<K>, var desired: V?) {
        var started = false
        var active = false
    }
    private val keys = keys.toSet()
    private val slots = mutableMapOf<K, Slot<K, V>>()
    private var nextId = 0L

    init { require(this.keys.isNotEmpty()) }

    @Synchronized fun offer(key: K, value: V, valid: () -> Boolean): Offer<K, V>? {
        require(key in keys)
        if (!valid()) return null
        val existing = slots[key]
        if (existing != null) {
            val previous = existing.desired
            existing.desired = value
            return Offer(existing.ticket, false, previous)
        }
        val ticket = Ticket(key, ++nextId)
        slots[key] = Slot(ticket, value)
        return Offer(ticket, true)
    }

    /** Check the latest owner atomically before entering an uncancellable platform registration. */
    @Synchronized fun start(ticket: Ticket<K>, valid: (V) -> Boolean): Start<V> {
        val slot = slots[ticket.key] ?: return Start(false)
        if (slot.ticket != ticket || slot.started || slot.active) return Start(false)
        val desired = slot.desired ?: return Start(false)
        if (!valid(desired)) {
            slots.remove(ticket.key)
            return Start(false, desired)
        }
        slot.started = true
        return Start(true)
    }

    @Synchronized fun take(ticket: Ticket<K>): V? {
        val slot = slots[ticket.key] ?: return null
        if (slot.ticket != ticket || slot.active) return null
        slot.active = true
        return slot.desired.also { slot.desired = null }
    }

    /** A callback that arrived before the registration returned must not be rejected afterward. */
    @Synchronized fun rejected(ticket: Ticket<K>): V? {
        val slot = slots[ticket.key] ?: return null
        if (slot.ticket != ticket || slot.active) return null
        slots.remove(ticket.key)
        return slot.desired
    }

    /** Called only after the delivered platform resource has finished closing. */
    @Synchronized fun finished(ticket: Ticket<K>): Offer<K, V>? {
        val slot = slots[ticket.key] ?: return null
        if (slot.ticket != ticket || !slot.active) return null
        slots.remove(ticket.key)
        val desired = slot.desired ?: return null
        val next = Ticket(ticket.key, ++nextId)
        slots[ticket.key] = Slot(next, desired)
        return Offer(next, true)
    }
}
