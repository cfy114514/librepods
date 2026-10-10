package me.kavishdevar.librepods.utils

/** Retains one broadcast batch; only its latest command can complete it. */
internal class CommandCompletionBatch {
    data class Ticket(val batch: Long, val revision: Long)
    data class Offer(val ticket: Ticket, val created: Boolean)
    private var nextBatch = 0L
    private var current: Ticket? = null

    @Synchronized fun offer(): Offer {
        val old = current
        val ticket = if (old == null) Ticket(++nextBatch, 1) else old.copy(revision = old.revision + 1)
        current = ticket
        return Offer(ticket, old == null)
    }
    @Synchronized fun isCurrent(ticket: Ticket): Boolean = current == ticket
    @Synchronized fun complete(ticket: Ticket): Boolean {
        if (current != ticket) return false
        current = null
        return true
    }
    @Synchronized fun expire(batch: Long): Boolean {
        if (current?.batch != batch) return false
        current = null
        return true
    }
}
