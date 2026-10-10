package me.kavishdevar.librepods.utils

/** One batch per known key; later requests reuse its resource and invalidate older work. */
internal class CoalescedRequests<K : Any, V : Any>(keys: Set<K>) {
    data class Ticket<K>(val key: K, val id: Long)
    data class Offer<K>(val ticket: Ticket<K>, val created: Boolean)
    data class Work<K, V>(val ticket: Ticket<K>, val version: Long, val value: V)
    private class Slot<K, V>(val ticket: Ticket<K>, var value: V) {
        var version = 0L
        var pending = true
        var activeVersion: Long? = null
    }
    private val keys = keys.toSet()
    private val slots = mutableMapOf<K, Slot<K, V>>()
    private var nextId = 0L
    init { require(this.keys.isNotEmpty()) }

    @Synchronized fun offer(key: K, value: V): Offer<K> {
        require(key in keys)
        slots[key]?.let {
            it.value = value
            it.version++
            it.pending = true
            return Offer(it.ticket, false)
        }
        val slot = Slot(Ticket(key, ++nextId), value)
        slots[key] = slot
        return Offer(slot.ticket, true)
    }

    @Synchronized fun take(key: K): Work<K, V>? {
        val slot = slots[key] ?: return null
        if (slot.activeVersion != null || !slot.pending) return null
        slot.activeVersion = slot.version
        slot.pending = false
        return Work(slot.ticket, slot.version, slot.value)
    }

    @Synchronized fun isCurrent(work: Work<K, V>): Boolean = slots[work.ticket.key]?.let {
        it.ticket == work.ticket && it.version == work.version
    } == true

    /** True only when this batch can release its resource, rather than its successor's. */
    @Synchronized fun finished(work: Work<K, V>): Boolean {
        val slot = slots[work.ticket.key] ?: return false
        if (slot.ticket != work.ticket || slot.activeVersion != work.version) return false
        slot.activeVersion = null
        if (slot.pending) return false
        slots.remove(work.ticket.key)
        return true
    }

    @Synchronized fun expire(ticket: Ticket<K>): Boolean {
        if (slots[ticket.key]?.ticket != ticket) return false
        slots.remove(ticket.key)
        return true
    }
}
