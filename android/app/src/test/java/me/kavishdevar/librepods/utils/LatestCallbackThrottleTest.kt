package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class LatestCallbackThrottleTest {
    private class Clock {
        var now = 0L
        val pending = linkedMapOf<() -> Unit, Long>()
        var peakPending = 0
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            pending[action] = now + delay
            peakPending = maxOf(peakPending, pending.size)
            return { pending.remove(action); Unit }
        }
        fun advance(to: Long) {
            while (true) {
                val next = pending.minByOrNull { it.value } ?: break
                if (next.value > to) break
                now = next.value
                pending.remove(next.key)
                next.key()
            }
            now = to
        }
    }

    @Test fun continuousCallbacksHaveBoundedWorkAndDoNotStarveTheLastState() {
        val clock = Clock()
        val observed = mutableListOf<Pair<Long, Int>>()
        val throttle = LatestCallbackThrottle(300, { clock.now }, clock::schedule) {
            value: Int -> observed.add(clock.now to value)
        }
        repeat(100_000) { value -> throttle.offer(value); clock.advance(clock.now + 1) }
        clock.advance(clock.now + 300)
        assertEquals(0, observed.first().second)
        assertEquals(99_999, observed.last().second)
        assertTrue(observed.size <= 335)
        assertTrue(observed.zipWithNext().all { (left, right) -> right.first - left.first >= 300 })
        assertEquals(1, clock.peakPending)
        assertTrue(clock.pending.isEmpty())
    }

    @Test fun newCallbacksCannotPushBackTheExistingDeadline() {
        val clock = Clock()
        val observed = mutableListOf<Int>()
        val throttle = LatestCallbackThrottle(300, { clock.now }, clock::schedule, observed::add)
        throttle.offer(1)
        clock.advance(100); throttle.offer(2)
        clock.advance(200); throttle.offer(3)
        assertEquals(listOf(300L), clock.pending.values.toList())
        clock.advance(300)
        assertEquals(listOf(1, 3), observed)
    }

    @Test fun closeInvalidatesAlreadyDispatchedCallbacksAndDoesNotCancelANewOwner() {
        val clock = Clock()
        val observed = mutableListOf<String>()
        val old = LatestCallbackThrottle(300, { clock.now }, clock::schedule, observed::add)
        old.offer("first"); old.offer("old pending")
        val delayed = clock.pending.keys.single()
        old.close()
        val fresh = LatestCallbackThrottle(300, { clock.now }, clock::schedule, observed::add)
        fresh.offer("new"); fresh.offer("new pending")
        delayed()
        old.close()
        assertFalse(old.offer("closed"))
        clock.advance(300)
        assertEquals(listOf("first", "new", "new pending"), observed)
    }

    @Test fun anEarlyWakeupReschedulesOnceUntilTheIntervalHasPassed() {
        val clock = Clock()
        val observed = mutableListOf<Int>()
        val throttle = LatestCallbackThrottle(300, { clock.now }, clock::schedule, observed::add)
        throttle.offer(1); throttle.offer(2)
        val early = clock.pending.keys.single()
        clock.pending.remove(early)
        clock.now = 100
        early()
        assertEquals(listOf(1), observed)
        assertEquals(listOf(300L), clock.pending.values.toList())
        clock.advance(300)
        assertEquals(listOf(1, 2), observed)
    }

    @Test fun reentrantOfferIsKeptAsTheNextState() {
        val clock = Clock()
        val observed = mutableListOf<Int>()
        lateinit var throttle: LatestCallbackThrottle<Int>
        throttle = LatestCallbackThrottle(300, { clock.now }, clock::schedule) {
            observed.add(it)
            if (it == 1) throttle.offer(2)
        }
        throttle.offer(1)
        clock.advance(300)
        assertEquals(listOf(1, 2), observed)
    }
}
