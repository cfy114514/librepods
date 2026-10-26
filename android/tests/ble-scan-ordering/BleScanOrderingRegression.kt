package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import java.util.Base64
import me.kavishdevar.librepods.utils.BluetoothCryptography

private val KEY = "9b7d390aa610103405adc857a33402ec".hexToByteArray()
private const val ADDRESS = "70:81:94:0D:FB:AA"
private const val OWNER = "AA:BB:CC:DD:EE:FF"

private fun rotatingAddress(key: ByteArray = KEY): String {
    val random = byteArrayOf(1, 2, 0x40)
    return (BluetoothCryptography.ah(key, random) + random).reversedArray()
        .joinToString(":") { "%02X".format(it) }
}

private class Fixture(enabled: Boolean = true) : AutoCloseable {
    val scanner = BluetoothLeScanner()
    val adapter = BluetoothAdapter(scanner).apply { isEnabled = enabled }
    val battery = mutableListOf<BLEManager.AirPodsStatus>()
    val changed = mutableListOf<BLEManager.AirPodsStatus>()
    val previousStates = mutableListOf<BLEManager.AirPodsStatus?>()
    val newAddresses = mutableListOf<String>()
    val lids = mutableListOf<Boolean>()
    var disappearances = 0
    val values = mutableMapOf("IRK" to Base64.getEncoder().encodeToString(KEY),
        "IRK_owner" to OWNER, "mac_address" to OWNER)
    private val preferences = object : SharedPreferences {
        override fun getString(key: String, default: String?): String? = values[key] ?: default
    }
    val manager = BLEManager(Context(preferences, BluetoothManager(adapter))).apply {
        setAirPodsStatusListener(object : BLEManager.AirPodsStatusListener {
            override fun onBatteryObserved(device: BLEManager.AirPodsStatus) { battery.add(device) }
            override fun onDeviceStatusChanged(device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?) {
                changed.add(device)
                previousStates.add(previousStatus)
            }
            override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) { newAddresses.add(device.address) }
            override fun onLidStateChanged(lidOpen: Boolean) { lids.add(lidOpen) }
            override fun onEarStateChanged(device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean) {}
            override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {}
            override fun onDeviceDisappeared() { disappearances++ }
        })
        startScanning()
    }
    fun single(address: String = ADDRESS, seen: Long, state: Int = 5, percent: Int = 80, lidOpen: Boolean = true) {
        scanner.callback!!.onScanResult(1, result(address, seen, state, percent, lidOpen))
    }
    override fun close() { manager.stopScanning() }
}

private fun result(address: String = ADDRESS, seen: Long, state: Int = 5, percent: Int = 80,
    lidOpen: Boolean = true, nanos: Long = seen * 1_000_000, valid: Boolean = true): ScanResult {
    val data = ByteArray(27)
    data[0] = 7; data[1] = if (valid) 25 else 24; data[2] = 1; data[3] = 0x30; data[4] = 0x20
    data[5] = 0x20; data[6] = ((percent / 10) * 17).toByte(); data[7] = 8
    data[8] = if (lidOpen) 0 else 8; data[10] = state.toByte()
    return ScanResult(BluetoothDevice(address), ScanRecord(data), nanos)
}

private fun olderSameAddressCannotChangeStatus() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    SystemClock.now = 3_000; f.single(seen = 1_000, state = 0, percent = 20, lidOpen = false)
    val status = checkNotNull(f.manager.getMostRecentStatus())
    check(status.connectionState == "Music" && status.leftBattery == 80 && status.lastSeen == 2_000L) { "Old result replaced current state: $status" }
    check(f.battery.size == 1 && f.changed.isEmpty()) { "Old result reached service listeners" }
    check(f.lids == listOf(true))
}

private fun newestRotatingAddressWinsTheWholeBatch() = Fixture().use { f ->
    val nextAddress = rotatingAddress()
    SystemClock.now = 1_000; f.single(seen = 1_000, state = 4, percent = 50, lidOpen = false)
    SystemClock.now = 5_000
    // One address rotation: B is new, while A is already known to the manager.
    f.scanner.callback!!.onBatchScanResults(listOf(
        result(ADDRESS, 3_000, state = 0, percent = 20, lidOpen = false),
        result(nextAddress, 4_000)))
    check(f.battery.map { it.leftBattery } == listOf(50, 80)) { "Older rotating address overwrote battery" }
    check(f.changed.map { it.connectionState } == listOf("Music")) { "The newest state was lost or an older disconnected event was replayed" }
    val status = checkNotNull(f.manager.getMostRecentStatus())
    check(status.address == nextAddress && status.connectionState == "Music" && status.lastSeen == 4_000L)
    check(f.lids == listOf(false, true))
}

