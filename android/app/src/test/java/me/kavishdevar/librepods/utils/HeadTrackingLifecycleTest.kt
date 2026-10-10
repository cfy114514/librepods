package me.kavishdevar.librepods.utils

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import me.kavishdevar.librepods.bluetooth.RtBuddyHeadTracking

class HeadTrackingLifecycleTest {
    @Test fun shortPacketsCannotAlterCalibrationOrCrash() {
        HeadTracking.reset()
        repeat(55) { HeadTracking.processPacket(ByteArray(it)) }
        assertEquals(Orientation(), HeadTracking.orientation.value)
        assertEquals(Acceleration(), HeadTracking.acceleration.value)
    }

    @Test fun concurrentResetAndSamplesLeaveFreshCalibrationUsable() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val neutral = RtBuddyHeadTracking.Motion(15, 0, 0, 0, 0, 0)
            val samples = executor.submit { repeat(10_000) { HeadTracking.processMotion(neutral) } }
            val resets = executor.submit { repeat(10_000) { HeadTracking.reset() } }
            samples.get(5, TimeUnit.SECONDS)
            resets.get(5, TimeUnit.SECONDS)
            HeadTracking.reset()
            repeat(10) { HeadTracking.processMotion(neutral) }
            HeadTracking.processMotion(neutral.copy(o2 = 32000))
            assertEquals(Orientation(90f, 90f), HeadTracking.orientation.value)
        } finally { executor.shutdownNow(); HeadTracking.reset() }
    }
}
