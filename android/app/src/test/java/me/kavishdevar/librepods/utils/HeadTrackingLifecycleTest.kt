package me.kavishdevar.librepods.utils

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import me.kavishdevar.librepods.bluetooth.HeadTrackingMotion
import me.kavishdevar.librepods.bluetooth.RtBuddyHeadTracking

class HeadTrackingLifecycleTest {
    @Test fun shortPacketsCannotAlterCalibrationOrCrash() {
        HeadTracking.reset()
        try {
            fun receive(packet: ByteArray) {
                RtBuddyHeadTracking.parse(packet)?.motion?.let(HeadTracking::processMotion)
            }
            val payload = ByteArray(58).apply {
                this[0] = 1
                this[9] = 3
                this[29] = 0x80.toByte() // horizontal = -32768
                this[30] = 0xff.toByte()
                this[31] = 0x7f // vertical = 32767
            }
            val packet = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, 68, 0,
                8, 1, 0x10, 1, 0x3a, 62, 8, 14, 0x1a, 58) + payload
            for (length in packet.indices) receive(packet.copyOf(length))
            repeat(9) { receive(packet) }
            assertEquals(Orientation(), HeadTracking.orientation.value)
            assertEquals(Acceleration(), HeadTracking.acceleration.value)
            receive(packet) // tenth valid sample calibrates without publishing motion
            assertEquals(Acceleration(), HeadTracking.acceleration.value)
            receive(packet)
            assertEquals(Acceleration(32767f, -32768f), HeadTracking.acceleration.value)
        } finally { HeadTracking.reset() }
    }

    @Test fun concurrentResetAndSamplesLeaveFreshCalibrationUsable() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val neutral = HeadTrackingMotion(15, 0, 0, 0, 0, 0)
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
