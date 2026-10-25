package me.kavishdevar.librepods.presentation

import java.io.Closeable
import me.kavishdevar.librepods.data.ConnectionHealthReport
import me.kavishdevar.librepods.data.ConnectionHealthSnapshot
import me.kavishdevar.librepods.data.HealthPlatformEvidence
import me.kavishdevar.librepods.data.connectionHealthIdentity
import me.kavishdevar.librepods.data.connectionHealthReport

/** One observer belongs to one UI selection and service binding; late reads cannot publish. */
internal class ConnectionHealthObserver(selectedPeer: String?) : Closeable {
    class ReadTicket internal constructor(internal val owner: Any, internal val generation: Long, internal val sequence: Long)
    private val owner = Any()
    private val peer = connectionHealthIdentity(selectedPeer)
    private var generation = 0L
    private var sequence = 0L
    private var lastPublished = 0L
    private var active = false
    private var closed = false
    private var snapshot: ConnectionHealthSnapshot? = null
    private var platform: HealthPlatformEvidence? = null

    @Synchronized fun start(): Boolean {
        if (closed) return false
        if (!active) { active = true; generation++ }
        return true
    }

    @Synchronized fun beginRead(): ReadTicket? =
        if (active && !closed) ReadTicket(owner, generation, ++sequence) else null

    @Synchronized fun publish(ticket: ReadTicket, snapshot: ConnectionHealthSnapshot?,
                              platform: HealthPlatformEvidence?, now: Long): ConnectionHealthReport? {
        if (closed || !active || ticket.owner !== owner || ticket.generation != generation || ticket.sequence <= lastPublished) return null
        lastPublished = ticket.sequence
        this.snapshot = snapshot
        this.platform = platform
        return connectionHealthReport(peer, snapshot, platform, now)
    }

    /** Re-evaluate age independently of a possibly slow platform read. This never creates evidence. */
    @Synchronized fun currentReport(now: Long): ConnectionHealthReport? =
        if (closed) null else connectionHealthReport(peer, snapshot, platform, now)

    @Synchronized fun stop() {
        active = false
        generation++
        snapshot = null
        platform = null
    }

    @Synchronized override fun close() {
        closed = true
        stop()
    }
}
