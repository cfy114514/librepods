package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class SampleDetectionScheduleTest {
    private class Timer {
        val actions = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        var cancels = 0
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            delays.add(delay); actions.add(action)
            return { cancels++ }
        }
    }
    @Test fun idleHasNoTimerAndBurstReadsLatestSampleOnceAtOriginalInterval() {
        val timer = Timer(); var latest = 0; val reads = mutableListOf<Int>()
        val work = SampleDetectionSchedule(50, timer::schedule) { reads.add(latest) }
        assertTrue(timer.actions.isEmpty())
        repeat(1000) { latest = it; work.offer() }
        assertEquals(listOf(50L), timer.delays); timer.actions[0]()
        assertEquals(listOf(999), reads); assertEquals(1, timer.actions.size)
        timer.actions[0](); assertEquals(listOf(999), reads)
        work.close(); assertEquals(0, timer.cancels)
    }
    @Test fun freshSampleSchedulesAgainButAlreadyDispatchedCallbackCannotReadItEarly() {
        val timer = Timer(); var reads = 0
        val work = SampleDetectionSchedule(50, timer::schedule) { reads++ }
        work.offer(); timer.actions[0](); assertEquals(1, reads)
        assertTrue(work.offer()); timer.actions[0](); assertEquals(1, reads)
        timer.actions[1](); assertEquals(2, reads); work.close()
    }
    @Test fun closeRejectsLateCallbackAndOldHandleCannotCancelReplacement() {
        val timer = Timer(); var oldReads = 0; var freshReads = 0
        val old = SampleDetectionSchedule(50, timer::schedule) { oldReads++ }; old.offer(); old.close()
        val fresh = SampleDetectionSchedule(50, timer::schedule) { freshReads++ }; fresh.offer()
        old.close(); assertFalse(old.offer()); timer.actions[0](); timer.actions[1]()
        assertEquals(0, oldReads); assertEquals(1, freshReads); assertEquals(1, timer.cancels); fresh.close()
    }
    @Test fun reentrantFreshSampleKeepsNextTimerAndCloseCancelsThatWork() {
        val timer = Timer(); var reads = 0
        lateinit var work: SampleDetectionSchedule
        work = SampleDetectionSchedule(50, timer::schedule) { reads++; work.offer() }
        work.offer(); timer.actions[0](); assertEquals(2, timer.actions.size)
        work.close(); timer.actions[1](); assertEquals(1, reads); assertEquals(1, timer.cancels)
    }
    @Test fun schedulerFailureDoesNotLeaveWorkPermanentlyPending() {
        val timer = Timer(); var failed = true; var reads = 0
        val work = SampleDetectionSchedule(50, { delay, action ->
            if (failed) error("Timer failed") else timer.schedule(delay, action)
        }) { reads++ }
        try { work.offer(); fail("Scheduling failure swallowed") } catch (_: IllegalStateException) { }
        failed = false; assertTrue(work.offer()); timer.actions[0](); assertEquals(1, reads); work.close()
    }
    @Test fun consumeFailureDoesNotRepeatWithoutNewDataAndNextSampleCanRetry() {
        val timer = Timer(); var reads = 0
        val work = SampleDetectionSchedule(50, timer::schedule) { if (++reads == 1) error("Read failed") }
        work.offer(); try { timer.actions[0](); fail("Read failure swallowed") } catch (_: IllegalStateException) { }
        timer.actions[0](); assertEquals(1, reads)
        work.offer(); timer.actions[1](); assertEquals(2, reads); work.close()
    }
}

