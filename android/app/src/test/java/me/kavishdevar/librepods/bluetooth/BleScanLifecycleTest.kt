package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanLifecycleTest {
    @Test fun duplicateAddressesAreIgnoredOnlyWithinOneBatch() {
        val deduplicator = BleAdvertisementDeduplicator()
        repeat(100) {
            deduplicator.inBatch {
                assertFalse(deduplicator.wasProcessed("AirPods"))
                deduplicator.markProcessed("AirPods")
                assertTrue(deduplicator.wasProcessed("AirPods"))
                assertFalse(deduplicator.wasProcessed("Other AirPods"))
            }
            assertFalse(deduplicator.wasProcessed("AirPods"))
        }
    }

    @Test fun individualAdvertisementsNeverLeaveAnAddressBlocked() {
        val deduplicator = BleAdvertisementDeduplicator()
        repeat(10_000) {
            assertFalse(deduplicator.wasProcessed("AirPods"))
            deduplicator.markProcessed("AirPods")
        }
    }

    @Test fun aFailedBatchReleasesItsAddresses() {
        val deduplicator = BleAdvertisementDeduplicator()
        try {
            deduplicator.inBatch {
                deduplicator.markProcessed("AirPods")
                throw IllegalStateException("Scan processing failed")
            }
        } catch (_: IllegalStateException) {
            // Simulate the scanner's failure boundary.
        }
        assertFalse(deduplicator.wasProcessed("AirPods"))
    }

    @Test fun restartingScanningDoesNotMultiplyMaintenanceLoops() {
        val scheduled = mutableListOf<Runnable>()
        var runs = 0
        val task = BleMaintenanceTask(
            schedule = { scheduled.add(it) },
            cancel = { callback -> scheduled.removeAll { it === callback } },
            maintain = { runs++ }
        )
        repeat(100) { task.start() }
        assertEquals(1, scheduled.size)
        repeat(100) {
            scheduled.removeAt(0).run()
            assertEquals(1, scheduled.size)
        }
        assertEquals(100, runs)
        val dispatchedBeforeStop = scheduled.first()
        task.stop()
        assertTrue(scheduled.isEmpty())
        dispatchedBeforeStop.run()
        assertTrue(scheduled.isEmpty())
        assertEquals(100, runs)
    }

    @Test fun callbackDispatchedBeforeRestartCannotCreateASecondLoop() {
        val scheduled = mutableListOf<Runnable>()
        var runs = 0
        val task = BleMaintenanceTask(
            schedule = { scheduled.add(it) },
            cancel = { callback -> scheduled.removeAll { it === callback } },
            maintain = { runs++ }
        )
        task.start()
        val oldDispatchedCallback = scheduled.removeAt(0)
        task.start()
        oldDispatchedCallback.run()
        assertEquals(0, runs)
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).run()
        assertEquals(1, runs)
        assertEquals(1, scheduled.size)
    }

    @Test fun aListenerRestartDuringMaintenanceStillLeavesOneCallback() {
        val scheduled = mutableListOf<Runnable>()
        lateinit var task: BleMaintenanceTask
        task = BleMaintenanceTask(
            schedule = { scheduled.add(it) },
            cancel = { callback -> scheduled.removeAll { it === callback } },
            maintain = { task.start() }
        )
        task.start()
        scheduled.removeAt(0).run()
        assertEquals(1, scheduled.size)
    }

    @Test fun aListenerStopDuringMaintenancePreventsRescheduling() {
        val scheduled = mutableListOf<Runnable>()
        lateinit var task: BleMaintenanceTask
        task = BleMaintenanceTask(
            schedule = { scheduled.add(it) },
            cancel = { callback -> scheduled.removeAll { it === callback } },
            maintain = { task.stop() }
        )
        task.start()
        scheduled.removeAt(0).run()
        assertTrue(scheduled.isEmpty())
    }
}
