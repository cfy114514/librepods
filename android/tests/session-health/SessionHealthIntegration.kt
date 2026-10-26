package me.kavishdevar.librepods.integration

import android.bluetooth.BluetoothSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.HeadTrackingMotion
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq

internal typealias IntegrationCase = Pair<String, () -> Unit>
internal const val PEER_A = "12:34:56:78:9A:BC"
internal const val PEER_B = "12:34:56:78:9A:BD"
internal const val MINUTE = 60_000L

internal fun expect(condition: Boolean, description: String) {
    if (!condition) throw AssertionError(description)
}

internal fun <T> expectEquals(expected: T, actual: T, description: String) =
    expect(expected == actual, "$description: expected $expected, got $actual")

internal class Clock(var now: Long = 10 * MINUTE) {
    fun advance(millis: Long = MINUTE): Long { now += millis; return now }
}

internal class WireSocket(val wire: OutputStream = ByteArrayOutputStream()) : BluetoothSocket() {
    @Volatile var connected = true
    override val isConnected: Boolean get() = connected
    override val inputStream = ByteArrayInputStream(byteArrayOf())
    override val outputStream: OutputStream get() = wire
    override fun close() { connected = false }
}

/** Fixed protocol bytes; the test does not call a production packet builder. */
internal fun batteryFrame(
    left: Int = 80, right: Int = 70, case: Int = 60,
    leftStatus: Int = 2, rightStatus: Int = 2, caseStatus: Int = 2,
    order: List<Int> = listOf(4, 2, 8)
): ByteArray {
    val packet = byteArrayOf(4, 0, 4, 0, 4, 0, 3, 4, 1, 80, 2, 0, 2, 1, 70, 2, 0, 8, 1, 60, 2, 0)
    order.forEachIndexed { index, component ->
        val offset = 7 + index * 5
        packet[offset] = component.toByte()
        packet[offset + 2] = when (component) { 4 -> left; 2 -> right; else -> case }.toByte()
        packet[offset + 3] = when (component) { 4 -> leftStatus; 2 -> rightStatus; else -> caseStatus }.toByte()
    }
    return packet
}

/** Only routes real AACP callbacks. No packet parsing or session policy lives here. */
internal class BatteryCallback(private val battery: (ByteArray) -> Unit) : AACPManager.PacketCallback {
    override fun onBatteryInfoReceived(batteryInfo: ByteArray) = battery(batteryInfo)
    override fun onEarDetectionReceived(earDetection: ByteArray) = Unit
    override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) = Unit
    override fun onControlCommandReceived(controlCommand: ByteArray) = Unit
    override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) = Unit
    override fun onHeadTrackingReceived(motion: HeadTrackingMotion) = Unit
    override fun onUnknownPacketReceived(packet: ByteArray) = Unit
    override fun onProximityKeysReceived(proximityKeys: ByteArray) = Unit
    override fun onStemPressReceived(stemPress: ByteArray) = Unit
    override fun onAudioSourceReceived(audioSource: ByteArray) = Unit
    override fun onOwnershipChangeReceived(owns: Boolean) = Unit
    override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) = Unit
    override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) = Unit
    override fun onShowNearbyUI(sender: String) = Unit
    override fun onHeadphoneAccommodationReceived(eqData: FloatArray) = Unit
    override fun onCustomEqReceived(customEq: CustomEq) = Unit
    override fun onCapabilitiesReceived(capabilities: List<Capability>) = Unit
}

internal fun CountDownLatch.reached(description: String) =
    expect(await(5, TimeUnit.SECONDS), "Timed out: $description")

internal fun <T> withWorker(block: (ExecutorService) -> T): T {
    val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "session-health-integration").apply { isDaemon = true }
    }
    try { return block(worker) } finally {
        worker.shutdownNow()
        expect(worker.awaitTermination(5, TimeUnit.SECONDS), "Worker did not terminate")
    }
}

fun main(arguments: Array<String>) {
    val cases = batteryCases() + healthCases() + platformCases()
    expect(cases.map { it.first }.distinct().size == cases.size, "Case names must be unique")
    val selected = arguments.firstOrNull()
    val runnable = cases.filter { selected == null || it.first == selected }
    expect(runnable.isNotEmpty(), "Unknown case: $selected")
    var failures = 0
    for ((name, test) in runnable) {
        try {
            test()
            println("PASS $name")
        } catch (error: Throwable) {
            failures++
            println("FAIL $name: ${error.message}")
            error.printStackTrace(System.out)
        }
    }
    println("${runnable.size - failures}/${runnable.size} session-health integration cases passed")
    if (failures > 0) throw AssertionError("$failures integration cases failed")
}
