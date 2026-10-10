/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.iterator

/**
 * Manager for Bluetooth Low Energy scanning operations specifically for AirPods
 */
class BLEManager(private val context: Context) {

    data class AirPodsStatus(
        val address: String,
        val ownerAddress: String? = null,
        val lastSeen: Long = SystemClock.elapsedRealtime(),
        val paired: Boolean = false,
        val model: String = "Unknown",
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val isLeftInEar: Boolean = false,
        val isRightInEar: Boolean = false,
        val isLeftCharging: Boolean = false,
        val isRightCharging: Boolean = false,
        val isCaseCharging: Boolean = false,
        val lidOpen: Boolean = false,
        val color: String = "Unknown",
        val connectionState: String = "Unknown"
    ) {
        internal fun hasSameBatteryAs(other: AirPodsStatus): Boolean =
            ownerAddress == other.ownerAddress && leftBattery == other.leftBattery &&
                rightBattery == other.rightBattery && caseBattery == other.caseBattery &&
                isLeftCharging == other.isLeftCharging && isRightCharging == other.isRightCharging &&
                isCaseCharging == other.isCaseCharging
        /** Reception time keeps the device alive, but is not a device state change. */
        internal fun hasSameStateAs(other: AirPodsStatus): Boolean =
            address == other.address &&
                ownerAddress == other.ownerAddress &&
                paired == other.paired &&
                model == other.model &&
                leftBattery == other.leftBattery &&
                rightBattery == other.rightBattery &&
                caseBattery == other.caseBattery &&
                isLeftInEar == other.isLeftInEar &&
                isRightInEar == other.isRightInEar &&
                isLeftCharging == other.isLeftCharging &&
                isRightCharging == other.isRightCharging &&
                isCaseCharging == other.isCaseCharging &&
                lidOpen == other.lidOpen &&
                color == other.color &&
                connectionState == other.connectionState
    }

