package me.kavishdevar.librepods.utils

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class LatestPublicationTest {
    private class Dispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { var left = 1000; while (queued.isNotEmpty()) { check(left-- > 0); queued.removeFirst().run() } }
    }
    @Test fun floodKeepsOnlyLatestValueAndUnchangedSuccessDoesNotRepublish() {
        val dispatcher = Dispatcher(); val calls = mutableListOf<Int>()
        val publisher = LatestPublication<Int>(dispatcher, { v, _ -> calls += v; true }, {})
        val owner = publisher.claim()
        repeat(100_000) { owner.offer(it) { true } }; dispatcher.drain()
        assertEquals(listOf(99_999), calls)
        owner.offer(99_999) { true }; dispatcher.drain(); assertEquals(1, calls.size)
        publisher.close(); dispatcher.drain()
    }
    @Test fun replacedOwnerCannotPublishClearOrCloseNewOwner() {
        val dispatcher = Dispatcher(); val calls = mutableListOf<Int>(); var clears = 0
        val publisher = LatestPublication<Int>(dispatcher, { v, _ -> calls += v; true }, { clears++ })
        val old = publisher.claim(); old.offer(1) { true }
        val current = publisher.claim(); current.offer(2) { true }; old.close()
        assertFalse(old.offer(3) { true }); assertFalse(old.clear())
        dispatcher.drain(); assertEquals(listOf(2), calls); assertEquals(0, clears)
        current.close(); dispatcher.drain(); assertEquals(1, clears)
        publisher.close(); dispatcher.drain()
    }
    @Test fun valueAlreadyInFlightIsFollowedByLatestReplacement() {
        val dispatcher = Dispatcher(); val calls = mutableListOf<Int>(); var oldCurrent = true
        lateinit var owner: LatestPublication.Lease<Int>
        val publisher = LatestPublication<Int>(dispatcher, { v, current ->
            calls += v
            if (v == 1) { repeat(500) { owner.offer(it + 2) { true } }; oldCurrent = current() }
            true
        }, {})
        owner = publisher.claim(); owner.offer(1) { true }; dispatcher.drain()
        assertEquals(listOf(1, 501), calls); assertFalse(oldCurrent)
        publisher.close(); dispatcher.drain()
    }
    @Test fun closingDuringPublicationSchedulesCleanupAfterItReturns() {
        val dispatcher = Dispatcher(); val events = mutableListOf<String>()
        lateinit var owner: LatestPublication.Lease<Int>
        val publisher = LatestPublication<Int>(dispatcher, { _, current -> events += "show"; owner.close(); assertFalse(current()); true }, { events += "clear" })
        owner = publisher.claim(); owner.offer(1) { true }; dispatcher.drain()
        assertEquals(listOf("show", "clear"), events)
        publisher.close(); dispatcher.drain()
    }
    @Test fun rejectionExceptionAndInvalidSourceDoNotPopulateSuccessCache() {
        val dispatcher = Dispatcher(); var calls = 0; var errors = 0
        val publisher = LatestPublication<Int>(dispatcher, { _, _ -> calls++; if (calls == 1) error("rejected"); calls > 2 }, {}, { errors++ })
        val owner = publisher.claim()
        owner.offer(1) { false }; dispatcher.drain(); assertEquals(0, calls)
        repeat(3) { owner.offer(1) { true }; dispatcher.drain() }
        assertEquals(3, calls); assertEquals(1, errors)
        owner.offer(1) { true }; dispatcher.drain(); assertEquals(3, calls)
        publisher.close(); dispatcher.drain()
    }
    @Test fun clearRetainsLeaseAndForcesSameValueToPublishAfterReconnect() {
        val dispatcher = Dispatcher(); var calls = 0; var clears = 0
        val publisher = LatestPublication<Int>(dispatcher, { _, _ -> calls++; true }, { clears++ })
        val owner = publisher.claim(); owner.offer(1) { true }; dispatcher.drain()
        repeat(500) { assertTrue(owner.clear()) }; dispatcher.drain(); assertEquals(1, clears)
        assertTrue(owner.offer(1) { true }); dispatcher.drain(); assertEquals(2, calls)
        publisher.close(); dispatcher.drain()
    }
    @Test fun sourceExpiringInsidePlatformWriteIsClearedWithoutTouchingNewerOwner() {
        val dispatcher = Dispatcher(); val events = mutableListOf<String>(); var valid = true
        val publisher = LatestPublication<Int>(dispatcher, { _, _ -> events += "show"; valid = false; false }, { events += "clear" })
        val owner = publisher.claim(); owner.offer(1) { valid }; dispatcher.drain()
        assertEquals(listOf("show", "clear"), events)
        publisher.close(); dispatcher.drain()
    }
}