private fun disconnectedTransitionSurvivesAddressRotation() = Fixture().use { f ->
    val nextAddress = rotatingAddress()
    SystemClock.now = 1_000; f.single(seen = 1_000, state = 4)
    SystemClock.now = 5_000
    f.scanner.callback!!.onBatchScanResults(listOf(
        result(ADDRESS, 3_000, state = 0), result(nextAddress, 4_000, state = 0)))
    SystemClock.now = 6_000; f.single(address = nextAddress, seen = 6_000, state = 0)
    check(f.changed.map { it.connectionState } == listOf("Disconnected")) {
        "Automatic takeover lost the current disconnected transition or received a duplicate"
    }
    check(f.changed.single().address == nextAddress)
    check(f.previousStates.single()?.connectionState == "Idle")
    check(f.newAddresses == listOf(ADDRESS, nextAddress))
}

private fun firstObservationAndUnchangedRotationDoNotEmitStateChanges() = Fixture().use { f ->
    val nextAddress = rotatingAddress()
    SystemClock.now = 1_000; f.single(seen = 1_000, state = 0)
    check(f.changed.isEmpty() && f.newAddresses == listOf(ADDRESS))
    SystemClock.now = 2_000; f.single(address = nextAddress, seen = 2_000, state = 0)
    SystemClock.now = 3_000; f.single(seen = 3_000, state = 0)
    check(f.changed.isEmpty()) { "An unchanged rotation invented a state transition" }
    check(f.newAddresses == listOf(ADDRESS, nextAddress))
}

private fun knownAddressTransitionsUseTheLatestIdentityState() = Fixture().use { f ->
    val nextAddress = rotatingAddress()
    SystemClock.now = 1_000; f.single(seen = 1_000, state = 4)
    SystemClock.now = 2_000; f.single(address = nextAddress, seen = 2_000, state = 0)
    // A's cached state is already Idle, but the identity's latest state was Disconnected.
    SystemClock.now = 3_000; f.single(seen = 3_000, state = 4)
    SystemClock.now = 4_000; f.single(seen = 4_000, state = 4)
    check(f.changed.map { it.connectionState } == listOf("Disconnected", "Idle"))
    check(f.previousStates.map { it?.connectionState } == listOf("Idle", "Disconnected"))
    check(f.newAddresses == listOf(ADDRESS, nextAddress))
}

private fun knownAddressDoesNotRepeatAnotherAddressStateChange() = Fixture().use { f ->
    val nextAddress = rotatingAddress()
    SystemClock.now = 1_000; f.single(seen = 1_000, state = 4)
    SystemClock.now = 2_000; f.single(address = nextAddress, seen = 2_000, state = 0)
    // A's old Idle cache must not manufacture a second disconnected transition.
    SystemClock.now = 3_000; f.single(seen = 3_000, state = 0)
    check(f.changed.map { it.connectionState } == listOf("Disconnected"))
    check(f.newAddresses == listOf(ADDRESS, nextAddress))
}

private fun staleFirstObservationCannotCreatePresence() = Fixture().use { f ->
    SystemClock.now = 20_000; f.single(seen = 1_000, state = 0, percent = 20)
    check(f.manager.getMostRecentStatus() == null && f.battery.isEmpty()) { "Expired observation created fresh presence" }
    f.single(seen = 19_000)
    check(f.manager.getMostRecentStatus()?.lastSeen == 19_000L)
}

private fun presenceExpiresFromObservationTime() = Fixture().use { f ->
    SystemClock.now = 7_000; f.single(seen = 2_000)
    check(f.manager.getMostRecentStatus()?.lastSeen == 2_000L) { "Delivery time became observation time" }
    // Accepted batches retain the existing arrival-based grace for lid notifications.
    check(f.lids == listOf(true))
    SystemClock.now = 17_000; check(f.manager.getMostRecentStatus() != null)
    SystemClock.now = 17_001; check(f.manager.getMostRecentStatus() == null)
}

private fun equalTimestampCannotRefreshPresence() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    SystemClock.now = 10_000; f.single(seen = 2_000, state = 0, percent = 20)
    check(f.battery.size == 1 && f.changed.isEmpty()) { "Duplicate timestamp republished state" }
    check(f.manager.getMostRecentStatus()?.lastSeen == 2_000L)
}

