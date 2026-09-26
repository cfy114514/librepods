package me.kavishdevar.librepods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTransitionTest {
    private class TestScheduler {
        val pending = ArrayDeque<Pair<Runnable, Long>>()
        val executedDelays = mutableListOf<Long>()

        fun schedule(action: Runnable, delayMs: Long) {
            pending.addLast(action to delayMs)
        }

        fun unschedule(action: Runnable) {
            pending.removeAll { it.first === action }
        }

        fun runNext() {
            val (action, delayMs) = pending.removeFirst()
            executedDelays.add(delayMs)
            action.run()
        }

        fun drain() {
            var remaining = 100
            while (pending.isNotEmpty()) {
                check(remaining-- > 0) { "Volume transition did not stop" }
                runNext()
            }
        }
    }

    @Test
    fun rampsToTargetWithoutAnExtraWakeupAtTheEnd() {
        val scheduler = TestScheduler()
        val volumes = mutableListOf<Int>()
        val transition = VolumeTransition(volumes::add, scheduler::schedule, scheduler::unschedule)

        transition.start(0, 3)
        scheduler.drain()
        assertEquals(listOf(1, 2, 3), volumes)
        assertEquals(listOf(0L, 50L, 50L), scheduler.executedDelays)

        transition.start(3, 0)
        scheduler.drain()
        assertEquals(listOf(1, 2, 3, 2, 1, 0), volumes)
    }

    @Test
    fun rapidSpeakingEndCancelsOldRampEvenIfItsCallbackWasAlreadyDispatched() {
        val scheduler = TestScheduler()
        val volumes = mutableListOf<Int>()
        val transition = VolumeTransition(volumes::add, scheduler::schedule, scheduler::unschedule)

        transition.start(10, 2)
        scheduler.runNext()
        val oldCallback = scheduler.pending.first().first
        transition.start(9, 10)
        assertEquals(1, scheduler.pending.size)
        oldCallback.run()
        scheduler.drain()

        assertEquals(listOf(9, 10), volumes)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun unchangedVolumeCancelsPreviouslyQueuedRamp() {
        val scheduler = TestScheduler()
        val volumes = mutableListOf<Int>()
        val transition = VolumeTransition(volumes::add, scheduler::schedule, scheduler::unschedule)

        transition.start(10, 2)
        val oldCallback = scheduler.pending.first().first
        transition.start(10, 10)
        oldCallback.run()

        assertTrue(volumes.isEmpty())
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun releaseCancelsPendingWritesWithoutSchedulingAnotherRamp() {
        val scheduler = TestScheduler()
        val volumes = mutableListOf<Int>()
        val transition = VolumeTransition(volumes::add, scheduler::schedule, scheduler::unschedule)
        transition.start(10, 2)
        val dispatched = scheduler.pending.first().first
        transition.cancel()
        dispatched.run()
        assertTrue(scheduler.pending.isEmpty())
        assertTrue(volumes.isEmpty())
    }

    @Test
    fun repeatedChangesKeepOnlyOnePendingVolumeWrite() {
        val scheduler = TestScheduler()
        val volumes = mutableListOf<Int>()
        val transition = VolumeTransition(volumes::add, scheduler::schedule, scheduler::unschedule)

        repeat(1_000) { index ->
            transition.start(5, if (index % 2 == 0) 0 else 10)
            assertEquals(1, scheduler.pending.size)
        }
        scheduler.drain()
        assertEquals(listOf(6, 7, 8, 9, 10), volumes)
    }
}
