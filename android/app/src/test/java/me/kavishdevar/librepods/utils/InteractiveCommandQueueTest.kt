package me.kavishdevar.librepods.utils

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class InteractiveCommandQueueTest {
    private class Dispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { var limit = 1000; while (queued.isNotEmpty()) { check(limit-- > 0); queued.removeFirst().run() } }
    }
    @Test fun floodRetainsOnlyLatestPerKeyAndCompletesEverySupersededRequestOnce() {
        val dispatcher = Dispatcher(); val written = mutableListOf<Int>(); val completions = IntArray(10000)
        val queue = InteractiveCommandQueue<Int, Int>(setOf(1, 2), dispatcher) { value, current ->
            assertTrue(current()); written.add(value)
        }
        repeat(10000) { index -> queue.offer(index % 2 + 1, index, onComplete = { completions[index]++ }) }
        assertEquals(1, dispatcher.queued.size)
        assertEquals(9998, completions.sum())
        dispatcher.drain(); assertEquals(listOf(9998, 9999), written)
        queue.close(); dispatcher.drain(); assertTrue(completions.all { it == 1 })
    }
    @Test fun newerCommandInvalidatesTheActiveGuardButPreservesOtherKey() {
        val dispatcher = Dispatcher(); val written = mutableListOf<Int>(); var completed = 0
        lateinit var queue: InteractiveCommandQueue<Int, Int>
        queue = InteractiveCommandQueue(setOf(1, 2), dispatcher) { value, current ->
            if (value == 0) {
                queue.offer(2, 8, onComplete = { completed++ })
                queue.offer(1, 9, onComplete = { completed++ })
                assertFalse(current())
            } else if (current()) written.add(value)
        }
        queue.offer(1, 0, onComplete = { completed++ }); dispatcher.drain()
        assertEquals(listOf(8, 9), written); assertEquals(3, completed)
        queue.close(); dispatcher.drain()
    }
    @Test fun expiredConnectionGuardDropsTheWriteAndStillCompletes() {
        val dispatcher = Dispatcher(); var session = 1; var completed = 0
        val queue = InteractiveCommandQueue<Int, Int>(setOf(1), dispatcher) { _, _ -> fail("Expired session wrote") }
        queue.offer(1, 1, valid = { session == 1 }, onComplete = { completed++ })
        session = 2; dispatcher.drain(); assertEquals(1, completed)
        queue.close(); dispatcher.drain()
    }
    @Test fun closeReleasesPendingAndRejectsLateCallsExactlyOnce() {
        val dispatcher = Dispatcher(); var completed = 0
        val queue = InteractiveCommandQueue<Int, Int>(setOf(1), dispatcher) { _, _ -> fail("Closed queue wrote") }
        queue.offer(1, 1, onComplete = { completed++ }); queue.close(); queue.close()
        assertFalse(queue.offer(1, 2, onComplete = { completed++ }))
        dispatcher.drain(); assertEquals(2, completed)
    }
    @Test fun failureCompletesAndDoesNotKillTheNextKey() {
        val dispatcher = Dispatcher(); var completed = 0; val errors = mutableListOf<Exception>(); val written = mutableListOf<Int>()
        val queue = InteractiveCommandQueue<Int, Int>(setOf(1, 2), dispatcher, onError = errors::add) { value, _ ->
            if (value == 1) error("write failed") else written.add(value)
        }
        queue.offer(1, 1, onComplete = { completed++ }); queue.offer(2, 2, onComplete = { completed++ })
        dispatcher.drain(); assertEquals(2, completed); assertEquals(1, errors.size); assertEquals(listOf(2), written)
        queue.close(); dispatcher.drain()
    }
    @Test fun throwingCompletionDoesNotDropSuccessorAndReentrantCompletionIsSafe() {
        val dispatcher = Dispatcher(); val written = mutableListOf<Int>(); val errors = mutableListOf<Exception>()
        val queue = InteractiveCommandQueue<Int, Int>(setOf(1), dispatcher, onError = errors::add) { value, _ -> written.add(value) }
        queue.offer(1, 1, onComplete = { queue.offer(1, 3); error("completion failed") })
        queue.offer(1, 2)
        dispatcher.drain(); assertEquals(listOf(3), written); assertEquals(1, errors.size)
        queue.close(); dispatcher.drain()
    }
    @Test fun unknownKeysAreRejectedWithoutGrowingTheQueue() {
        val dispatcher = Dispatcher(); val queue = InteractiveCommandQueue<Int, Int>(setOf(1), dispatcher) { _, _ -> }
        assertThrows(IllegalArgumentException::class.java) { queue.offer(2, 1) }
        queue.close(); dispatcher.drain()
    }
}