private fun futureResultCannotBlockValidResultInSameBatch() = Fixture().use { f ->
    SystemClock.now = 10_000
    f.scanner.callback!!.onBatchScanResults(listOf(
        result(seen = 10_000, nanos = 10_000_000_001, state = 0, percent = 20),
        result(seen = 9_000)))
    check(f.battery.size == 1 && f.battery.single().leftBattery == 80) { "Future result blocked the valid batch member" }
    check(f.manager.getMostRecentStatus()?.lastSeen == 9_000L)
}

private fun invalidOrUnverifiedResultsCannotAdvanceOrdering() = Fixture().use { f ->
    SystemClock.now = 10_000
    f.scanner.callback!!.onBatchScanResults(listOf(
        result(seen = 10_000, valid = false),
        result(address = "70:81:94:0D:FB:AB", seen = 9_500),
        result(seen = 9_000)))
    check(f.battery.size == 1 && f.manager.getMostRecentStatus()?.lastSeen == 9_000L)
}

private fun negativeTimestampCannotPublishOrBlockNextResult() = Fixture().use { f ->
    SystemClock.now = 1_000
    f.scanner.callback!!.onScanResult(1, result(seen = 0, nanos = -1))
    check(f.battery.isEmpty()) { "Negative timestamp published" }
    f.single(seen = 900)
    check(f.manager.getMostRecentStatus()?.lastSeen == 900L)
}

private fun restartPreservesOrderingAndRejectsOldCallback() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    val oldCallback = checkNotNull(f.scanner.callback)
    f.manager.stopScanning()
    f.manager.startScanning()
    SystemClock.now = 4_000
    oldCallback.onScanResult(1, result(seen = 4_000, state = 0, percent = 20))
    f.single(seen = 1_000, state = 0, percent = 20)
    check(f.battery.size == 1) { "Restart allowed an old callback or observation" }
    SystemClock.now = 5_000; f.single(seen = 5_000, percent = 70)
    check(f.battery.size == 2 && f.manager.getMostRecentStatus()?.leftBattery == 70)
}

private fun repeatedStartKeepsOneScanAndOneLidEvent() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    val callback = checkNotNull(f.scanner.callback)
    f.manager.startScanning()
    f.manager.startScanning()
    check(f.scanner.starts == 1 && f.scanner.stops == 0 && f.scanner.callback === callback) {
        "Repeated start replaced the active scanner"
    }
    SystemClock.now = 3_000; f.single(seen = 3_000)
    check(f.lids == listOf(true)) { "Repeated start invented a lid-open transition" }
}

private fun disabledStartupCanStartAfterAdapterEnabled() = Fixture(enabled = false).use { f ->
    check(f.scanner.starts == 0 && f.scanner.callback == null)
    f.adapter.isEnabled = true
    f.manager.startScanning()
    check(f.scanner.starts == 1 && f.scanner.callback != null)
    SystemClock.now = 2_000; f.single(seen = 2_000)
    check(f.lids == listOf(true)) { "First enabled scan did not emit lid-open" }
}

private fun stoppedScanClearsPresenceAndRearmsLid() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    val callback = checkNotNull(f.scanner.callback)
    f.manager.stopScanning()
    check(f.manager.getMostRecentStatus() == null)
    SystemClock.now = 3_000
    callback.onScanResult(1, result(seen = 3_000))
    check(f.battery.size == 1)
    f.manager.startScanning()
    check(f.manager.getMostRecentStatus() == null) { "Restart republished the previous scan's cached status" }
    SystemClock.now = 4_000; f.single(seen = 4_000)
    check(f.lids == listOf(true, true)) { "New scan kept the previous scan's open-lid latch" }
    check(f.changed.isEmpty()) { "New scan compared against retired status" }
}

private fun unavailableScannerDoesNotLatchAnActiveStart() = Fixture(enabled = false).use { f ->
    f.adapter.isEnabled = true
    f.adapter.bluetoothLeScanner = null
    f.manager.startScanning()
    check(f.manager.getMostRecentStatus() == null)
    f.adapter.bluetoothLeScanner = f.scanner
    f.manager.startScanning()
    check(f.scanner.starts == 1 && f.scanner.callback != null) { "Missing scanner blocked the next explicit start" }
    SystemClock.now = 2_000; f.single(seen = 2_000)
    check(f.lids == listOf(true))
}

