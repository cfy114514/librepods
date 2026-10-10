package me.kavishdevar.librepods.utils

import java.io.Closeable

/** One current callback; closing a retired registration cannot remove its replacement. */
internal class OwnedCallbackSlot<T : Any> {
    class Registration<T : Any> internal constructor(
        private val slot: OwnedCallbackSlot<T>, internal val callback: T
    ) : Closeable {
        @Volatile private var active = true
        internal fun retire() { active = false }
        fun dispatch(action: (T) -> Unit) { if (active) action(callback) }
        override fun close() { slot.remove(this) }
    }

    private var current: Registration<T>? = null
    @Synchronized fun get(): T? = current?.callback
    @Synchronized fun register(callback: T): Registration<T> {
        current?.retire()
        return Registration(this, callback).also { current = it }
    }
    @Synchronized fun clear() { current?.retire(); current = null }
    @Synchronized private fun remove(registration: Registration<T>) {
        registration.retire()
        if (current === registration) current = null
    }
    fun dispatch(action: (T) -> Unit) {
        val registration = synchronized(this) { current }
        registration?.dispatch(action)
    }
}
