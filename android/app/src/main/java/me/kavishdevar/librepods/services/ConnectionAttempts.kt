package me.kavishdevar.librepods.services

import java.util.concurrent.CancellationException

/** Admission happens before dispatch so disconnect can retire queued and blocking connects. */
internal class ConnectionAttempts(private val owner: CloseableResourceScope) {
    class Attempt internal constructor(val resources: CloseableResourceScope, internal val canConnect: () -> Boolean)
    private var active: Attempt? = null

    val inProgress: Boolean @Synchronized get() = active != null

    /** The source predicate must be quick and side-effect free; it also guards publication. */
    @Synchronized
    fun begin(canConnect: () -> Boolean = { true }): Attempt? {
        if (owner.isClosed || active != null || !canConnect()) return null
        val resources = try { owner.track(owner.createChildScope()) }
            catch (_: CancellationException) { return null }
        return Attempt(resources, canConnect).also { active = it }
    }

    @Synchronized
    fun isCurrent(attempt: Attempt): Boolean = active === attempt && !owner.isClosed && attempt.canConnect()

    /** Only short state publication belongs here; socket I/O must stay outside this lock. */
    @Synchronized
    fun <T> whileCurrent(attempt: Attempt, publish: () -> T): T {
        if (!isCurrent(attempt)) throw CancellationException("Connection attempt was retired")
        return attempt.resources.whileOpen(publish)
    }

    @Synchronized
    fun finish(attempt: Attempt) {
        if (active === attempt) active = null
    }

    fun cancel() {
        val retired = synchronized(this) { active.also { active = null } }
        // The scope revokes publication immediately and dispatches blocking close on IO.
        retired?.resources?.let {
            it.close()
            owner.release(it)
        }
    }
}