private fun deniedStartDoesNotRegisterScan() = Fixture(enabled = false).use { f ->
    f.adapter.isEnabled = true
    f.manager.startScanning { false }
    check(f.scanner.starts == 0 && f.scanner.callback == null && Handler.pendingCount == 0) {
        "Retired scan request registered work"
    }
}

private fun invalidationDuringStartRetiresRegistration() = Fixture(enabled = false).use { f ->
    var current = true
    f.adapter.isEnabled = true
    f.scanner.onStart = { current = false }
    f.manager.startScanning { current }
    check(f.scanner.starts == 1 && f.scanner.stops == 1 && f.scanner.callback == null && Handler.pendingCount == 0) {
        "In-flight start outlived its owner"
    }
    check(f.manager.getMostRecentStatus() == null)
}

private fun invalidationRejectsCallbacksBeforeQueuedStop() = Fixture(enabled = false).use { f ->
    var current = true
    f.adapter.isEnabled = true
    f.manager.startScanning { current }
    SystemClock.now = 2_000; f.single(seen = 2_000)
    val callback = checkNotNull(f.scanner.callback)
    current = false
    SystemClock.now = 3_000
    callback.onScanResult(1, result(seen = 3_000, lidOpen = false))
    callback.onBatchScanResults(listOf(result(seen = 3_000, state = 0, percent = 20)))
    check(f.battery.size == 1 && f.lids == listOf(true) && f.changed.isEmpty()) {
        "Invalidated scan callback reached listeners before queued stop"
    }
}

private fun synchronousFailureCannotRestartMaintenance() = Fixture(enabled = false).use { f ->
    f.adapter.isEnabled = true
    f.scanner.onStart = { checkNotNull(f.scanner.callback).onScanFailed(2) }
    f.manager.startScanning()
    check(f.scanner.starts == 1 && Handler.pendingCount == 0) {
        "Synchronous scan failure restarted maintenance"
    }
    SystemClock.now = 2_000
    f.scanner.callback?.onScanResult(1, result(seen = 2_000))
    check(f.battery.isEmpty() && f.manager.getMostRecentStatus() == null)
    f.scanner.onStart = null
    f.manager.startScanning()
    check(f.scanner.starts == 2 && Handler.pendingCount == 1)
    f.single(seen = 2_000)
    check(f.lids == listOf(true))
}

private fun thrownStartRetiresPartialRegistration() = Fixture(enabled = false).use { f ->
    f.adapter.isEnabled = true
    f.scanner.onStart = { error("Scanner startup failed after registration") }
    f.manager.startScanning()
    check(f.scanner.starts == 1 && f.scanner.stops == 1 && f.scanner.callback == null && Handler.pendingCount == 0) {
        "Startup exception left a registered scan"
    }
    f.scanner.onStart = null
    f.manager.startScanning()
    SystemClock.now = 2_000; f.single(seen = 2_000)
    check(f.lids == listOf(true))
}

private fun timeoutCleanupDoesNotRestoreDelayedPresence() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    SystemClock.now = 20_000; Handler.runPending()
    check(f.disappearances == 1 && f.manager.getMostRecentStatus() == null)
    SystemClock.now = 21_000; f.single(seen = 3_000, state = 0, percent = 20)
    check(f.battery.size == 1 && f.manager.getMostRecentStatus() == null) { "Delayed batch revived expired presence" }
    f.single(seen = 20_500)
    check(f.manager.getMostRecentStatus()?.lastSeen == 20_500L)
}

private fun changingIrkResetsOrderingForTheNewIdentity() = Fixture().use { f ->
    SystemClock.now = 10_000; f.single(seen = 10_000)
    val nextKey = ByteArray(16)
    f.values["IRK"] = Base64.getEncoder().encodeToString(nextKey)
    f.values["IRK_owner"] = "AA:BB:CC:DD:EE:01"
    f.values["mac_address"] = "AA:BB:CC:DD:EE:01"
    SystemClock.now = 11_000; f.single(address = rotatingAddress(nextKey), seen = 9_000, percent = 70)
    val status = checkNotNull(f.manager.getMostRecentStatus())
    check(status.ownerAddress == "AA:BB:CC:DD:EE:01" && status.leftBattery == 70 && status.lastSeen == 9_000L)
}

private fun newerUnchangedStateRefreshesOnlyObservationTime() = Fixture().use { f ->
    SystemClock.now = 2_000; f.single(seen = 2_000)
    SystemClock.now = 5_000; f.single(seen = 3_000)
    check(f.manager.getMostRecentStatus()?.lastSeen == 3_000L)
    check(f.battery.size == 2 && f.changed.isEmpty() && f.lids == listOf(true))
}

