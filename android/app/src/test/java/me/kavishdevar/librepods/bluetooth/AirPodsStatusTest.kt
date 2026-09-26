package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsStatusTest {
    private val status = BLEManager.AirPodsStatus(address = "AA:BB:CC:DD:EE:FF", lastSeen = 1L)

    @Test fun repeatedAdvertisementsRefreshLivenessWithoutReportingAStateChange() {
        repeat(10_000) { index ->
            assertTrue(status.hasSameStateAs(status.copy(lastSeen = index.toLong() + 2)))
        }
    }

    @Test fun everyObservableStateChangeIsReported() {
        val changes = listOf(
            status.copy(address = "11:22:33:44:55:66"),
            status.copy(paired = true),
            status.copy(model = "AirPods Pro"),
            status.copy(leftBattery = 50),
            status.copy(rightBattery = 50),
            status.copy(caseBattery = 50),
            status.copy(isLeftInEar = true),
            status.copy(isRightInEar = true),
            status.copy(isLeftCharging = true),
            status.copy(isRightCharging = true),
            status.copy(isCaseCharging = true),
            status.copy(lidOpen = true),
            status.copy(color = "White"),
            status.copy(connectionState = "Music")
        )
        changes.forEach { changed ->
            assertFalse("State change was ignored: $changed", status.hasSameStateAs(changed))
            assertFalse(changed.hasSameStateAs(status))
        }
    }
}
