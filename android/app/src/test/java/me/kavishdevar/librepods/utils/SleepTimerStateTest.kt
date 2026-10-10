package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class SleepTimerStateTest {
    private val clock = TimerClock(1_000_000, 100_000, 4)
    private val record = SleepTimerRecord("task-1", "AA:BB:CC:DD:EE:FF", 3, clock.wall + 60_000, clock.elapsed + 60_000, 4)

    @Test fun clockChangesDoNotShortenOrExtendAnIntervalDuringTheSameBoot() {
        assertEquals(50_000L, record.remaining(TimerClock(clock.wall + 7_200_000, clock.elapsed + 10_000, 4)))
        assertEquals(50_000L, record.remaining(TimerClock(clock.wall - 7_200_000, clock.elapsed + 10_000, 4)))
    }
    @Test fun timeChangeRebasesPersistedWallDeadlineForALaterReboot() {
        val changed = TimerClock(clock.wall + 7_200_000, clock.elapsed + 10_000, 4)
        val rebased = record.rebase(changed)
        assertEquals(changed.wall + 50_000, rebased.endWall)
        val reboot = TimerClock(changed.wall + 20_000, 5_000, 5)
        assertEquals(30_000L, rebased.remaining(reboot))
        assertFalse(rebased.staleAfterReboot(reboot))
        assertEquals(35_000L, rebased.rebase(reboot).endElapsed)
    }
    @Test fun overdueOrAlreadyDeliveringTimersDoNotReplayAfterAReboot() {
        assertTrue(record.staleAfterReboot(TimerClock(clock.wall + 100_000, 5_000, 5)))
        val pending = record.beginDelivery(TimerClock(clock.wall + 60_000, clock.elapsed + 60_000, 4), 30_000)
        assertEquals(30_000L, pending.deliveryRemaining(TimerClock(clock.wall + 60_000, clock.elapsed + 60_000, 4)))
        assertEquals(0L, pending.deliveryRemaining(TimerClock(clock.wall + 61_000, 5_000, 5)))
        assertTrue(pending.staleAfterReboot(TimerClock(clock.wall + 61_000, 5_000, 5)))
    }
    @Test fun deliveryWindowUsesMonotonicTimeAndEndsWithoutAnIndefiniteReconnectAction() {
        val pending = record.beginDelivery(clock, 30_000)
        assertEquals(20_000L, pending.deliveryRemaining(TimerClock(clock.wall - 1_000_000, clock.elapsed + 10_000, 4)))
        assertEquals(0L, pending.deliveryRemaining(TimerClock(clock.wall, clock.elapsed + 30_000, 4)))
    }
    @Test fun identityAndModeMustBeValidAndMatchTheSelectedEarphones() {
        assertTrue(record.valid())
        assertTrue(record.matches("aa:bb:cc:dd:ee:ff"))
        assertFalse(record.matches("11:22:33:44:55:66"))
        assertFalse(record.copy(address = "").valid())
        assertFalse(record.copy(mode = 0).valid())
        assertFalse(record.copy(token = "invalid/path").valid())
    }
    @Test fun missingBootCounterFallsBackToWallTime() {
        val fallback = record.copy(boot = -1)
        assertEquals(40_000L, fallback.remaining(TimerClock(clock.wall + 20_000, 1, -1)))
    }
    @Test fun confirmedRebootDiscardsOverdueTimersWhenEitherBootCounterIsUnavailable() {
        for (savedBoot in listOf(-1, 4)) for (currentBoot in listOf(-1, 4)) {
            val overdue = record.copy(boot = savedBoot)
            val now = TimerClock(record.endWall + 1, 1, currentBoot)
            assertTrue(overdue.staleAfterReboot(now, confirmedReboot = true))
        }
    }
    @Test fun confirmedRebootDiscardsPendingDeliveryEvenWhenItsWallWindowIsStillOpen() {
        val pending = record.copy(boot = -1).beginDelivery(clock.copy(boot = -1), 30_000)
        val reboot = TimerClock(clock.wall + 1_000, 1, -1)
        assertEquals(29_000L, pending.deliveryRemaining(reboot))
        assertTrue(pending.staleAfterReboot(reboot, confirmedReboot = true))
    }
    @Test fun confirmedRebootPreservesFutureTimersUsingWallTimeEvenWithAnEqualBootCounter() {
        for (boot in listOf(-1, 4)) {
            val future = record.copy(boot = boot)
            val reboot = TimerClock(clock.wall + 20_000, 1_000, boot)
            assertFalse(future.staleAfterReboot(reboot, confirmedReboot = true))
            val restored = future.rebase(reboot, confirmedReboot = true)
            assertEquals(future.endWall, restored.endWall)
            assertEquals(41_000L, restored.endElapsed)
            assertEquals(boot, restored.boot)
        }
    }
    @Test fun NormalRestoreDoesNotTreatADueTimerAsRebootedWhenCounterIsUnavailable() {
        val due = record.copy(boot = -1)
        val now = TimerClock(record.endWall + 1, 1, -1)
        assertFalse(due.staleAfterReboot(now))
        assertEquals(0L, due.rebase(now).remaining(now))
    }
    @Test fun minuteBoundaryRefreshPreservesEveryDisplayedMinuteWithSixtyTicksPerHour() {
        val endAt = 3_600_000L
        var now = 0L
        var ticks = 0
        val seen = mutableListOf<Long>()
        while (now < endAt) {
            seen.add(SleepTimerManager.remainingMinutes(endAt, now))
            val pause = sleepTimerRefreshDelay(endAt, now)
            assertTrue(pause in 1..60_000)
            now += pause; ticks++
        }
        assertEquals(60, ticks)
        assertEquals((60L downTo 1L).toList(), seen)
        assertEquals(0L, sleepTimerRefreshDelay(endAt, now))
        assertEquals(500L, sleepTimerRefreshDelay(60_500, 0))
    }
}