private fun newestNanosecondWinsAcrossAddressesInTheSameMillisecond() {
    val nextAddress = rotatingAddress()
    // Exercise both insertion orders; map traversal order is not observation order.
    for ((older, newer) in listOf(ADDRESS to nextAddress, nextAddress to ADDRESS)) Fixture().use { f ->
        SystemClock.now = 2_001
        f.scanner.callback!!.onScanResult(1, result(address = older, seen = 2_000,
            nanos = 2_000_000_001, state = 0, percent = 20))
        f.scanner.callback!!.onScanResult(1, result(address = newer, seen = 2_000,
            nanos = 2_000_000_002, state = 5, percent = 80))
        val status = checkNotNull(f.manager.getMostRecentStatus())
        check(status.address == newer && status.connectionState == "Music" && status.leftBattery == 80) {
            "Millisecond tie selected old address ${status.address} instead of $newer"
        }
    }
}

fun main() {
    check(BluetoothCryptography.verifyRPA(ADDRESS, KEY) && BluetoothCryptography.verifyRPA(rotatingAddress(), KEY))
    val cases = listOf(
        "older same-address result cannot replace state" to ::olderSameAddressCannotChangeStatus,
        "newest rotating address wins the whole batch" to ::newestRotatingAddressWinsTheWholeBatch,
        "disconnected transition survives address rotation" to ::disconnectedTransitionSurvivesAddressRotation,
        "first observation and unchanged rotation do not emit state changes" to ::firstObservationAndUnchangedRotationDoNotEmitStateChanges,
        "known address transitions use the latest identity state" to ::knownAddressTransitionsUseTheLatestIdentityState,
        "known address does not repeat another address state change" to ::knownAddressDoesNotRepeatAnotherAddressStateChange,
        "stale first observation cannot create presence" to ::staleFirstObservationCannotCreatePresence,
        "presence expires from observation time" to ::presenceExpiresFromObservationTime,
        "equal timestamp cannot refresh presence" to ::equalTimestampCannotRefreshPresence,
        "future result does not block valid same-address batch member" to ::futureResultCannotBlockValidResultInSameBatch,
        "invalid or unverified results do not advance ordering" to ::invalidOrUnverifiedResultsCannotAdvanceOrdering,
        "negative timestamp cannot publish" to ::negativeTimestampCannotPublishOrBlockNextResult,
        "restart preserves ordering and rejects old callback" to ::restartPreservesOrderingAndRejectsOldCallback,
        "repeated start keeps one scan and one lid event" to ::repeatedStartKeepsOneScanAndOneLidEvent,
        "disabled startup starts after adapter enabled" to ::disabledStartupCanStartAfterAdapterEnabled,
        "stopped scan clears presence and rearms lid" to ::stoppedScanClearsPresenceAndRearmsLid,
        "unavailable scanner does not latch active start" to ::unavailableScannerDoesNotLatchAnActiveStart,
        "denied start does not register scan" to ::deniedStartDoesNotRegisterScan,
        "invalidation during start retires registration" to ::invalidationDuringStartRetiresRegistration,
        "invalidation rejects callbacks before queued stop" to ::invalidationRejectsCallbacksBeforeQueuedStop,
        "synchronous failure cannot restart maintenance" to ::synchronousFailureCannotRestartMaintenance,
        "thrown start retires partial registration" to ::thrownStartRetiresPartialRegistration,
        "timeout cleanup does not revive delayed presence" to ::timeoutCleanupDoesNotRestoreDelayedPresence,
        "new IRK resets ordering" to ::changingIrkResetsOrderingForTheNewIdentity,
        "unchanged newer state refreshes observation time" to ::newerUnchangedStateRefreshesOnlyObservationTime,
        "newest nanosecond wins across addresses in one millisecond" to ::newestNanosecondWinsAcrossAddressesInTheSameMillisecond
    )
    var failures = 0
    for ((name, test) in cases) {
        try { test(); println("PASS: $name") }
        catch (failure: Throwable) { failures++; println("FAIL: $name: ${failure.message}"); failure.printStackTrace(System.out) }
    }
    println("BLE scan ordering regression: ${cases.size - failures}/${cases.size} passed")
    check(failures == 0) { "$failures BLE scan ordering regression(s) failed" }
}
