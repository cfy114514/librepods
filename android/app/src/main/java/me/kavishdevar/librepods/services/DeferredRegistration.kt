package me.kavishdevar.librepods.services

import java.io.Closeable

/** Closing while a platform registration is in flight still unregisters its result once. */
internal class DeferredRegistration(private val unregister: () -> Unit) : Closeable {
    private val lock = Any()
    @Volatile var isClosed = false
        private set
    private var registered = false
    private var unregistered = false

    fun didRegister(): Boolean {
        val cleanup = synchronized(lock) {
            check(!registered)
            registered = true
            if (isClosed && !unregistered) { unregistered = true; true } else false
        }
        if (cleanup) unregister()
        return !isClosed
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            isClosed = true
            if (registered && !unregistered) { unregistered = true; true } else false
        }
        if (cleanup) unregister()
    }
}