    fun getMostRecentStatus(): AirPodsStatus? {
        if (mScanCallback == null) return null
        if (statusIrk != sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)) return null
        val selected = sharedPreferences.getString("mac_address", "") ?: ""
        return deviceStatusMap.values.maxByOrNull { it.lastSeen }?.takeIf {
            SystemClock.elapsedRealtime() - it.lastSeen <= STALE_DEVICE_TIMEOUT_MS &&
                (it.ownerAddress == null || it.ownerAddress.equals(selected, ignoreCase = true))
        }
    }

    interface AirPodsStatusListener {
        fun onBatteryObserved(device: AirPodsStatus) {}
        fun onDeviceStatusChanged(device: AirPodsStatus, previousStatus: AirPodsStatus?)
        fun onBroadcastFromNewAddress(device: AirPodsStatus)
        fun onLidStateChanged(lidOpen: Boolean)
        fun onEarStateChanged(device: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)
        fun onBatteryChanged(device: AirPodsStatus)
        fun onDeviceDisappeared()
    }

    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    @Volatile private var mScanCallback: ScanCallback? = null
    @Volatile private var airPodsStatusListener: AirPodsStatusListener? = null
    private val deviceStatusMap = ConcurrentHashMap<String, AirPodsStatus>()
    @Volatile private var statusIrk: String? = null
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val rpaVerifier = CachedRpaVerifier(onInvalidKey = { Log.e(TAG, "Invalid IRK", it) })
    private val proximityDecryptor = CachedProximityDecryptor(onError = {
        Log.e(TAG, "Unable to decrypt proximity data", it)
    })
    private var currentGlobalLidState: Boolean? = null
    private var lastBroadcastTime: Long = 0
    private val advertisementDeduplicator = BleAdvertisementDeduplicator()

    val colorNames = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow"
    )

    val connStates = mapOf(
        0x00 to "Disconnected", 0x04 to "Idle", 0x05 to "Music",
        0x06 to "Call", 0x07 to "Ringing", 0x09 to "Hanging Up", 0xFF to "Unknown"
    )

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupTask = BleMaintenanceTask(
        schedule = { cleanupHandler.postDelayed(it, CLEANUP_INTERVAL_MS) },
        cancel = { cleanupHandler.removeCallbacks(it) },
        maintain = {
            cleanupStaleDevices()
            checkLidStateTimeout()
        }
    )

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        stopScanning()
        try {
            Log.d(TAG, "Starting BLE scanner")

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null) {
                Log.d(TAG, "No Bluetooth adapter available")
                return
            }

            if (!btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth is disabled")
                return
            }

            mBluetoothLeScanner = btAdapter.bluetoothLeScanner

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(500L)
                .build()

            val manufacturerData = ByteArray(27)
            val manufacturerDataMask = ByteArray(27)

            manufacturerData[0] = 7
            manufacturerData[1] = 25

            manufacturerDataMask[0] = -1
            manufacturerDataMask[1] = -1

            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(76, manufacturerData, manufacturerDataMask)
                .build()

            mScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (mScanCallback !== this) return
                    processScanResult(result)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    if (mScanCallback !== this) return
                    advertisementDeduplicator.inBatch {
                        // Keep the newest state for each address in this batch.
                        for (result in results.sortedByDescending { it.timestampNanos }) {
                            if (mScanCallback !== this) break
                            processScanResult(result)
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (mScanCallback !== this) return
                    mScanCallback = null
                    cleanupTask.stop()
                    Log.e(TAG, "BLE scan failed with error code: $errorCode")
                }
            }

            mBluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, mScanCallback)
            Log.d(TAG, "BLE scanner started successfully")

            cleanupTask.start()
        } catch (t: Throwable) {
            mScanCallback = null
            mBluetoothLeScanner = null
            cleanupTask.stop()
            Log.e(TAG, "Error starting BLE scanner", t)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        cleanupTask.stop()
        val callback = mScanCallback
        val scanner = mBluetoothLeScanner
        // Invalidate even if stopScan fails after permission/adapter changes.
        mScanCallback = null
        mBluetoothLeScanner = null
        try {
            if (scanner != null && callback != null) {
                Log.d(TAG, "Stopping BLE scanner")
                scanner.stopScan(callback)
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scanner", t)
        }
    }

    private fun formatBattery(byteVal: Int): Pair<Boolean, Int> {
        val charging = (byteVal and 0x80) != 0
        val level = byteVal and 0x7F
        return Pair(charging, level)
    }

    private fun processScanResult(result: ScanResult) {
        try {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address

            if (advertisementDeduplicator.wasProcessed(address)) {
                return
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(76) ?: return
            if (!AirPodsProximityModels.isValid(manufacturerData)) return

            val irkBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)
            val irkOwner = sharedPreferences.getString("IRK_owner", null)
            if (irkOwner != null && !irkOwner.equals(sharedPreferences.getString("mac_address", ""), ignoreCase = true)) return
            if (statusIrk != irkBase64) {
                deviceStatusMap.clear()
                currentGlobalLidState = null
                statusIrk = irkBase64
            }
            if (!rpaVerifier.verify(address, irkBase64)) return

            advertisementDeduplicator.markProcessed(address)
            lastBroadcastTime = SystemClock.elapsedRealtime()

            val storedEncryptionKey = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.ENC_KEY.name, null)
            val encryptionOwner = sharedPreferences.getString("ENC_KEY_owner", null)
            val encryptionKey = if (irkOwner != null && encryptionOwner != null && !irkOwner.equals(encryptionOwner, ignoreCase = true))
                null else storedEncryptionKey
            val decryptedData = proximityDecryptor.decryptLastBlock(manufacturerData, encryptionKey)
            val parsedStatus = (if (decryptedData != null && decryptedData.size == 16) {
                parseProximityMessageWithDecryption(address, manufacturerData, decryptedData)
            } else {
                parseProximityMessage(address, manufacturerData)
            }).copy(ownerAddress = irkOwner)
            if (irkBase64 != sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)) return

            val previousStatus = deviceStatusMap[address]
            deviceStatusMap[address] = parsedStatus

            airPodsStatusListener?.let { listener ->
                listener.onBatteryObserved(parsedStatus)
                if (previousStatus == null) {
                    listener.onBroadcastFromNewAddress(parsedStatus)
                    Log.d(TAG, "New AirPods device detected: $address")

                    if (currentGlobalLidState == null || currentGlobalLidState != parsedStatus.lidOpen) {
                        currentGlobalLidState = parsedStatus.lidOpen
                        listener.onLidStateChanged(parsedStatus.lidOpen)
                        Log.d(TAG, "Lid state ${if (parsedStatus.lidOpen) "opened" else "closed"} (detected from new device)")
                    }
                } else {
                    if (!parsedStatus.hasSameStateAs(previousStatus)) {
                        listener.onDeviceStatusChanged(parsedStatus, previousStatus)
                    }

                    if (parsedStatus.lidOpen != previousStatus.lidOpen) {
                        val previousGlobalState = currentGlobalLidState
                        currentGlobalLidState = parsedStatus.lidOpen

                        if (previousGlobalState != parsedStatus.lidOpen) {
                            listener.onLidStateChanged(parsedStatus.lidOpen)
                            Log.d(TAG, "Lid state changed from $previousGlobalState to ${parsedStatus.lidOpen}")
                        }
                    }

                    if (parsedStatus.isLeftInEar != previousStatus.isLeftInEar ||
                        parsedStatus.isRightInEar != previousStatus.isRightInEar) {
                        listener.onEarStateChanged(
                            parsedStatus,
                            parsedStatus.isLeftInEar,
                            parsedStatus.isRightInEar
                        )
                        Log.d(TAG, "Ear state changed - Left: ${parsedStatus.isLeftInEar}, Right: ${parsedStatus.isRightInEar}")
                    }

                    if (parsedStatus.leftBattery != previousStatus.leftBattery ||
                        parsedStatus.rightBattery != previousStatus.rightBattery ||
                        parsedStatus.caseBattery != previousStatus.caseBattery ||
                        parsedStatus.isLeftCharging != previousStatus.isLeftCharging ||
                        parsedStatus.isRightCharging != previousStatus.isRightCharging ||
                        parsedStatus.isCaseCharging != previousStatus.isCaseCharging) {
                        listener.onBatteryChanged(parsedStatus)
                        Log.d(TAG, "Battery changed - Left: ${parsedStatus.leftBattery}, Right: ${parsedStatus.rightBattery}, Case: ${parsedStatus.caseBattery}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }

    private fun parseProximityMessageWithDecryption(address: String, data: ByteArray, decrypted: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val model = AirPodsProximityModels.getModelName(data)

        val status = data[5].toInt() and 0xFF
//        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt() and 0xFF] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftByteIndex = if (isFlipped) 2 else 1
        val rightByteIndex = if (isFlipped) 1 else 2

        val (isLeftCharging, rawLeftBattery) = formatBattery(decrypted[leftByteIndex].toInt() and 0xFF)
        val (isRightCharging, rawRightBattery) = formatBattery(decrypted[rightByteIndex].toInt() and 0xFF)
        val leftBattery = rawLeftBattery.takeIf { it in 0..100 }
        val rightBattery = rawRightBattery.takeIf { it in 0..100 }

        val rawCaseBatteryByte = decrypted[3].toInt() and 0xFF
        val (isCaseCharging, rawCaseBattery) = formatBattery(rawCaseBatteryByte)

        // Unavailable case reports must not refresh the age of an old percentage.
        val caseBattery = rawCaseBattery.takeIf { it in 0..100 }

        val lidOpen = ((lid shr 3) and 0x01) == 0

        return AirPodsStatus(
            address = address,
            lastSeen = SystemClock.elapsedRealtime(),
            paired = paired,
            model = model,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    private fun cleanupStaleDevices() {
        val now = SystemClock.elapsedRealtime()
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS
        val hadDevices = deviceStatusMap.isNotEmpty()

        val staleDevices = deviceStatusMap.filter { it.value.lastSeen < staleCutoff }

        for (device in staleDevices) {
            if (!deviceStatusMap.remove(device.key, device.value)) continue
            Log.d(TAG, "Removed stale device from tracking: ${device.key}")
        }

        if (hadDevices && deviceStatusMap.isEmpty()) {
            airPodsStatusListener?.onDeviceDisappeared()
        }
    }

    private fun checkLidStateTimeout() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastBroadcastTime > LID_CLOSE_TIMEOUT_MS && currentGlobalLidState == true) {
            Log.d(TAG, "No broadcasts for ${LID_CLOSE_TIMEOUT_MS}ms, forcing lid state to closed")
            currentGlobalLidState = false
            airPodsStatusListener?.onLidStateChanged(false)
        }
    }

    private fun parseProximityMessage(address: String, data: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val model = AirPodsProximityModels.getModelName(data)

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt() and 0xFF] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBattery = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        val lidOpen = ((lid shr 3) and 0x01) == 0

        fun decodeBattery(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }

        return AirPodsStatus(
            address = address,
            lastSeen = SystemClock.elapsedRealtime(),
            paired = paired,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBattery),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val CLEANUP_INTERVAL_MS = 10000L
        private const val STALE_DEVICE_TIMEOUT_MS = 15000L
        private const val LID_CLOSE_TIMEOUT_MS = 2500L
    }
}
