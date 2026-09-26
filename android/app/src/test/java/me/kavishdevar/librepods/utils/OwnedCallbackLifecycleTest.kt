package me.kavishdevar.librepods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCallbackLifecycleTest {
    @Test
    fun repeatedInitializationDoesNotRegisterDuplicateSystemCallbacks() {
        val lifecycle = OwnedCallbackLifecycle()
        val owner = Any()
        var registrations = 0
        var unregistrations = 0
        repeat(100) {
            lifecycle.start(owner) {
                registrations++
                val cleanup: () -> Unit = { unregistrations++ }
                cleanup
            }
        }
        lifecycle.stop(owner)
        lifecycle.stop(owner)
        assertEquals(1, registrations)
        assertEquals(1, unregistrations)
    }

    @Test
    fun callbacksQueuedBeforeStopCannotActOnTheNextService() {
        val lifecycle = OwnedCallbackLifecycle()
        val oldOwner = Any()
        val newOwner = Any()
        val queued = mutableListOf<() -> Unit>()
        val received = mutableListOf<String>()
        lifecycle.start(oldOwner) { session ->
            queued.add { if (session.isActive) received.add("old") }
            val cleanup: () -> Unit = {}
            cleanup
        }
        lifecycle.start(newOwner) { session ->
            queued.add { if (session.isActive) received.add("new") }
            val cleanup: () -> Unit = {}
            cleanup
        }
        lifecycle.stop(oldOwner) // A delayed old service onDestroy must not unregister the new one.
        queued.forEach { it() }
        assertEquals(listOf("new"), received)
    }

    @Test
    fun replacingOwnerInvalidatesOldTokenBeforeUnregistering() {
        val lifecycle = OwnedCallbackLifecycle()
        val first = Any()
        var oldUnregistered = false
        lifecycle.start(first) { session ->
            val cleanup: () -> Unit = {
                assertFalse(session.isActive)
                oldUnregistered = true
            }
            cleanup
        }
        lifecycle.start(Any()) { session ->
            assertTrue(oldUnregistered)
            assertTrue(session.isActive)
            val cleanup: () -> Unit = {}
            cleanup
        }
    }

    @Test
    fun failedRegistrationInvalidatesCallbacksAndAllowsRetry() {
        val lifecycle = OwnedCallbackLifecycle()
        val owner = Any()
        var failedToken: OwnedCallbackLifecycle.Session? = null
        try {
            lifecycle.start(owner) { session ->
                failedToken = session
                error("System registration failed")
            }
        } catch (_: IllegalStateException) { }
        assertFalse(failedToken!!.isActive)
        var retried = false
        lifecycle.start(owner) {
            retried = true
            val cleanup: () -> Unit = {}
            cleanup
        }
        assertTrue(retried)
    }
}
