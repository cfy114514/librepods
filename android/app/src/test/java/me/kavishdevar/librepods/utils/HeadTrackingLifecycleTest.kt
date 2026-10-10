package me.kavishdevar.librepods.utils

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

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
            val samples = executor.submit { repeat(10_000) { HeadTracking.processPacket(ByteArray(70)) } }
            val resets = executor.submit { repeat(10_000) { HeadTracking.reset() } }
            samples.get(5, TimeUnit.SECONDS)
            resets.get(5, TimeUnit.SECONDS)
            HeadTracking.reset()
            repeat(10) { HeadTracking.processPacket(ByteArray(70)) }
            val sample = ByteArray(70)
            sample[45] = 0x00
            sample[46] = 0x7D // o2 = 32000
            HeadTracking.processPacket(sample)
            assertEquals(Orientation(90f, 90f), HeadTracking.orientation.value)
        } finally { executor.shutdownNow(); HeadTracking.reset() }
    }
}
