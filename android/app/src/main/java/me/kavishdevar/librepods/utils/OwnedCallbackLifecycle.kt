package me.kavishdevar.librepods.utils

/** One registration owner at a time, with tokens that invalidate callbacks already in a queue. */
internal class OwnedCallbackLifecycle {
    class Session internal constructor(internal val owner: Any) {
        @Volatile var isActive: Boolean = true
            internal set
        internal var cleanup: (() -> Unit)? = null
    }

    private var current: Session? = null

    @Synchronized
    fun start(owner: Any, register: (Session) -> (() -> Unit)) {
        if (current?.owner === owner) return
        closeCurrent()
        val session = Session(owner)
        current = session
        try {
            session.cleanup = register(session)
        } catch (error: Throwable) {
            session.isActive = false
            current = null
            throw error
        }
    }

    @Synchronized
    fun stop(owner: Any) {
        if (current?.owner === owner) closeCurrent()
    }

    private fun closeCurrent() {
        val session = current ?: return
        current = null
        session.isActive = false
        session.cleanup?.invoke()
    }
}
