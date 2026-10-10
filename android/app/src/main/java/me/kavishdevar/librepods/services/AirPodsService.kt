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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

//import me.kavishdevar.librepods.utils.CrossDevice
//import me.kavishdevar.librepods.utils.CrossDevicePackets
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.createBluetoothSocket
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.BatteryHistoryStore
import me.kavishdevar.librepods.data.canRecordBleBattery
import me.kavishdevar.librepods.data.isAvailableReading
import me.kavishdevar.librepods.data.systemHeadsetBatteryLevel
import me.kavishdevar.librepods.data.appleHeadsetBatteryIndicator
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.AirPodsBase
import me.kavishdevar.librepods.data.isHeadTrackingData
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.presentation.widgets.PhoneBatteryUpdateGate
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.CameraRemoteCommand
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.ConflatedCallbackSession
import me.kavishdevar.librepods.utils.CoalescedWorkSession
import me.kavishdevar.librepods.utils.KeyedWorkSession
import me.kavishdevar.librepods.utils.InteractiveCommandQueue
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.SleepTimerManager
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.kavishdevar.librepods.bluetooth.shouldConnectAtt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AirPodsService"

object ServiceManager {
    private var service: AirPodsService? = null
    private var version = 0L
    internal data class State(val service: AirPodsService?, val version: Long)
    @Synchronized internal fun captureState() = State(service, version)
    @Synchronized internal fun isCurrentIdle(expected: Long) = service == null && version == expected

    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @Synchronized
    fun setService(service: AirPodsService?) {
        if (this.service !== service) version++
        this.service = service
    }
}

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    @Volatile var macAddress = ""
    @Volatile var localMac = ""
    lateinit var aacpManager: AACPManager
    lateinit var attManager: ATTManagerv2
    @Volatile private var cachedInformation: me.kavishdevar.librepods.data.CachedAirPodsInformation? = null
    private val informationKeys = setOf("mac_address", "name", "airpods_model_address", "airpods_model_number",
        "airpods_serial_number", "airpods_left_serial_number", "airpods_right_serial_number",
        "airpods_version1", "airpods_version2", "airpods_version3")
    val airpodsInstance: AirPodsInstance?
        get() = cachedInformation?.takeIf {
            ::sharedPreferences.isInitialized && it.owner == me.kavishdevar.librepods.data.batteryHistoryIdentity(
                sharedPreferences.getString("mac_address", "") ?: "")
        }?.instance

    @Synchronized
    private fun refreshCachedInformation(notify: Boolean = false) {
        val updated = me.kavishdevar.librepods.data.cachedAirPodsInformation(sharedPreferences.all)
        val changed = cachedInformation != updated
        cachedInformation = updated
        if (notify && changed) sendBroadcast(Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(packageName))
    }
    @Volatile var cameraActive = cameraOpen
    private val cameraGeneration = AtomicLong()
    private val cameraCaptureInFlight = AtomicBoolean(false)
    @Volatile private var disconnectedBecauseReversed = false
    @Volatile private var otherDeviceTookOver = false

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        @Volatile var bleOnlyMode: Boolean = false,

        // AirPods state-based takeover
        @Volatile var takeoverWhenDisconnected: Boolean = true,
        @Volatile var takeoverWhenIdle: Boolean = true,
        @Volatile var takeoverWhenMusic: Boolean = false,
        @Volatile var takeoverWhenCall: Boolean = true,

        // Phone state-based takeover
        @Volatile var takeoverWhenRingingCall: Boolean = true,
        @Volatile var takeoverWhenMediaStart: Boolean = true,

        var leftSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,

        var leftDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,

        var leftTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,

        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,

        var cameraAction: StemPressType? = null,

        // AirPods device information
        var airpodsName: String = "",
        var airpodsModelNumber: String = "",
        var airpodsManufacturer: String = "",
        var airpodsSerialNumber: String = "",
        var airpodsLeftSerialNumber: String = "",
        var airpodsRightSerialNumber: String = "",
        var airpodsVersion1: String = "",
        var airpodsVersion2: String = "",
        var airpodsVersion3: String = "",
        var airpodsHardwareRevision: String = "",
        var airpodsUpdaterIdentifier: String = "",

        // phone's mac, needed for tipi
        var selfMacAddress: String = ""
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var packetLogHistory: PacketLogHistory
    val packetLogsFlow: StateFlow<Set<String>> get() = packetLogHistory.flow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: TelephonyCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceResources = CloseableResourceScope(closeResources = ::closeResourcesOnIo)
    private val ownedControlSocket = java.util.concurrent.atomic.AtomicReference<BluetoothSocket?>()
    private val ownedAttSocket = java.util.concurrent.atomic.AtomicReference<BluetoothSocket?>()
    private enum class AudioProfileAction { DISCOVER, SDP, ATTACH, PAUSE, POLICY }
    private data class AudioProfileRequest(val generation: Long, val action: (BluetoothProfile) -> Unit)
    private val profileOwnerToken = Any()
    private class PendingAudioProfileRecipient(
        service: AirPodsService, val ownerToken: Any, val ticket: PendingProfileRequests.Ticket<Int>
    ) {
        val owner = java.lang.ref.WeakReference(service)
        fun isCurrent(): Boolean = owner.get()?.let {
            !it.serviceResources.isClosed && ServiceManager.getService() === it && it.profileRequests.contains(ticket)
        } == true
        fun reject() { owner.get()?.profileRequests?.rejected(ticket) }
        fun sameRequest(other: PendingAudioProfileRecipient) = ownerToken === other.ownerToken && ticket == other.ticket
    }
    private val profileRequests = serviceResources.track(PendingProfileRequests<Int, AudioProfileAction, AudioProfileRequest>(
        setOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET), AudioProfileAction.entries.toSet()))
    private val batteryWidgetUpdates = serviceResources.track(CoalescedWorkSession(
        Dispatchers.IO, onError = { Log.w(TAG, "Battery widget refresh failed", it) },
        work = ::publishBatteryWidgets
    ))
    private val sleepTimerDeliveries = serviceResources.track(CoalescedWorkSession(
        Dispatchers.IO, windowMillis = 0,
        onError = { Log.w(TAG, "Sleep timer delivery failed", it) },
        work = ::deliverSleepTimer
    ))
    private val noiseWidgetUpdates = serviceResources.track(CoalescedWorkSession(
        Dispatchers.IO, onError = { Log.w(TAG, "Noise widget refresh failed", it) }, work = ::publishNoiseControlWidget
    ))
    private enum class MetadataKind { INFORMATION, BATTERY }
    private data class MetadataScope(val peer: String, val selection: Long, val deviceSession: Long)
    private data class MetadataRequest(val kind: MetadataKind, val target: BluetoothDevice,
        val scope: MetadataScope, val values: Map<Int, String>, val modelNumber: String? = null,
        val batterySnapshot: List<Battery>? = null)
    private val metadataSelection = AtomicLong()
    private data class InteractiveCommand(val packet: ByteArray, val socket: BluetoothSocket, val deviceSession: Long)
    private val interactiveCommandKeys = setOf(
        AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
        AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
        AACPManager.Companion.ControlCommandIdentifiers.STEM_CONFIG.value)
    private val interactiveCommands = serviceResources.track(InteractiveCommandQueue<Byte, InteractiveCommand>(
        interactiveCommandKeys, Dispatchers.IO, onError = { Log.w(TAG, "Interactive command failed", it) }
    ) { request, current ->
        aacpManager.sendPacket(request.packet, request.socket) {
            current() && ServiceManager.getService() === this@AirPodsService &&
                aacpManager.isCurrentDeviceSession(request.deviceSession) && isCurrentControlSocket(request.socket)
        }
    })
    private val metadataBindingLock = Any()
    @Volatile private var desiredInformationMetadata: MetadataRequest? = null
    @Volatile private var desiredBatteryMetadata: MetadataRequest? = null
    // Only the serial metadata worker reads or mutates this cache.
    private var metadataWriter: MetadataValueWriter<MetadataScope>? = null
    private val metadataUpdates = serviceResources.track(KeyedWorkSession<MetadataKind, MetadataRequest>(
        MetadataKind.entries.toSet(), Dispatchers.IO,
        onError = { Log.w(TAG, "Metadata update failed", it) }, consume = ::publishMetadata))
    private val localMacLookupFinished = CountDownLatch(1)
    private lateinit var inMemoryLogs: PacketLogBuffer
    private val packetLogFormatter = PacketLogFormatter()
    private var lastBatterySnapshot: Pair<String?, List<Battery>>? = null
    private val audioChargingState = AudioChargingState()
    private data class ConversationUpdate(
        val generation: Long, val level: Int? = null, val peer: String? = null,
        val socket: BluetoothSocket? = null, val resumeMedia: Boolean = false,
        val speaking: Boolean? = null
    )
    private val conversationQueueLock = Any()
    private val conversationGeneration = AtomicLong(0)
    private val conversationState = ConversationAwarenessState()
    private val incomingConversationState = ConversationAwarenessState()
    private var handledConversationGeneration = -1L
    private val conversationUpdates = serviceResources.track(
        ConflatedCallbackSession<ConversationUpdate>(Dispatchers.Main, ::handleConversationUpdate)
    )
    private val audioProfileOwnership = AudioProfileOwnership(setOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET))
    private data class MediaInformationRequest(
        val socket: BluetoothSocket, val selfMac: String, val streaming: Boolean,
        val ticket: AudioProfileOwnership.MediaTicket, val deviceSession: Long
    )
    private val mediaInformationUpdates = serviceResources.track(KeyedWorkSession<Unit, MediaInformationRequest>(
        setOf(Unit), Dispatchers.IO,
        onError = { Log.w(TAG, "Media information update failed", it) }, consume = ::publishMediaInformation
    ))
    private val headTrackingSession = serviceResources.track(HeadTrackingSession())
    private data class HeadTrackingRequest(
        val ticket: HeadTrackingSession.Ticket, val socket: BluetoothSocket, val deviceSession: Long,
        val attempt: Long, val preferredAlternate: Boolean, val alternate: Boolean = preferredAlternate,
        val retryOf: Long? = null, val motionService: Int? = null
    )
    @Volatile private var appliedHeadTrackingRequest: HeadTrackingRequest? = null
    private val headTrackingBindingLock = Any()
    private val headTrackingAttempts = AtomicLong()
    private val headTrackingStartup = HeadTrackingStartup<HeadTrackingRequest>()
    val headTrackingStatus = headTrackingStartup.status
    private var headTrackingStartupJob: Job? = null
    private val headTrackingCommands = serviceResources.track(KeyedWorkSession<Unit, HeadTrackingRequest>(
        setOf(Unit), Dispatchers.IO, onError = { Log.w(TAG, "Head tracking command failed", it) },
        consume = ::writeHeadTrackingRequest))
    private data class RenameRequest(
        val peer: String, val name: String, val socket: BluetoothSocket, val deviceSession: Long,
        val target: BluetoothDevice?
    )
    private val renameBindingLock = Any()
    @Volatile private var desiredRename: RenameRequest? = null
    @Volatile private var appliedRename: RenameRequest? = null
    private val renameCommands = serviceResources.track(KeyedWorkSession<Unit, RenameRequest>(
        setOf(Unit), Dispatchers.IO, onError = { Log.w(TAG, "Rename command failed", it) }, consume = ::writeRenameRequest))
    private enum class TakeoverAction { MUSIC, CALL, REVERSE, MANUAL, HEAD_TRACKING }
    private data class TakeoverRequest(
        val action: TakeoverAction, val reason: String, val peer: String, val generation: Long, val deviceSession: Long?,
        val socket: BluetoothSocket?, val selfMac: String?, val manual: Boolean, val headTracking: Boolean,
        val headGeneration: Long? = null
    ) {
        val explicit: Boolean get() = action == TakeoverAction.REVERSE || manual || headTracking
    }
    private val takeoverGeneration = AtomicLong()
    private val takeoverRequests = serviceResources.track(KeyedWorkSession<TakeoverAction, TakeoverRequest>(
        TakeoverAction.entries.toSet(), Dispatchers.IO,
        onError = { Log.w(TAG, "Audio takeover failed", it) }, consume = ::performTakeover
    ))
    private class A2dpWatch(
        val receiver: BroadcastReceiver, val ticket: AudioProfileOwnership.Ticket,
        val deviceSession: Long, val registration: DeferredRegistration
    ) : java.io.Closeable {
        val timeout = java.util.concurrent.atomic.AtomicReference<Job?>()
        fun arm(job: Job) {
            timeout.getAndSet(job)?.cancel()
            if (registration.isClosed) timeout.getAndSet(null)?.cancel()
        }
        override fun close() {
            registration.close()
            timeout.getAndSet(null)?.cancel()
        }
    }
    private val a2dpWatch = java.util.concurrent.atomic.AtomicReference<A2dpWatch?>()
    private val profileActionCurrent = ThreadLocal<() -> Boolean>()
    private lateinit var batteryHistory: BatteryHistoryStore
    private var lastBleBatteryStatus: BLEManager.AirPodsStatus? = null
    private val packetSourceSocket = ThreadLocal<BluetoothSocket>()

    private val notificationGeneration = AtomicLong()
    @Volatile private var notificationLease: me.kavishdevar.librepods.utils.LatestPublication.Lease<ConnectionNotice>? = null
    private val notificationRefreshes = serviceResources.track(CoalescedWorkSession(
        Dispatchers.IO, onError = { Log.w(TAG, "Connection notification refresh failed", it) },
        work = ::refreshConnectionNotification))

    @Volatile private var handleIncomingCallOnceConnected = false
    private var showIslandReceiver: BroadcastReceiver? = null
    private var phoneBatteryReceiverRegistered = false
    private var ownershipResetJob: Job? = null

    lateinit var bleManager: BLEManager

    companion object {
        private val pendingAudioProfiles = PendingAcquisitions<Int, PendingAudioProfileRecipient>(
            setOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET))
        private val profileAcquisitionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun beginAudioProfileAcquisition(context: Context, adapter: BluetoothAdapter, listener: WeakAudioProfileListener) {
            val ticket = listener.acquisition ?: return
            profileAcquisitionScope.launch {
                val admission = pendingAudioProfiles.start(ticket) { it.isCurrent() }
                if (!admission.ready) { admission.rejected?.reject(); return@launch }
                try {
                    if (!adapter.getProfileProxy(context, listener, ticket.key)) {
                        pendingAudioProfiles.rejected(ticket)?.reject()
                        Log.w(TAG, "Audio profile acquisition was rejected")
                    }
                } catch (error: Exception) {
                    pendingAudioProfiles.rejected(ticket)?.reject()
                    Log.w(TAG, "Could not obtain audio profile", error)
                }
            }
        }
        private fun completeAudioProfileAcquisition(context: Context, adapter: BluetoothAdapter, ticket: PendingAcquisitions.Ticket<Int>) {
            pendingAudioProfiles.finished(ticket)?.let {
                beginAudioProfileAcquisition(context, adapter, WeakAudioProfileListener(context, adapter, it.ticket.key, it.ticket, true))
            }
        }
        init {
            System.loadLibrary("bluetooth_socket")
        }
    }

    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {
        override fun onBatteryObserved(device: BLEManager.AirPodsStatus) {
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            recordBleBatteryHistory(device)
            val previous = lastBleBatteryStatus
            lastBleBatteryStatus = device
            if (lastBatterySnapshot != null && previous != null && device.hasSameBatteryAs(previous)) return
            batteryNotification.setBatteryDirect(device.leftBattery ?: -1, device.isLeftCharging,
                device.rightBattery ?: -1, device.isRightCharging, device.caseBattery ?: -1, device.isCaseCharging)
            updateBattery(force = false)
        }
        @SuppressLint("NewApi")
        override fun onDeviceStatusChanged(
            device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?
        ) {
            if (!config.bleOnlyMode && config.takeoverWhenDisconnected &&
                device.connectionState == "Disconnected" && BluetoothConnectionManager.aacpSocket?.isConnected != true) {
                Log.d(TAG, "Seems no device has taken over, we will.")
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val bluetoothAdapter = bluetoothManager.adapter
                val savedAddress = sharedPreferences.getString("mac_address", "") ?: ""
                if (BluetoothAdapter.checkBluetoothAddress(savedAddress)) {
                    val bluetoothDevice = bluetoothAdapter.getRemoteDevice(savedAddress)
                    serviceScope.launch {
                        if (config.takeoverWhenDisconnected && !config.bleOnlyMode &&
                            savedAddress.equals(macAddress, ignoreCase = true) && !serviceResources.isClosed) {
                            audioProfileOwnership.claim(savedAddress)
                            connectToSocket(bluetoothAdapter, bluetoothDevice)
                        }
                    }
                }
            }
            Log.d(TAG, "Device status changed")

        }

        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "New address detected")
        }

        override fun onLidStateChanged(
            lidOpen: Boolean,
        ) {
            if (lidOpen) {
                Log.d(TAG, "Lid opened")
                showPopup(
                    this@AirPodsService,
                    getSharedPreferences("settings", MODE_PRIVATE).getString("name", "AirPods Pro")
                        ?: "AirPods"
                )

            } else {
                Log.d(TAG, "Lid closed")
            }
        }

        override fun onEarStateChanged(
            device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean
        ) {
            Log.d(TAG, "Ear state changed - Left: $leftInEar, Right: $rightInEar")

            // In BLE-only mode, ear detection is purely based on BLE data
            if (config.bleOnlyMode) {
                Log.d(TAG, "BLE-only mode: ear detection from BLE data")
            }
        }

        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "Battery changed")
        }

        override fun onDeviceDisappeared() {
            Log.d(TAG, "All disappeared")
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
                updateNotificationContent(false)
            }
        }
    }

    fun isBluetoothSocketExempted(): Boolean {
        return try {
            BluetoothSocket::class.java.declaredConstructors // will throw if still blocked
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag", "HardwareIds")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lib exempt worked: ${isBluetoothSocketExempted()}")

        packetLogHistory = PacketLogs.open(applicationContext)
        inMemoryLogs = packetLogHistory.buffer

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        batteryHistory = BatteryHistoryStore(this, writable = true)
        initializeConfig()
        refreshCachedInformation()

        aacpManager = AACPManager()
        aacpManager.setListeningModePolicy(::supportedListeningModes)
        initializeAACPManagerCallback()

        attManager = ATTManagerv2()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        ServiceManager.setService(this)
        notificationLease = serviceResources.track(ConnectionNotifications.claim(this))
        startForegroundNotification()
        initializeLocalMac()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initGestureDetector()
        } else {
            gestureDetector = null
            config.headGestures = false
            sharedPreferences.edit { putBoolean("head_gestures", false) }
            Log.d(TAG, "Head gestures disabled as device is running Android 9 or below")
        }

        bleManager = BLEManager(this)
        bleManager.setAirPodsStatusListener(bleStatusListener)

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        with(sharedPreferences) {
            edit {
                if (!contains("conversational_awareness_pause_music")) putBoolean(
                    "conversational_awareness_pause_music", false
                )
                if (!contains("personalized_volume")) putBoolean("personalized_volume", false)
                if (!contains("automatic_ear_detection")) putBoolean(
                    "automatic_ear_detection", true
                )
                if (!contains("long_press_nc")) putBoolean("long_press_nc", true)
                if (!contains("show_phone_battery_in_widget")) putBoolean(
                    "show_phone_battery_in_widget", true
                )
                if (!contains("single_anc")) putBoolean("single_anc", true)
                if (!contains("long_press_transparency")) putBoolean(
                    "long_press_transparency", true
                )
                if (!contains("conversational_awareness")) putBoolean(
                    "conversational_awareness", true
                )
                if (!contains("relative_conversational_awareness_volume")) putBoolean(
                    "relative_conversational_awareness_volume", true
                )
                if (!contains("long_press_adaptive")) putBoolean("long_press_adaptive", true)
                if (!contains("loud_sound_reduction")) putBoolean("loud_sound_reduction", true)
                if (!contains("long_press_off")) putBoolean("long_press_off", false)
                if (!contains("volume_control")) putBoolean("volume_control", true)
                if (!contains("head_gestures")) putBoolean("head_gestures", true)
                if (!contains("disconnect_when_not_wearing")) putBoolean(
                    "disconnect_when_not_wearing", false
                )

                // AirPods state-based takeover
                if (!contains("takeover_when_disconnected")) putBoolean(
                    "takeover_when_disconnected", false
                )
                if (!contains("takeover_when_idle")) putBoolean("takeover_when_idle", false)
                if (!contains("takeover_when_music")) putBoolean("takeover_when_music", false)
                if (!contains("takeover_when_call")) putBoolean("takeover_when_call", false)

                // Phone state-based takeover
                if (!contains("takeover_when_ringing_call")) putBoolean(
                    "takeover_when_ringing_call", false
                )
                if (!contains("takeover_when_media_start")) putBoolean(
                    "takeover_when_media_start", false
                )

                if (!contains("adaptive_strength")) putInt("adaptive_strength", 51)
                if (!contains("tone_volume")) putInt("tone_volume", 75)
                if (!contains("conversational_awareness_volume")) putInt(
                    "conversational_awareness_volume", 43
                )

                if (!contains("qs_click_behavior")) putString("qs_click_behavior", "cycle")
                if (!contains("name")) putString("name", "AirPods")

                if (!contains("left_single_press_action")) putString(
                    "left_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("right_single_press_action")) putString(
                    "right_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("left_double_press_action")) putString(
                    "left_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("right_double_press_action")) putString(
                    "right_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("left_triple_press_action")) putString(
                    "left_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("right_triple_press_action")) putString(
                    "right_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("left_long_press_action")) putString(
                    "left_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("right_long_press_action")) putString(
                    "right_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )

            }
        }

        initializeConfig()

        externalBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    if (intent.hasExtra("mode")) {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode in 1..4) {
                            setListeningMode(mode)
                        }
                    } else {
                        val currentMode = ancNotification.status
                        val configByte = sharedPreferences.getInt("long_press_byte", 0b0111)
                        val allowOffMode = isOffListeningModeEnabled()
                        val nextMode = getNextMode(currentMode = currentMode, configByte = configByte, allowOffMode)

                        setListeningModeAsync(nextMode)
                        Log.d(
                            TAG,
                            "Cycling ANC mode from $currentMode to $nextMode"
                        )
                    }
                } else  if (intent?.action == "me.kavishdevar.librepods.CONVO_DETECT") {
                    if (intent.hasExtra("enabled")) {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        enqueueInteractiveCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                            byteArrayOf(if (enabled) 1 else 2)
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalBroadcastReceiver, externalBroadcastFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                externalBroadcastReceiver, externalBroadcastFilter
            )
        }
        val audioManager = this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        MediaController.initialize(
            audioManager, this@AirPodsService.getSharedPreferences(
                "settings", MODE_PRIVATE
            ),
            owner = this@AirPodsService
        )
//        Log.d(TAG, "Initializing CrossDevice")
//        CoroutineScope(Dispatchers.IO).launch {
//            CrossDevice.init(this@AirPodsService)
//            Log.d(TAG, "CrossDevice initialized")
//        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""
        audioProfileOwnership.select(macAddress)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(Dispatchers.IO).launch {
                        if (leAvailableForAudio) serviceScope.launch {
                            takeOver("call")
                        }
                        if (config.headGestures) {
                            handleIncomingCall()
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(
                        if (leAvailableForAudio) serviceScope.launch {
                            takeOver("call")
                        }
                        isInCall = true
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        isInCall = false
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(mainExecutor, phoneStateListener)
        }

        widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
        updatePhoneBatteryReceiver()
        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
            addAction("android.bluetooth.device.action.UUID")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    val detectedDevice = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                    attachControlIfAudioConnected(detectedDevice)

                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    synchronized(BluetoothConnectionManager) {
                        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                            Log.d(TAG, "Ignoring a stale disconnect broadcast after reconnection")
                            return
                        }
                        device = null
//                      isConnectedLocally = false
                        popupShown = false
                        updateNotificationContent(false)
                        aacpManager.disconnected()
                        BluetoothConnectionManager.aacpSocket = null
                        BluetoothConnectionManager.attSocket = null
                        clearA2dpConnectionReceiver()
                    }
                }
            }
        }
        showIslandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(
                        this@AirPodsService,
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                                batteryNotification.getBattery()
                                    .find { it.component == BatteryComponent.RIGHT }?.level!!
                            )
                    )
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                showIslandReceiver, showIslandIntentFilter
            )
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                connectionReceiver, deviceIntentFilter
            )
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        // Query connected profiles; discovery alone must not attach control or claim audio.
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            withAudioProfile(this, profile, AudioProfileAction.DISCOVER) { proxy ->
                val connected = proxy.connectedDevices
                connected.filter { it.uuids == null }.forEach { if (isCurrentProfileAction()) it.fetchUuidsWithSdp() }
                val candidate = connected.filter(::isKnownAirPods)
                    .let { connected -> connected.find { it.address.equals(macAddress, true) } ?: connected.firstOrNull() }
                candidate?.let { attachControlDevice(it) }
            }
        }

//        if (!isConnectedLocally && !CrossDevice.isAvailable) {
//            clearPacketLogs()
//        }

        serviceScope.launch {
            bleManager.startScanning()
        }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun initializeLocalMac() {
        localMac = config.selfMacAddress
        if (localMac.isNotEmpty()) {
            localMacLookupFinished.countDown()
            return
        }
        if (checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
            localMac = getSystemService(BluetoothManager::class.java).adapter.address
            config.selfMacAddress = localMac
            sharedPreferences.edit { putString("self_mac_address", localMac) }
            localMacLookupFinished.countDown()
            return
        }

        serviceScope.launch {
            var ownedProcess: Closeable? = null
            try {
                val process = ProcessBuilder("su", "-c", "settings get secure bluetooth_address")
                    .redirectErrorStream(true)
                    .start()
                val resource = Closeable {
                    process.destroy()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                }
                ownedProcess = resource
                serviceResources.track(resource)
                val resolved = if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    process.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                } else ""
                if (BluetoothAdapter.checkBluetoothAddress(resolved)) {
                    withContext(Dispatchers.Main) {
                        serviceResources.whileOpen {
                            localMac = resolved
                            config.selfMacAddress = resolved
                            sharedPreferences.edit { putString("self_mac_address", resolved) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!serviceResources.isClosed) {
                    Log.w(TAG, "Could not resolve the local Bluetooth address: ${e.message}")
                }
            } finally {
                ownedProcess?.let {
                    runCatching { it.close() }
                    serviceResources.release(it)
                }
                localMacLookupFinished.countDown()
            }
        }.invokeOnCompletion { localMacLookupFinished.countDown() }
    }

    @Suppress("unused")
    fun cameraOpened() {
        if (cameraActive || serviceResources.isClosed) return
        Log.d(TAG, "Camera opened, gonna handle stem presses and take action if visible")
        cameraActive = true
        cameraGeneration.incrementAndGet()
        setupStemActions()
    }

    @Suppress("unused")
    fun cameraClosed() {
        if (!cameraActive || serviceResources.isClosed) return
        cameraActive = false
        cameraGeneration.incrementAndGet()
        setupStemActions()
    }

    fun isCustomAction(
        action: StemAction?, default: StemAction?
    ): Boolean {
        return action != default
    }

    fun setupStemActions() {
        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized =
            isCustomAction(config.leftSinglePressAction, singlePressDefault) || isCustomAction(
                config.rightSinglePressAction, singlePressDefault
            ) || (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized =
            isCustomAction(config.leftDoublePressAction, doublePressDefault) || isCustomAction(
                config.rightDoublePressAction, doublePressDefault
            )
        val triplePressCustomized =
            isCustomAction(config.leftTriplePressAction, triplePressDefault) || isCustomAction(
                config.rightTriplePressAction, triplePressDefault
            )
        val longPressCustomized = isCustomAction(
            config.leftLongPressAction, longPressDefault
        ) || isCustomAction(
            config.rightLongPressAction, longPressDefault
        ) || (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)
        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "Setting up stem actions: Single Press Customized: $singlePressCustomized, Double Press Customized: $doublePressCustomized, Triple Press Customized: $triplePressCustomized, Long Press Customized: $longPressCustomized"
        )
        val value = aacpManager.createStemConfigValue(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
        enqueueInteractiveCommand(AACPManager.Companion.ControlCommandIdentifiers.STEM_CONFIG.value,
            byteArrayOf(value))
    }

    @ExperimentalEncodingApi
    private fun initializeAACPManagerCallback() {
        aacpManager.setPacketCallback(object : AACPManager.PacketCallback {
            @SuppressLint("MissingPermission")
            override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
                val owner = currentPacketPeer() ?: return
                batteryNotification.setBattery(batteryInfo)
                if (batteryInfo.size == 22) {
                    batteryHistory.record(owner, listOf(7, 12, 17).map { offset ->
                        Battery(batteryInfo[offset].toInt() and 0xFF, batteryInfo[offset + 2].toInt() and 0xFF,
                            batteryInfo[offset + 3].toInt() and 0xFF)
                    })
                }
                updateBattery(force = false)
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

                val batteries = batteryNotification.getBattery()
                val buds = batteries.take(2)
                val bothCharging = when {
                    buds.all { it.level in 0..100 && (it.status == BatteryStatus.CHARGING || it.status == BatteryStatus.OPTIMIZED_CHARGING) } -> true
                    buds.any { it.level in 0..100 && it.status == BatteryStatus.NOT_CHARGING } -> false
                    else -> return // Unknown readings do not establish an audio policy.
                }
                if (audioChargingState.changed(owner, bothCharging)) {
                    if (bothCharging) disconnectAudio(this@AirPodsService, device, restorable = true)
                    else restoreAudio()
                }
            }

            override fun onEarDetectionReceived(earDetection: ByteArray) {
                currentPacketPeer() ?: return
                sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                    putExtra("data", earDetection.copyOfRange(6, 8))
                }.apply {
                    setPackage(packageName)
                })
                Log.d(
                    "AirPodsParser",
                    "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                )
                processEarDetectionChange(earDetection)
            }

            override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
                val peer = currentPacketPeer() ?: return
                if (!conversationAwarenessNotification.setData(conversationAwareness)) return
                val socket = packetSourceSocket.get() ?: return
                synchronized(conversationQueueLock) {
                    if (socket !== BluetoothConnectionManager.aacpSocket || !socket.isConnected || !peer.equals(macAddress, true)) return
                    val level = conversationAwareness[9].toInt() and 0xFF
                    incomingConversationState.update(level)
                    // Intermediate packets may replace the start packet in the Main queue;
                    // retain the speech phase derived from every accepted packet.
                    conversationUpdates.offer(ConversationUpdate(conversationGeneration.get(),
                        level, peer, socket, speaking = incomingConversationState.isSpeaking))
                }
            }

            override fun onControlCommandReceived(controlCommand: ByteArray) {
                val peer = currentPacketPeer() ?: return
                val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
                if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                    ancNotification.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }
                        ?.get(0) ?: 0x00.toByte()))
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                } else if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION.value) {
                    synchronized(BluetoothConnectionManager) {
                        if (currentPacketPeer() != peer) return
                        me.kavishdevar.librepods.data.saveOffListeningMode(sharedPreferences, peer,
                            command.value.firstOrNull() == 0x01.toByte())
                    }
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                    requestSleepTimerDelivery()
                } else if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value &&
                    command.value.firstOrNull() == 0x02.toByte()) {
                    resetConversation(resumeMedia = true, peer = peer, socket = packetSourceSocket.get())
                }
            }

            override fun onOwnershipChangeReceived(owns: Boolean) {
                currentPacketPeer() ?: return
                if (!owns) {
                    revokeAudioRestore(ownershipLost = true)
                    MediaController.recentlyLostOwnership = true
                    ownershipResetJob?.cancel()
                    ownershipResetJob = serviceScope.launch(Dispatchers.Main) {
                        delay(3000)
                        MediaController.recentlyLostOwnership = false
                    }
                    Log.d(TAG, "ownership lost")
                    MediaController.sendPause()
                    MediaController.pausedForOtherDevice = true
                    otherDeviceTookOver = true
                    disconnectAudio(
                        this@AirPodsService, device
                    )
                }
            }

            override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
                currentPacketPeer() ?: return
                revokeAudioRestore(ownershipLost = true)
                // TODO: Show a reverse button, but that's a lot of effort -- i'd have to change the UI too, which i hate doing, and handle other device's reverses too, and disconnect audio etc... so for now, just pause the audio and show the island without asking to reverse.
                // handling reverse is a problem because we'd have to disconnect the audio, but there's no option connect audio again natively, so notification would have to be changed. I wish there was a way to just "change the audio output device".
                // (20 minutes later) i've done it nonetheless :]
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                Log.d(
                    TAG,
                    "other device has hijacked the connection, reasonReverseTapped: $reasonReverseTapped"
                )
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    byteArrayOf(0x00)
                )
                otherDeviceTookOver = true
                disconnectAudio(
                    this@AirPodsService, device
                )
                if (reasonReverseTapped) {
                    Log.d(TAG, "reverse tapped, disconnecting audio")
                    disconnectedBecauseReversed = true
                    sendBatteryNotification()
                    disconnectAudio(this@AirPodsService, device)
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = true,
                        otherDeviceName = senderName
                    )
                }
                if (!aacpManager.owns) {
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = reasonReverseTapped,
                        otherDeviceName = senderName
                    )
                }
                MediaController.sendPause()
            }

            override fun onShowNearbyUI(sender: String) {
                currentPacketPeer() ?: return
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    ),
                    IslandType.MOVED_TO_OTHER_DEVICE,
                    reversed = false,
                    otherDeviceName = senderName
                )
            }

            override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
                val peer = currentPacketPeer() ?: return
                Log.d(
                    "AirPodsParser",
                    "Device Information: name: ${deviceInformation.name}, modelNumber: ${deviceInformation.modelNumber}, manufacturer: ${deviceInformation.manufacturer}, serialNumber: ${deviceInformation.serialNumber}, version1: ${deviceInformation.version1}, version2: ${deviceInformation.version2}, hardwareRevision: ${deviceInformation.hardwareRevision}, updaterIdentifier: ${deviceInformation.updaterIdentifier}, leftSerialNumber: ${deviceInformation.leftSerialNumber}, rightSerialNumber: ${deviceInformation.rightSerialNumber}, version3: ${deviceInformation.version3}"
                )
                val informationDevice = synchronized(BluetoothConnectionManager) {
                    // A delayed old information callback cannot overwrite a replacement
                    // session's owned snapshot after that session has been published.
                    if (currentPacketPeer() != peer) return
                    sharedPreferences.edit {
                        putString("name", deviceInformation.name)
                        putString("airpods_model_number", deviceInformation.modelNumber)
                        putString("airpods_model_address", peer)
                        putString("airpods_manufacturer", deviceInformation.manufacturer)
                        putString("airpods_serial_number", deviceInformation.serialNumber)
                        putString("airpods_left_serial_number", deviceInformation.leftSerialNumber)
                        putString("airpods_right_serial_number", deviceInformation.rightSerialNumber)
                        putString("airpods_version1", deviceInformation.version1)
                        putString("airpods_version2", deviceInformation.version2)
                        putString("airpods_version3", deviceInformation.version3)
                        putString("airpods_hardware_revision", deviceInformation.hardwareRevision)
                        putString("airpods_updater_identifier", deviceInformation.updaterIdentifier)
                    }
                    config.airpodsName = deviceInformation.name
                    config.airpodsModelNumber = deviceInformation.modelNumber
                    config.airpodsManufacturer = deviceInformation.manufacturer
                    config.airpodsSerialNumber = deviceInformation.serialNumber
                    config.airpodsLeftSerialNumber = deviceInformation.leftSerialNumber
                    config.airpodsRightSerialNumber = deviceInformation.rightSerialNumber
                    config.airpodsVersion1 = deviceInformation.version1
                    config.airpodsVersion2 = deviceInformation.version2
                    config.airpodsVersion3 = deviceInformation.version3
                    config.airpodsHardwareRevision = deviceInformation.hardwareRevision
                    config.airpodsUpdaterIdentifier = deviceInformation.updaterIdentifier
                    refreshCachedInformation()
                    device
                }
                requestSleepTimerDelivery()
                informationDevice?.let(::setMetadatas)
                sendBroadcast(
                    Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(
                        packageName
                    )
                )
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(headTracking: ByteArray) {
                currentPacketPeer() ?: return
                val applied = appliedHeadTrackingRequest
                if (applied != null && applied.ticket.enabled && headTrackingRequestCurrent(applied)) {
                    val motion = me.kavishdevar.librepods.bluetooth.RtBuddyHeadTracking.motion(headTracking) ?: return
                    // AACP has validated the sensor payload length before this callback.
                    headTrackingStartup.received(applied)
                    HeadTracking.processMotion(motion)
                    processHeadTrackingData(headTracking)
                }
            }

            override fun onHeadTrackingServiceDiscovered() {
                currentPacketPeer() ?: return
                val ticket = headTrackingSession.capture()
                if (ticket.enabled) enqueueHeadTrackingCommand(ticket, restart = true)
            }

            override fun onProximityKeysReceived(proximityKeys: ByteArray) {
                val owner = currentPacketPeer() ?: return
                val keys = aacpManager.parseProximityKeysResponse(proximityKeys)
                sharedPreferences.edit {
                    for (key in keys) {
                        putString(key.key.name, Base64.encode(key.value))
                        putString("${key.key.name}_owner", owner)
                    }
                }
            }

            override fun onStemPressReceived(stemPress: ByteArray) {
                currentPacketPeer() ?: return
                val (stemPressType, bud) = aacpManager.parseStemPressResponse(stemPress)

                Log.d(
                    "AirPodsParser",
                    "Stem press received: $stemPressType on $bud, cameraActive: $cameraActive, cameraAction: ${config.cameraAction}"
                )
                if (cameraActive && config.cameraAction != null && stemPressType == config.cameraAction) {
                    captureCamera()
                } else {
                    val action = getActionFor(bud, stemPressType)
                    Log.d("AirPodsParser", "$bud $stemPressType action: $action")
                    action?.let { executeStemAction(it) }
                }
            }

            override fun onAudioSourceReceived(audioSource: ByteArray) {
                currentPacketPeer() ?: return
                Log.d(
                    "AirPodsParser",
                    "Audio source changed mac: ${aacpManager.audioSource?.mac}, type: ${aacpManager.audioSource?.type?.name}"
                )
                if (localMac!="" && (aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE && aacpManager.audioSource?.mac != localMac)) {
                    Log.d(
                        "AirPodsParser",
                        "Audio source is another device, better to give up aacp control"
                    )
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                        byteArrayOf(0x00)
                    )
                    revokeAudioRestore(ownershipLost = true)
                    // this also means that the other device has start playing the audio, and if that's true, we can again start listening for audio config changes
//                    Log.d(TAG, "Another device started playing audio, listening for audio config changes again")
//                    MediaController.pausedForOtherDevice = false
// future me: what the heck is this? this just means it will not be taking over again if audio source doesn't change???
                }
            }

            override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
                currentPacketPeer() ?: return
                for (device in connectedDevices) {
                    Log.d(
                        "AirPodsParser",
                        "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld =
                        aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = newDevice.mac != localMac
                    notInOld && notLocal
                }

                for (device in newDevices) {
                    Log.d(
                        "AirPodsParser",
                        "New connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                    Log.d(
                        TAG,
                        "Sending new Tipi packet for device ${device.mac}, and sending media info to the device"
                    )
                    aacpManager.sendMediaInformationNewDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                    aacpManager.sendAddTiPiDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                }
            }

            override fun onHeadphoneAccommodationReceived(eqData: FloatArray) {
                currentPacketPeer() ?: return
                sendBroadcast(
                    Intent(AirPodsNotifications.EQ_DATA).putExtra("eqData", eqData).apply {
                        setPackage(packageName)
                    })
            }

            override fun onCustomEqReceived(customEq: CustomEq) {
                // TODO
            }

            override fun onCapabilitiesReceived(capabilities: List<Capability>) {
                // TODO
            }

            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d(
                    "AACPManager",
                    "Unknown packet received: ${packet.joinToString(" ") { "%02X".format(it) }}"
                )
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun currentPacketPeer(): String? {
        val source = packetSourceSocket.get() ?: return null
        if (serviceResources.isClosed || source !== BluetoothConnectionManager.aacpSocket || !source.isConnected ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val address = source.remoteDevice.address
        return address.takeIf { it.equals(sharedPreferences.getString("mac_address", ""), ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    internal fun isCurrentControlSocket(socket: BluetoothSocket): Boolean =
        !serviceResources.isClosed && socket === BluetoothConnectionManager.aacpSocket && socket.isConnected &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            socket.remoteDevice.address.equals(sharedPreferences.getString("mac_address", ""), ignoreCase = true)

    @SuppressLint("MissingPermission")
    internal fun isCurrentAttSocket(socket: BluetoothSocket): Boolean =
        !serviceResources.isClosed && socket === BluetoothConnectionManager.attSocket && socket.isConnected &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            socket.remoteDevice.address.equals(sharedPreferences.getString("mac_address", ""), ignoreCase = true)

    @SuppressLint("MissingPermission")
    fun sendMediaInformationAsync(streamingState: Boolean) {
        val socket = BluetoothConnectionManager.aacpSocket ?: return
        if (!isCurrentControlSocket(socket)) return
        val self = me.kavishdevar.librepods.data.batteryHistoryIdentity(localMac) ?: return
        val ticket = audioProfileOwnership.captureMedia(socket.remoteDevice.address) ?: return
        mediaInformationUpdates.offer(Unit, MediaInformationRequest(socket, self, streamingState, ticket,
            aacpManager.captureDeviceSession()))
    }

    private fun mediaOperationCurrent(socket: BluetoothSocket, ticket: AudioProfileOwnership.MediaTicket): Boolean =
        ServiceManager.getService() === this && isCurrentControlSocket(socket) &&
            audioProfileOwnership.isCurrentMedia(ticket)

    private fun publishMediaInformation(request: MediaInformationRequest) {
        val current = {
            !mediaInformationUpdates.isClosed && aacpManager.isCurrentDeviceSession(request.deviceSession) &&
                mediaOperationCurrent(request.socket, request.ticket) &&
                request.selfMac == me.kavishdevar.librepods.data.batteryHistoryIdentity(localMac)
        }
        if (!current()) return
        val packet = aacpManager.createMediaInformationData(request.selfMac, request.streaming) ?: return
        aacpManager.sendPacket(packet, request.socket, current)
    }

    private fun getActionFor(
        bud: AACPManager.Companion.StemPressBudType, type: StemPressType
    ): StemAction? {
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftSinglePressAction else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftDoublePressAction else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftTriplePressAction else config.rightTriplePressAction
            StemPressType.LONG_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftLongPressAction else config.rightLongPressAction
        }
    }

    private fun captureCamera() {
        if (!cameraActive || serviceResources.isClosed || !cameraCaptureInFlight.compareAndSet(false, true)) return
        val generation = cameraGeneration.get()
        serviceScope.launch {
            try {
                val captured = runInterruptible {
                    CameraRemoteCommand().capture {
                        cameraActive && cameraGeneration.get() == generation && !serviceResources.isClosed
                    }
                }
                if (!captured && cameraActive && cameraGeneration.get() == generation && !serviceResources.isClosed) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AirPodsService, R.string.camera_remote_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Camera remote could not inject the camera key", e)
                if (!serviceResources.isClosed) withContext(Dispatchers.Main) {
                    Toast.makeText(this@AirPodsService, R.string.camera_remote_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.invokeOnCompletion { cameraCaptureInFlight.set(false) }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                Log.d(
                    "AirPodsParser", "Default single press action: Play/Pause, not taking action."
                )
            }

            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.DIGITAL_ASSISTANT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Log.w(
                        "AirPodsParser",
                        "Digital Assistant action is not supported on this Android version."
                    )
                }
            }

            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                Log.d("AirPodsParser", "Cycling noise control modes")
                sendBroadcast(Intent("me.kavishdevar.librepods.SET_ANC_MODE").apply {
                    setPackage(packageName)
                })
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        var inEar: Boolean
        val inEarData = listOf(
            earDetectionNotification.status[0] == 0x00.toByte(),
            earDetectionNotification.status[1] == 0x00.toByte()
        )
        var justEnabledA2dp = false
        earDetectionNotification.setStatus(earDetection)
        if (!audioProfileOwnership.allowsMediaControl(macAddress)) return
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

            val newInEarData = listOf(
                data[0] == 0x00.toByte(), data[1] == 0x00.toByte()
            )

            if (inEarData.sorted() == listOf(false, false) && newInEarData.sorted() != listOf(
                    false, false
                ) && islandWindow?.isVisible != true
            ) {
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    )
                )
            }

            if (newInEarData == listOf(false, false) && islandWindow?.isVisible == true) {
                islandWindow?.close()
            }

            if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
                justEnabledA2dp = restoreAudio(playWhenConnected = true)
                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
            } else if (newInEarData == listOf(false, false)) {
                audioProfileOwnership.cancelPendingRestore()
                serviceScope.launch(Dispatchers.Main.immediate) { clearStaleA2dpConnectionReceiver() }
                MediaController.sendPause(force = true)
                if (config.disconnectWhenNotWearing) {
                    disconnectAudio(this@AirPodsService, device, restorable = true)
                }
            }
            val wasNone = inEarData == listOf(false, false)
            val nowSingle = newInEarData.count { it } == 1

            if (wasNone && nowSingle) {
                if (!justEnabledA2dp) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
                return
            }

            if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User put in both AirPods from just one.")
                MediaController.userPlayedTheMedia = false
            }

            if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User took one of two out.")
                MediaController.userPlayedTheMedia = false
            }

            Log.d(
                "AirPodsParser",
                "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
            )

            if (newInEarData.sorted() != inEarData.sorted()) {
                if (inEar) {
                    if (!justEnabledA2dp) {
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false
                    }
                } else {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun resetConversation(resumeMedia: Boolean = false, peer: String? = null, socket: BluetoothSocket? = null) {
        synchronized(conversationQueueLock) {
            incomingConversationState.reset()
            conversationUpdates.offer(ConversationUpdate(conversationGeneration.incrementAndGet(),
                peer = peer, socket = socket, resumeMedia = resumeMedia))
        }
    }

    /** All volume work runs on Main; stale control packets cannot attenuate a replacement link. */
    private fun handleConversationUpdate(update: ConversationUpdate) {
        if (serviceResources.isClosed || update.generation != conversationGeneration.get()) return
        if (update.socket != null && (update.socket !== BluetoothConnectionManager.aacpSocket ||
            !update.socket.isConnected || !update.peer.equals(macAddress, ignoreCase = true))) return
        if (handledConversationGeneration != update.generation) {
            conversationState.reset()
            handledConversationGeneration = update.generation
            MediaController.stopSpeaking(this, resumeMedia = update.resumeMedia &&
                update.peer?.let(audioProfileOwnership::allowsMediaControl) == true)
        }
        val level = update.level ?: return
        sendBroadcast(Intent(AirPodsNotifications.CA_DATA).putExtra("data", level.toByte()).setPackage(packageName))
        when (conversationState.applyDesired(update.speaking ?: return)) {
            ConversationAwarenessState.Change.LOWER -> {
                if (update.peer?.let(audioProfileOwnership::allowsMediaControl) == true) MediaController.startSpeaking(this)
            }
            ConversationAwarenessState.Change.RESTORE -> MediaController.stopSpeaking(this,
                resumeMedia = update.peer?.let(audioProfileOwnership::allowsMediaControl) == true)
            ConversationAwarenessState.Change.NONE -> Unit
        }
    }

    private fun revokeAudioRestore(ownershipLost: Boolean = false) {
        takeoverGeneration.incrementAndGet()
        audioProfileOwnership.revoke(ownershipLost)
        resetConversation()
        serviceScope.launch(Dispatchers.Main.immediate) { clearStaleA2dpConnectionReceiver() }
    }

    private fun restoreAudio(playWhenConnected: Boolean = false): Boolean {
        val target = device ?: return false
        val ticket = audioProfileOwnership.beginRestore(target.address) ?: return false
        val socket = BluetoothConnectionManager.aacpSocket ?: return false
        connectAudioProfiles(this, target, ticket, socket, playWhenConnected)
        return BluetoothProfile.A2DP in ticket.profiles
    }

    private fun clearStaleA2dpConnectionReceiver() {
        val watch = a2dpWatch.get() ?: return
        if (!audioProfileOwnership.isCurrentSnapshot(watch.ticket) && a2dpWatch.compareAndSet(watch, null)) watch.close()
    }

    private fun clearA2dpConnectionReceiver(expected: BroadcastReceiver? = null) {
        while (true) {
            val watch = a2dpWatch.get() ?: return
            if (expected != null && watch.receiver !== expected) return
            if (a2dpWatch.compareAndSet(watch, null)) { watch.close(); return }
        }
    }

    private fun dispatchProfileCleanup(action: () -> Unit) {
        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            try { action() } catch (error: Exception) { Log.w(TAG, "Audio profile cleanup failed", error) }
        }
    }

    /** Register on IO before requesting connect; callback identity and retirement are atomic. */
    @SuppressLint("MissingPermission")
    private fun registerA2dpConnectionReceiver(
        target: BluetoothDevice, ticket: AudioProfileOwnership.Ticket, socket: BluetoothSocket
    ): BroadcastReceiver? {
        if (serviceResources.isClosed || !audioProfileOwnership.isCurrentSnapshot(ticket)) return null
        lateinit var watch: A2dpWatch
        fun current() = !watch.registration.isClosed && audioOperationValid(target, ticket, socket) &&
            audioProfileOwnership.isCurrentSnapshot(ticket) && aacpManager.isCurrentDeviceSession(watch.deviceSession)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (a2dpWatch.get() !== watch) return
                if (!current()) { clearA2dpConnectionReceiver(this); return }
                val connected = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
                val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                val peer = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (connected && previous != BluetoothProfile.STATE_CONNECTED &&
                    peer?.address.equals(target.address, ignoreCase = true) && a2dpWatch.compareAndSet(watch, null)) {
                    watch.close()
                    // The registration is retired now; validate the ticket independently of it.
                    serviceScope.launch {
                        if (audioOperationValid(target, ticket, socket) && audioProfileOwnership.isCurrentSnapshot(ticket) &&
                            aacpManager.isCurrentDeviceSession(watch.deviceSession)) {
                            MediaController.sendPlay()
                            MediaController.iPausedTheMedia = false
                        }
                    }
                }
            }
        }
        watch = A2dpWatch(receiver, ticket, aacpManager.captureDeviceSession(), DeferredRegistration {
            dispatchProfileCleanup { applicationContext.unregisterReceiver(receiver) }
        })
        a2dpWatch.getAndSet(watch)?.close()
        try {
            applicationContext.registerReceiver(receiver, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), RECEIVER_EXPORTED)
            if (!watch.registration.didRegister()) return null
        } catch (error: Exception) {
            a2dpWatch.compareAndSet(watch, null)
            watch.close()
            dispatchProfileCleanup { runCatching { applicationContext.unregisterReceiver(receiver) } }
            Log.w(TAG, "Could not observe the A2DP connection", error)
            return null
        }
        if (!current() || a2dpWatch.get() !== watch) { clearA2dpConnectionReceiver(receiver); return null }
        watch.arm(serviceScope.launch {
            delay(10_000)
            clearA2dpConnectionReceiver(receiver)
        })
        return receiver
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean(
                "conversational_awareness_pause_music", false
            ),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean(
                "show_phone_battery_in_widget", true
            ),
            relativeConversationalAwarenessVolume = sharedPreferences.getBoolean(
                "relative_conversational_awareness_volume", true
            ),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean(
                "disconnect_when_not_wearing", false
            ),
            conversationalAwarenessVolume = sharedPreferences.getInt(
                "conversational_awareness_volume", 43
            ),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle",

            // AirPods state-based takeover
            takeoverWhenDisconnected = sharedPreferences.getBoolean(
                "takeover_when_disconnected", false
            ),
            takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
            takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
            takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),

            // Phone state-based takeover
            takeoverWhenRingingCall = sharedPreferences.getBoolean(
                "takeover_when_ringing_call", false
            ),
            takeoverWhenMediaStart = sharedPreferences.getBoolean(
                "takeover_when_media_start", false
            ),

            // Stem actions
            leftSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            ) ?: StemAction.PLAY_PAUSE,
            rightSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            ) ?: StemAction.PLAY_PAUSE,

            leftDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_double_press_action", "NEXT_TRACK"
                ) ?: "NEXT_TRACK"
            ) ?: StemAction.NEXT_TRACK,
            rightDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_double_press_action", "NEXT_TRACK"
                ) ?: "NEXT_TRACK"
            ) ?: StemAction.NEXT_TRACK,

            leftTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            ) ?: StemAction.PREVIOUS_TRACK,
            rightTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            ) ?: StemAction.PREVIOUS_TRACK,

            leftLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_long_press_action", "CYCLE_NOISE_CONTROL_MODES"
                ) ?: "CYCLE_NOISE_CONTROL_MODES"
            ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES,
            rightLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_long_press_action", "CYCLE_NOISE_CONTROL_MODES"
                ) ?: "CYCLE_NOISE_CONTROL_MODES"
            ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES,

            cameraAction = cameraActionFromString(sharedPreferences.getString("camera_action", null)),

            // AirPods device information
            airpodsName = sharedPreferences.getString("airpods_name", "") ?: "",
            airpodsModelNumber = sharedPreferences.getString("airpods_model_number", "") ?: "",
            airpodsManufacturer = sharedPreferences.getString("airpods_manufacturer", "") ?: "",
            airpodsSerialNumber = sharedPreferences.getString("airpods_serial_number", "") ?: "",
            airpodsLeftSerialNumber = sharedPreferences.getString("airpods_left_serial_number", "")
                ?: "",
            airpodsRightSerialNumber = sharedPreferences.getString(
                "airpods_right_serial_number", ""
            ) ?: "",
            airpodsVersion1 = sharedPreferences.getString("airpods_version1", "") ?: "",
            airpodsVersion2 = sharedPreferences.getString("airpods_version2", "") ?: "",
            airpodsVersion3 = sharedPreferences.getString("airpods_version3", "") ?: "",
            airpodsHardwareRevision = sharedPreferences.getString("airpods_hardware_revision", "")
                ?: "",
            airpodsUpdaterIdentifier = sharedPreferences.getString("airpods_updater_identifier", "")
                ?: "",

            selfMacAddress = sharedPreferences.getString("self_mac_address", "") ?: ""
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return
        if (key in informationKeys) refreshCachedInformation(notify = true)

        when (key) {
            "name" -> {
                config.deviceName = preferences.getString(key, "AirPods") ?: "AirPods"
                sendBatteryNotification()
            }
            "mac_address" -> {
                notificationGeneration.incrementAndGet()
                metadataSelection.incrementAndGet()
                takeoverGeneration.incrementAndGet()
                val previousHeadPeer = me.kavishdevar.librepods.data.batteryHistoryIdentity(macAddress)
                macAddress = preferences.getString(key, "") ?: ""
                if (previousHeadPeer != me.kavishdevar.librepods.data.batteryHistoryIdentity(macAddress)) HeadTracking.reset()
                headTrackingSession.selectPeer(me.kavishdevar.librepods.data.batteryHistoryIdentity(macAddress))
                appliedHeadTrackingRequest = null
                headTrackingStartup.clear()
                gestureDetector?.stopDetection()
                audioProfileOwnership.select(macAddress)
                retireControlForChangedPeer()
                SleepTimerManager.restore(this)
                updateBatteryWidget()
                updateNoiseControlWidget()
            }
            "show_battery_history", "battery_history_days" -> updateBatteryWidget()
            "automatic_ear_detection" -> config.earDetectionEnabled =
                preferences.getBoolean(key, true)

            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic =
                preferences.getBoolean(key, false)

            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                updatePhoneBatteryReceiver()
                updateBatteryWidget()
            }

            "off_listening_mode", "off_listening_mode_address", "airpods_model_address" -> updateNoiseControlWidget()

            "relative_conversational_awareness_volume" -> config.relativeConversationalAwarenessVolume =
                preferences.getBoolean(key, true)

            "head_gestures" -> {
                config.headGestures = preferences.getBoolean(key, true)
                if (!config.headGestures) gestureDetector?.stopDetection()
            }
            "use_alternate_head_tracking_packets" -> applyHeadTrackingDesired(headTrackingSession.capture())
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing =
                preferences.getBoolean(key, false)

            "conversational_awareness_volume" -> config.conversationalAwarenessVolume =
                preferences.getInt(key, 43)

            "qs_click_behavior" -> config.qsClickBehavior =
                preferences.getString(key, "cycle") ?: "cycle"

            // AirPods state-based takeover
            "takeover_when_disconnected" -> {
                config.takeoverWhenDisconnected = preferences.getBoolean(key, false)
                if (!config.takeoverWhenDisconnected) audioProfileOwnership.cancelClaim()
            }

            "takeover_when_idle" -> config.takeoverWhenIdle = preferences.getBoolean(key, true)
            "takeover_when_music" -> config.takeoverWhenMusic = preferences.getBoolean(key, false)
            "takeover_when_call" -> config.takeoverWhenCall = preferences.getBoolean(key, true)

            // Phone state-based takeover
            "takeover_when_ringing_call" -> config.takeoverWhenRingingCall =
                preferences.getBoolean(key, true)

            "takeover_when_media_start" -> config.takeoverWhenMediaStart =
                preferences.getBoolean(key, true)

            "left_single_press_action" -> {
                config.leftSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                ) ?: StemAction.PLAY_PAUSE
                setupStemActions()
            }

            "right_single_press_action" -> {
                config.rightSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                ) ?: StemAction.PLAY_PAUSE
                setupStemActions()
            }

            "left_double_press_action" -> {
                config.leftDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                ) ?: StemAction.NEXT_TRACK
                setupStemActions()
            }

            "right_double_press_action" -> {
                config.rightDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                ) ?: StemAction.NEXT_TRACK
                setupStemActions()
            }

            "left_triple_press_action" -> {
                config.leftTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                ) ?: StemAction.PREVIOUS_TRACK
                setupStemActions()
            }

            "right_triple_press_action" -> {
                config.rightTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                ) ?: StemAction.PREVIOUS_TRACK
                setupStemActions()
            }

            "left_long_press_action" -> {
                config.leftLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES")
                        ?: "CYCLE_NOISE_CONTROL_MODES"
                ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES
                setupStemActions()
            }

            "right_long_press_action" -> {
                config.rightLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES") ?: "CYCLE_NOISE_CONTROL_MODES"
                ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES
                setupStemActions()
            }

            "camera_action" -> {
                config.cameraAction = cameraActionFromString(preferences.getString(key, null))
                cameraGeneration.incrementAndGet()
                setupStemActions()
            }

            // AirPods device information
            "airpods_name" -> config.airpodsName = preferences.getString(key, "") ?: ""
            "airpods_model_number" -> {
                config.airpodsModelNumber = preferences.getString(key, "") ?: ""
                updateNoiseControlWidget()
            }

            "airpods_manufacturer" -> config.airpodsManufacturer =
                preferences.getString(key, "") ?: ""

            "airpods_serial_number" -> config.airpodsSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_left_serial_number" -> config.airpodsLeftSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_right_serial_number" -> config.airpodsRightSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_version1" -> config.airpodsVersion1 = preferences.getString(key, "") ?: ""
            "airpods_version2" -> config.airpodsVersion2 = preferences.getString(key, "") ?: ""
            "airpods_version3" -> config.airpodsVersion3 = preferences.getString(key, "") ?: ""
            "airpods_hardware_revision" -> config.airpodsHardwareRevision =
                preferences.getString(key, "") ?: ""

            "airpods_updater_identifier" -> config.airpodsUpdaterIdentifier =
                preferences.getString(key, "") ?: ""

            "self_mac_address" -> config.selfMacAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, @Suppress("SameParameterValue") source: String) {
        val received = synchronized(inMemoryLogs) {
            // Formatting and history share this monitor; avoid a second per-packet lock.
            packetLogFormatter.format(packet, source)
            packetLogHistory.add(packetLogFormatter.entry)
            packetLogFormatter.received
        }
        // The immutable text survives a later formatter update; logging cannot hold up history.
        Log.d("AirPodsData", received)
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            packetLogFormatter.clear()
            packetLogHistory.clear()
        }
    }

    fun clearLogs() {
        if (serviceResources.isClosed) return
        clearPacketLogs()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    private var callNumber: String? = null

    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false
    private var popupWindow: PopupWindow? = null
    fun showPopup(service: Service, name: String) {
        if (serviceResources.isClosed) return
        if (!sharedPreferences.getBoolean("show_bottom_sheet_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            if (popupShown || serviceResources.isClosed) return@launch
            popupWindow?.close()
            val window = PopupWindow(service.applicationContext)
            popupWindow = window
            val model = airpodsInstance?.model
            window.open(name, batteryNotification, model?.connectionArtworkRes)
            popupShown = true
        }
    }

    var islandOpen = false
    var islandWindow: IslandWindow? = null

    @SuppressLint("MissingPermission")
    fun showIsland(
        service: Service,
        batteryPercentage: Int,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        Log.d(TAG, "Showing island window")
        if (!sharedPreferences.getBoolean("show_island_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            if (serviceResources.isClosed) return@launch
            islandWindow?.close()
            islandWindow = IslandWindow(service.applicationContext)
            islandWindow!!.show(
                sharedPreferences.getString("name", "AirPods Pro").toString(),
                batteryPercentage,
                this@AirPodsService,
                type,
                reversed,
                otherDeviceName,
                artworkRes = airpodsInstance?.model?.connectionArtworkRes
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    //    var isConnectedLocally = false
    @Volatile var device: BluetoothDevice? = null

    var widgetMobileBatteryEnabled = false
    private val phoneBatteryUpdates = PhoneBatteryUpdateGate()

    private val phoneBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED && !serviceResources.isClosed &&
                phoneBatteryUpdates.changed(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                    intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING)) {
                // A phone battery event does not change earbud metadata or notifications.
                updateBatteryWidget()
            }
        }
    }

    private fun updatePhoneBatteryReceiver() {
        if (widgetMobileBatteryEnabled && !phoneBatteryReceiverRegistered && !serviceResources.isClosed) {
            registerReceiver(phoneBatteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
            phoneBatteryReceiverRegistered = true
        } else if (!widgetMobileBatteryEnabled && phoneBatteryReceiverRegistered) {
            unregisterReceiver(phoneBatteryReceiver)
            phoneBatteryReceiverRegistered = false
            phoneBatteryUpdates.reset()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_NONE
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods BluetoothConnectionManager.aacpSocket? Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationSettingsIntent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
            }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods).setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("KotlinUnreachableCode")
    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        return // something causes too many notifications. turning off for now
        if (BuildConfig.FLAVOR != "xposed") {
            Log.w(
                TAG,
                "Not showing BluetoothConnectionManager.aacpSocket? error notification to user, the service shouldn't be running if it isn't supported."
            )
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods).setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP").setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. Error: $errorMessage"
                )
            ).setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
            setPackage(packageName)
        })
    }

    fun sendBatteryBroadcast() {
        broadcastBatteryInformation()
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
            setPackage(packageName)
        })
    }

    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            config.deviceName,
            batteryNotification.getBattery()
        )
    }

    fun setBatteryMetadata() {
        val target = device ?: return
        val snapshot = batteryNotification.getBattery()
        val values = linkedMapOf<Int, String>()
        for (battery in snapshot) {
            if (!battery.isAvailableReading()) continue
            val (levelKey, chargingKey) = when (battery.component) {
                BatteryComponent.CASE -> target.METADATA_UNTETHERED_CASE_BATTERY to target.METADATA_UNTETHERED_CASE_CHARGING
                BatteryComponent.LEFT -> target.METADATA_UNTETHERED_LEFT_BATTERY to target.METADATA_UNTETHERED_LEFT_CHARGING
                BatteryComponent.RIGHT -> target.METADATA_UNTETHERED_RIGHT_BATTERY to target.METADATA_UNTETHERED_RIGHT_CHARGING
                else -> continue
            }
            values[levelKey] = battery.level.toString()
            values[chargingKey] = if (battery.status == BatteryStatus.CHARGING ||
                battery.status == BatteryStatus.OPTIMIZED_CHARGING) "1" else "0"
        }
        requestMetadata(MetadataKind.BATTERY, target, values, batterySnapshot = snapshot)
    }

    fun updateBatteryWidget() {
        if (!serviceResources.isClosed) batteryWidgetUpdates.request()
    }

    @SuppressLint("MissingPermission")
    private fun publishBatteryWidgets() {
        me.kavishdevar.librepods.presentation.widgets.WidgetPublications.offer(
            me.kavishdevar.librepods.presentation.widgets.OfflineWidgetUpdates.Kind.BATTERY,
            valid = { !serviceResources.isClosed && ServiceManager.getService() === this },
            publish = ::publishBatteryWidgetsSerial)
    }

    @SuppressLint("MissingPermission")
    private fun publishBatteryWidgetsSerial() {
        if (serviceResources.isClosed || ServiceManager.getService()?.let { it !== this } == true) return
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, BatteryWidget::class.java))
        if (ids.isEmpty()) return
        val socket = BluetoothConnectionManager.aacpSocket
        val selectedAddress = sharedPreferences.getString("mac_address", "") ?: ""
        val aacpAvailable = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            socket?.isConnected == true && socket.remoteDevice.address.equals(selectedAddress, ignoreCase = true)
        val status = (if (::bleManager.isInitialized) bleManager.getMostRecentStatus() else null)?.takeIf {
            it.ownerAddress == null || it.ownerAddress.equals(selectedAddress, ignoreCase = true)
        }
        val live = if (aacpAvailable) batteryNotification.getBattery() else if (status != null) listOf(
            Battery(BatteryComponent.LEFT, status.leftBattery ?: -1, if (status.isLeftCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, status.rightBattery ?: -1, if (status.isRightCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, status.caseBattery ?: -1, if (status.isCaseCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING)
        ) else emptyList()
        val available = aacpAvailable || status != null
        val history = batteryHistory.loadedSnapshot()
        for (id in ids) {
            if (serviceResources.isClosed || ServiceManager.getService()?.let { it !== this } == true) return
            val views = BatteryWidget.createViews(this, id, live, available, history)
            if (!selectedAddress.equals(sharedPreferences.getString("mac_address", ""), ignoreCase = true) ||
                (aacpAvailable && (socket !== BluetoothConnectionManager.aacpSocket || !socket.isConnected)) ||
                serviceResources.isClosed || ServiceManager.getService()?.let { it !== this } == true) return
            manager.updateAppWidget(id, views)
        }
    }

    private fun recordBleBatteryHistory(status: BLEManager.AirPodsStatus) {
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
        val address = sharedPreferences.getString("mac_address", "") ?: ""
        if (status.ownerAddress?.equals(address, ignoreCase = true) == true &&
            canRecordBleBattery(address, sharedPreferences.getString("IRK_owner", null),
                sharedPreferences.getString("ENC_KEY", null) != null, sharedPreferences.getString("ENC_KEY_owner", null))) {
            batteryHistory.record(address, listOf(
                Battery(BatteryComponent.LEFT, status.leftBattery ?: -1, if (status.isLeftCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, status.rightBattery ?: -1, if (status.isRightCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, status.caseBattery ?: -1, if (status.isCaseCharging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING)
            ))
        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    @Synchronized
    fun updateBattery(force: Boolean = true) {
        val snapshot = device?.address to batteryNotification.getBattery().toList()
        if (!force && snapshot == lastBatterySnapshot) return
        setBatteryMetadata()
        updateBatteryWidget()
        sendBatteryBroadcast()
        sendBatteryNotification()
        lastBatterySnapshot = snapshot
    }

    fun updateNoiseControlWidget() { noiseWidgetUpdates.request() }

    private fun publishNoiseControlWidget() {
        me.kavishdevar.librepods.presentation.widgets.WidgetPublications.offer(
            me.kavishdevar.librepods.presentation.widgets.OfflineWidgetUpdates.Kind.NOISE,
            valid = { !serviceResources.isClosed && ServiceManager.getService() === this },
            publish = ::publishNoiseControlWidgetSerial)
    }

    private fun publishNoiseControlWidgetSerial() {
        if (serviceResources.isClosed || ServiceManager.getService() !== this) return
        val selected = sharedPreferences.getString("mac_address", "")
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, NoiseControlWidget::class.java))
        if (ids.isEmpty()) return
        val modes = supportedListeningModes()
        val views = NoiseControlWidget.createViews(this, modes)
        val buttons = mapOf(1 to R.id.widget_off_button, 3 to R.id.widget_transparency_button,
            4 to R.id.widget_adaptive_button, 2 to R.id.widget_anc_button)
        modes.forEachIndexed { index, mode ->
            val checked = ancNotification.status == mode
            val shape = when (index) {
                0 -> if (checked) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
                modes.lastIndex -> if (checked) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
                else -> if (checked) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            }
            val button = buttons.getValue(mode)
            views.setInt(button, "setBackgroundResource", shape)
            views.setViewLayoutMargin(button, RemoteViews.MARGIN_START, if (index == 0) 12f else 2f, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(button, RemoteViews.MARGIN_END, if (index == modes.lastIndex) 12f else 2f, TypedValue.COMPLEX_UNIT_DIP)
        }
        if (serviceResources.isClosed || ServiceManager.getService() !== this ||
            selected != sharedPreferences.getString("mac_address", "")) return
        manager.updateAppWidget(ids, views)
    }

    fun isOffListeningModeEnabled(): Boolean = offListeningModeEnabled(sharedPreferences.all)

    @SuppressLint("MissingPermission")
    private fun offListeningModeEnabled(values: Map<String, *>): Boolean {
        val socket = BluetoothConnectionManager.aacpSocket
        val value = if (socket != null && isCurrentControlSocket(socket) &&
            socket.remoteDevice.address.equals(values["mac_address"] as? String, ignoreCase = true))
            aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION)?.value else null
        return me.kavishdevar.librepods.data.isOffListeningModeAllowed(value,
            me.kavishdevar.librepods.data.cachedOffListeningMode(values))
    }

    fun supportedListeningModes(): List<Int> {
        if (serviceResources.isClosed) return emptyList()
        val values = sharedPreferences.all
        return me.kavishdevar.librepods.data.cachedListeningModes(values, offListeningModeEnabled(values))
    }

    fun requestConnectionNotificationRefresh() {
        if (!serviceResources.isClosed) notificationRefreshes.request()
    }

    @Synchronized
    private fun refreshConnectionNotification() {
        if (serviceResources.isClosed || ServiceManager.getService() !== this) return
        val socket = BluetoothConnectionManager.aacpSocket ?: return
        if (!isCurrentControlSocket(socket)) return
        // Returning from notification settings must re-check delivery even with unchanged data.
        notificationGeneration.incrementAndGet()
        sendBatteryNotification()
    }

    @Synchronized
    fun updateNotificationContent(
        connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null
    ) {
        if (serviceResources.isClosed || ServiceManager.getService() !== this) return
        if (!connected) {
            headTrackingStartup.clear()
            synchronized(this) {
                notificationGeneration.incrementAndGet()
                takeoverGeneration.incrementAndGet()
                resetConversation()
                audioChargingState.reset()
                batteryNotification.invalidate()
                audioProfileOwnership.socketClosed()
                serviceScope.launch(Dispatchers.Main.immediate) { clearStaleA2dpConnectionReceiver() }
                lastBatterySnapshot = null
                if (::batteryHistory.isInitialized) { batteryHistory.flush(); updateBatteryWidget() }
            }
            notificationLease?.clear()
            return
        }
        val socket = BluetoothConnectionManager.aacpSocket ?: return
        if (!isCurrentControlSocket(socket)) return
        val peer = me.kavishdevar.librepods.data.batteryHistoryIdentity(socket.remoteDevice.address) ?: return
        val notice = ConnectionNotice(airpodsName ?: config.deviceName, batteryList?.toList().orEmpty(),
            disconnectedBecauseReversed, peer, notificationGeneration.get(), aacpManager.captureDeviceSession(), socket)
        notificationLease?.offer(notice) {
            !serviceResources.isClosed && ServiceManager.getService() === this &&
                notice.selection == notificationGeneration.get() && aacpManager.isCurrentDeviceSession(notice.deviceSession) &&
                isCurrentControlSocket(notice.socket) && notice.peer == me.kavishdevar.librepods.data.batteryHistoryIdentity(
                    sharedPreferences.getString("mac_address", "") ?: "")
        }
    }

    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                    handleIncomingCallOnceConnected = false
                } else {
                    rejectCall()
                    handleIncomingCallOnceConnected = false
                }
            }

        }
    }

    suspend fun testHeadGestures(): Boolean? {
        initGestureDetector()
        val detector = gestureDetector ?: return null
        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val completed = AtomicBoolean(false)
                fun finish(result: Boolean?) {
                    if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
                }
                val detection = detector.startDetection(onStopped = { finish(null) }) { accepted -> finish(accepted) }
                if (detection == null) {
                    finish(null)
                } else continuation.invokeOnCancellation {
                    completed.set(true)
                    detection.close()
                }
            }
        }
    }

    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod =
                    telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val motion = me.kavishdevar.librepods.bluetooth.RtBuddyHeadTracking.motion(data) ?: return
        val horizontal = motion.horizontal
        val vertical = motion.vertical
        try {
            gestureDetector?.processHeadOrientation(horizontal, vertical)
        } catch (e: Exception) {
            Log.w(TAG, "gesture detector on ${data.toHexString()}: ${e.message}")
        }
    }

    private lateinit var connectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId)).build()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1

    @Suppress("PrivatePropertyName")
    private val APPLE = 0x004C

    @Suppress("PrivatePropertyName")
    private val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    @Suppress("PrivatePropertyName")
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @Suppress("PrivatePropertyName")
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"

    @Suppress("PrivatePropertyName")
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @SuppressLint("MissingPermission")
    fun broadcastBatteryInformation() {
        if (device == null || checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") != PackageManager.PERMISSION_GRANTED) return

        val batteryList = batteryNotification.getBattery()
        val batteryUnified = systemHeadsetBatteryLevel(batteryList) ?: return
        val vendorIndicator = appleHeadsetBatteryIndicator(batteryUnified)

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            vendorIndicator ?: 0 // Only sent when representable by Apple's coarse indicator.
        )

        // Broadcast vendor-specific event
        val intent = Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD,
                VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV
            )
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                BluetoothHeadset.AT_CMD_TYPE_SET
            )
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        if (vendorIndicator != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sendBroadcastAsUser(
                        intent,
                        UserHandle.getUserHandleForUid(-1),
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send vendor-specific event: ${e.message}")
            }
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, batteryIntent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d(TAG, "Broadcast battery level $batteryUnified% to system")
    }

    private fun hasMetadataPermission(): Boolean =
        checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun requestMetadata(kind: MetadataKind, target: BluetoothDevice, values: Map<Int, String>,
                                modelNumber: String? = null, batterySnapshot: List<Battery>? = null) {
        if (serviceResources.isClosed || !::sharedPreferences.isInitialized || !hasMetadataPermission() || values.isEmpty()) return
        val peer = me.kavishdevar.librepods.data.batteryHistoryIdentity(target.address) ?: return
        if (peer != me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "")) return
        val request = MetadataRequest(kind, target,
            MetadataScope(peer, metadataSelection.get(), aacpManager.captureDeviceSession()), values.toMap(), modelNumber, batterySnapshot?.toList())
        synchronized(metadataBindingLock) {
            // Keep publication and queue replacement ordered across callback threads.
            if (kind == MetadataKind.INFORMATION) desiredInformationMetadata = request else desiredBatteryMetadata = request
            metadataUpdates.offer(kind, request)
        }
    }

    private fun metadataRequestCurrent(request: MetadataRequest): Boolean =
        !serviceResources.isClosed && !metadataUpdates.isClosed && ServiceManager.getService() === this &&
            request.scope.selection == metadataSelection.get() &&
            aacpManager.isCurrentDeviceSession(request.scope.deviceSession) &&
            request.scope.peer == me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "") &&
            (if (request.kind == MetadataKind.INFORMATION) desiredInformationMetadata else desiredBatteryMetadata) === request &&
            (request.batterySnapshot == null || request.batterySnapshot == batteryNotification.getBattery()) &&
            (request.modelNumber == null || cachedInformation?.let {
                it.owner == request.scope.peer && it.instance.actualModelNumber == request.modelNumber
            } == true)

    private fun publishMetadata(request: MetadataRequest) {
        val target = request.target
        val writer = metadataWriter ?: MetadataValueWriter<MetadataScope>(setOf(
            target.METADATA_MAIN_ICON, target.METADATA_MODEL_NAME, target.METADATA_DEVICE_TYPE,
            target.METADATA_UNTETHERED_CASE_ICON, target.METADATA_UNTETHERED_RIGHT_ICON, target.METADATA_UNTETHERED_LEFT_ICON,
            target.METADATA_MANUFACTURER_NAME, target.METADATA_COMPANION_APP,
            target.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD, target.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
            target.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
            target.METADATA_UNTETHERED_CASE_BATTERY, target.METADATA_UNTETHERED_CASE_CHARGING,
            target.METADATA_UNTETHERED_LEFT_BATTERY, target.METADATA_UNTETHERED_LEFT_CHARGING,
            target.METADATA_UNTETHERED_RIGHT_BATTERY, target.METADATA_UNTETHERED_RIGHT_CHARGING)).also { metadataWriter = it }
        writer.write(request.scope, request.values,
            current = { metadataRequestCurrent(request) && hasMetadataPermission() },
            onError = { Log.w(TAG, "Metadata field update failed", it) }) { key, value ->
            SystemApisUtils.setMetadata(request.target, key, value.toByteArray(Charsets.UTF_8))
        }
    }

    @SuppressLint("MissingPermission")
    private fun setMetadatas(target: BluetoothDevice) {
        if (!hasMetadataPermission()) return
        val information = cachedInformation?.takeIf {
            it.owner == me.kavishdevar.librepods.data.batteryHistoryIdentity(target.address) &&
                it.owner == me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "")
        } ?: return
        val model = information.instance.model
        val values = linkedMapOf(
            target.METADATA_MODEL_NAME to model.name,
            target.METADATA_DEVICE_TYPE to target.DEVICE_TYPE_UNTETHERED_HEADSET,
            target.METADATA_MANUFACTURER_NAME to model.manufacturer,
            target.METADATA_COMPANION_APP to packageName,
            target.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD to "20",
            target.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD to "20",
            target.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD to "20")
        listOf(target.METADATA_MAIN_ICON to model.budCaseRes, target.METADATA_UNTETHERED_CASE_ICON to model.caseRes,
            target.METADATA_UNTETHERED_RIGHT_ICON to model.rightBudsRes, target.METADATA_UNTETHERED_LEFT_ICON to model.leftBudsRes)
            .forEach { (key, resource) -> resToUri(resource)?.let { values[key] = it.toString() } }
        requestMetadata(MetadataKind.INFORMATION, target, values, information.instance.actualModelNumber)
    }

    @Suppress("ClassName")
    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val target = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val action = intent.action ?: return
            if (serviceResources.isClosed) return
            when (action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        val profile = if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) BluetoothProfile.A2DP else BluetoothProfile.HEADSET
                        if (isKnownAirPods(target)) {
                            val changed = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1) != BluetoothProfile.STATE_CONNECTED
                            attachControlIfAudioConnected(target, freshProfile = profile.takeIf { changed })
                        } else if (target.uuids == null) {
                            withAudioProfile(this@AirPodsService, profile, AudioProfileAction.SDP) { proxy ->
                                if (proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED && isCurrentProfileAction()) target.fetchUuidsWithSdp()
                            }
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_UUID -> {
                    if (isKnownAirPods(target)) attachControlIfAudioConnected(target)
                    // Avoid proactively creating an SDP/ACL link to idle paired devices.
                }
            }
        }
    }

    val externalBroadcastFilter = IntentFilter().apply {
        addAction("me.kavishdevar.librepods.SET_ANC_MODE")
        addAction("me.kavishdevar.librepods.CONVO_DETECT")
    }
    var externalBroadcastReceiver: BroadcastReceiver? = null

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent action: ${intent?.action}")

        if (intent?.action == SleepTimerManager.ACTION_APPLY_MODE) {
            val token = intent.getStringExtra(SleepTimerManager.EXTRA_TOKEN)
            if (token != null && SleepTimerManager.ownsToken(this, token)) requestSleepTimerDelivery()
        }

        if (intent?.action == "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE") {
            Log.d(TAG, "reconnect after reversed received, taking over")
            takeOver("music", manualTakeOverAfterReversed = true)
        }

        return START_STICKY
    }

    fun setListeningMode(mode: Int) {
        setListeningModeAsync(mode)
    }

    fun requestSleepTimerDelivery() {
        sleepTimerDeliveries.request()
    }

    private fun deliverSleepTimer() {
        val record = SleepTimerManager.prepareDelivery(this) ?: return
        val socket = BluetoothConnectionManager.aacpSocket ?: return
        if (!isCurrentControlSocket(socket) || !record.matches(macAddress)) return
        val packet = aacpManager.createDataPacket(aacpManager.createControlCommandPacket(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            byteArrayOf(record.mode.toByte())))
        val written = aacpManager.sendPacket(packet, socket) {
            ServiceManager.getService() === this@AirPodsService && isCurrentControlSocket(socket) &&
                SleepTimerManager.canDeliver(this, record, macAddress)
        }
        if (written) SleepTimerManager.complete(this, record.token)
    }

    fun setListeningModeAsync(mode: Int, onComplete: (() -> Unit)? = null) {
        enqueueInteractiveCommand(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            byteArrayOf(mode.toByte()), onComplete = onComplete, valid = { mode in 1..4 })
    }

    internal fun canRequestListeningMode(mode: Int): Boolean {
        val socket = BluetoothConnectionManager.aacpSocket
        return mode in 1..4 && socket != null && isCurrentControlSocket(socket) &&
            ServiceManager.getService() === this
    }

    internal fun enqueueInteractiveCommand(identifier: Byte, value: ByteArray,
        valid: () -> Boolean = { true }, onComplete: (() -> Unit)? = null): Boolean {
        val socket = BluetoothConnectionManager.aacpSocket
        val legalValue = value.size == 1 && when (identifier) {
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value -> value[0].toInt() in 1..4
            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value -> value[0].toInt() in 1..2
            AACPManager.Companion.ControlCommandIdentifiers.STEM_CONFIG.value -> value[0].toInt() in 0..15
            else -> false
        }
        if (!legalValue || socket == null || !isCurrentControlSocket(socket) ||
            ServiceManager.getService() !== this || !valid()) {
            runCatching { onComplete?.invoke() }.onFailure { Log.w(TAG, "Command completion failed", it) }
            return false
        }
        val session = aacpManager.captureDeviceSession()
        val packet = aacpManager.createDataPacket(aacpManager.createControlCommandPacket(
            identifier, value.copyOf()))
        return interactiveCommands.offer(identifier, InteractiveCommand(packet, socket, session), valid) { onComplete?.invoke() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun takeOver(
        takingOverFor: String, manualTakeOverAfterReversed: Boolean = false,
        startHeadTrackingAgain: Boolean = false
    ) {
        if (takingOverFor !in setOf("music", "call", "reverse") || serviceResources.isClosed) return
        val action = when {
            takingOverFor == "reverse" -> TakeoverAction.REVERSE
            manualTakeOverAfterReversed -> TakeoverAction.MANUAL
            startHeadTrackingAgain -> TakeoverAction.HEAD_TRACKING
            takingOverFor == "call" -> TakeoverAction.CALL
            else -> TakeoverAction.MUSIC
        }
        val peer = me.kavishdevar.librepods.data.batteryHistoryIdentity(
            sharedPreferences.getString("mac_address", "") ?: "") ?: return
        val socket = BluetoothConnectionManager.aacpSocket
        val request = TakeoverRequest(action, takingOverFor, peer, takeoverGeneration.get(),
            socket?.let { aacpManager.captureDeviceSession() }, socket,
            me.kavishdevar.librepods.data.batteryHistoryIdentity(localMac),
            manualTakeOverAfterReversed, startHeadTrackingAgain,
            if (startHeadTrackingAgain) headTrackingSession.capture().id else null)
        if (takeoverEnabled(request)) takeoverRequests.offer(action, request)
    }

    private fun takeoverEnabled(request: TakeoverRequest): Boolean = request.explicit || when (request.reason) {
        "music" -> config.takeoverWhenMediaStart
        "call" -> config.takeoverWhenRingingCall
        else -> false
    }

    private fun takeoverCurrent(request: TakeoverRequest, socket: BluetoothSocket? = request.socket): Boolean =
        !takeoverRequests.isClosed && !serviceResources.isClosed && ServiceManager.getService() === this &&
            request.generation == takeoverGeneration.get() && takeoverEnabled(request) &&
            (request.headGeneration == null || headTrackingSession.currentStart(request.headGeneration) != null) &&
            (request.deviceSession == null || aacpManager.isCurrentDeviceSession(request.deviceSession)) &&
            request.peer == me.kavishdevar.librepods.data.batteryHistoryIdentity(
                sharedPreferences.getString("mac_address", "") ?: "") &&
            if (socket != null) isCurrentControlSocket(socket) else
                request.socket == null && BluetoothConnectionManager.aacpSocket?.let {
                    !it.isConnected || isCurrentControlSocket(it)
                } != false

    @SuppressLint("MissingPermission")
    private fun performTakeover(request: TakeoverRequest) {
        if (!takeoverCurrent(request)) return
        // A request which had no control link may use a newly attached link for the same peer.
        // A request with a link always retains that exact link and cannot be rerouted.
        val socket = request.socket ?: BluetoothConnectionManager.aacpSocket?.takeIf(::isCurrentControlSocket)
        if (socket != null) {
            val self = request.selfMac ?: me.kavishdevar.librepods.data.batteryHistoryIdentity(localMac) ?: return
            val deviceSession = request.deviceSession ?: aacpManager.captureDeviceSession()
            val current = { takeoverCurrent(request, socket) && aacpManager.isCurrentDeviceSession(deviceSession) &&
                self == me.kavishdevar.librepods.data.batteryHistoryIdentity(localMac) }
            val owns = aacpManager.getControlCommandStatus(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value?.firstOrNull()?.toInt()
            val reverse = request.reason == "reverse"
            if (!reverse) {
                if (!XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false) ||
                    (!request.explicit && owns == 0)) return
                val source = aacpManager.audioSource
                if (owns == 1 && (source?.mac.equals(self, ignoreCase = true) ||
                        source?.type == AACPManager.Companion.AudioSourceType.NONE)) return
                if (disconnectedBecauseReversed && !request.manual) return
            }
            if (!current() || !aacpManager.sendTakeoverPackets(self, reverse, socket, current) || !current()) return
            val media = connectAudioForTakeover(request, socket.remoteDevice, socket, sourceCurrent = current) ?: return
            if (!current()) return
            otherDeviceTookOver = false
            if (request.manual || reverse) {
                disconnectedBecauseReversed = false
                sendBatteryNotification()
            }
            showTakeoverIsland(request, socket, IslandType.CONNECTED, deviceSession)
            scheduleTakeoverResume(request, socket, media, deviceSession)
            return
        }

        val status = bleManager.getMostRecentStatus()
        if (!request.explicit) {
            if (status?.isLeftInEar == false && status.isRightInEar == false) return
            val allowed = when (status?.connectionState) {
                "Disconnected" -> config.takeoverWhenDisconnected
                "Idle" -> config.takeoverWhenIdle
                "Music" -> config.takeoverWhenMusic
                "Call", "Ringing", "Hanging Up" -> config.takeoverWhenCall
                else -> false
            }
            if (!allowed) return
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
        val target = adapter.bondedDevices.firstOrNull { it.address.equals(request.peer, ignoreCase = true) } ?: return
        if (!takeoverCurrent(request)) return
        if (config.bleOnlyMode) {
            updateNotificationContent(true, config.deviceName, batteryNotification.getBattery())
        } else {
            val media = connectAudioForTakeover(request, target, null) {
                device = target
                if (request.reason == "music") {
                    MediaController.pausedWhileTakingOver = true
                    MediaController.sendPause(true)
                } else if (request.reason == "call" && !request.headTracking) handleIncomingCallOnceConnected = true
            } ?: return
            if (!takeoverCurrent(request) || !audioProfileOwnership.isCurrentMedia(media)) return
            // The primary connection timeout must not stall the routing request queue.
            serviceScope.launch { if (takeoverCurrent(request)) connectToSocket(adapter, target) }
        }
        showTakeoverIsland(request, null, IslandType.TAKING_OVER)
    }

    private fun showTakeoverIsland(
        request: TakeoverRequest, socket: BluetoothSocket?, type: IslandType, deviceSession: Long? = request.deviceSession
    ) {
        serviceScope.launch(Dispatchers.Main.immediate) {
            if (!takeoverCurrent(request, socket)) return@launch
            if (deviceSession != null && !aacpManager.isCurrentDeviceSession(deviceSession)) return@launch
            if (type == IslandType.TAKING_OVER && BluetoothConnectionManager.aacpSocket?.isConnected == true) return@launch
            showIsland(this@AirPodsService, takeoverBatteryLevel(), type)
        }
    }

    private fun takeoverBatteryLevel(): Int {
        val batteries = batteryNotification.getBattery()
        return (batteries.firstOrNull { it.component == BatteryComponent.LEFT }?.level ?: 0)
            .coerceAtMost(batteries.firstOrNull { it.component == BatteryComponent.RIGHT }?.level ?: 0)
    }

    private fun scheduleTakeoverResume(
        request: TakeoverRequest, socket: BluetoothSocket, media: AudioProfileOwnership.MediaTicket, deviceSession: Long
    ) {
        if (request.reason != "music" && !request.headTracking) return
        fun current() = takeoverCurrent(request, socket) && aacpManager.isCurrentDeviceSession(deviceSession) &&
            mediaOperationCurrent(socket, media)
        serviceScope.launch {
            delay(500)
            if (!current()) return@launch
            if (request.reason == "music") MediaController.sendPlay(replayWhenPaused = true)
            else if (request.headTracking) launch(Dispatchers.Main) {
                delay(500)
                val ticket = request.headGeneration?.let(headTrackingSession::currentStart)
                if (current() && ticket != null) enqueueHeadTrackingCommand(ticket, restart = true)
            }
            delay(1000)
            if (current() && request.reason == "music") MediaController.sendPlay(force = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAudioForTakeover(
        request: TakeoverRequest, target: BluetoothDevice, socket: BluetoothSocket?,
        sourceCurrent: () -> Boolean = { true }, beforeConnect: () -> Unit = {}
    ): AudioProfileOwnership.MediaTicket? {
        val selection = audioProfileOwnership.captureSelection(target.address) ?: return null
        val valid = { takeoverCurrent(request, socket) && sourceCurrent() }
        val ticket = audioProfileOwnership.beginExplicitConnectIfCurrent(selection, valid) ?: return null
        if (!valid()) return null
        beforeConnect()
        serviceScope.launch(Dispatchers.Main.immediate) { clearStaleA2dpConnectionReceiver() }
        connectAudioProfiles(this, target, ticket, socket, playWhenConnected = false, canConnect = valid)
        return audioProfileOwnership.captureMedia(target.address)
    }

    @SuppressLint("MissingPermission")
    private fun isKnownAirPods(target: BluetoothDevice): Boolean =
        target.address.equals(macAddress, ignoreCase = true) ||
            target.uuids?.contains(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")) == true

    @SuppressLint("MissingPermission")
    private fun attachControlIfAudioConnected(target: BluetoothDevice, freshProfile: Int? = null) {
        if (freshProfile == null && audioProfileOwnership.hasReleased(target.address)) {
            attachControlDevice(target)
            return
        }
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            withAudioProfile(this, profile, AudioProfileAction.ATTACH) { proxy ->
                if (proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED) {
                    if (freshProfile == profile && isCurrentProfileAction()) audioProfileOwnership.localProfileConnected(target.address, profile)
                    attachControlDevice(target)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachControlDevice(target: BluetoothDevice) {
        val current = profileActionCurrent.get() ?: { !serviceResources.isClosed }
        if (!current()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            serviceScope.launch(Dispatchers.Main.immediate) { if (current()) attachControlDevice(target) }
            return
        }
        if (serviceResources.isClosed || config.bleOnlyMode) return
        val socket = BluetoothConnectionManager.aacpSocket
        if (socket?.isConnected == true) return
        if (connectionInProgress.get() && device?.address != target.address) return
        device = target
        if (config.deviceName == "AirPods" && target.name != null) {
            config.deviceName = target.name ?: "AirPods"
            sharedPreferences.edit { putString("name", config.deviceName) }
        }
        macAddress = target.address
        audioProfileOwnership.select(macAddress)
        sharedPreferences.edit { putString("mac_address", macAddress) }
        setMetadatas(target)
        serviceScope.launch { connectToSocket(getSystemService(BluetoothManager::class.java).adapter, target) }
    }

    private val connectionInProgress = AtomicBoolean(false)

    internal fun shouldOpenAttChannel(model: AirPodsBase?): Boolean =
        // Known models without hearing features need neither ATT nor its framework lookup.
        shouldConnectAtt(model, true) &&
            XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false)

    fun connectToSocket(
        adapter: BluetoothAdapter, device: BluetoothDevice, manual: Boolean = false
    ) {
        if (serviceResources.isClosed || !connectionInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Connection already in progress or service stopped; ignoring request")
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            serviceScope.launch {
                connectToSocketOnce(adapter, device, manual)
            }.invokeOnCompletion { connectionInProgress.set(false) }
            return
        }
        try {
            connectToSocketOnce(adapter, device, manual)
        } finally {
            connectionInProgress.set(false)
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun connectToSocketOnce(
        adapter: BluetoothAdapter, device: BluetoothDevice, manual: Boolean
    ) {
        if (serviceResources.isClosed || BluetoothConnectionManager.aacpSocket?.isConnected == true) return
        // This method only runs off the main thread. Preserve MAC-before-handshake ordering,
        // while a denied root prompt cannot delay foreground-service startup or block forever.
        localMacLookupFinished.await(5500, TimeUnit.MILLISECONDS)
        if (serviceResources.isClosed || !device.address.equals(macAddress, ignoreCase = true)) return
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")

        val attempt = serviceResources.createChildScope()
        var socket: BluetoothSocket? = null
        val pendingAttSocket = java.util.concurrent.atomic.AtomicReference<BluetoothSocket?>()
        var handedToReader = false
        var packetGeneration = -1L
        val connectionPhaseFinished = AtomicBoolean(false)
        var timeoutJob: kotlinx.coroutines.Job? = null

        fun closeAttempt(notifyDisconnect: Boolean = false) {
            // Potentially blocking close stays outside the publication lock.
            attempt.close()
            serviceResources.release(attempt)
            pendingAttSocket.get()?.let { attManager.stopReader(it) }
            ownedControlSocket.compareAndSet(socket, null)
            pendingAttSocket.get()?.let { ownedAttSocket.compareAndSet(it, null) }
            synchronized(BluetoothConnectionManager) {
                if (socket != null && BluetoothConnectionManager.aacpSocket === socket) {
                    BluetoothConnectionManager.aacpSocket = null
                    if (notifyDisconnect && !serviceResources.isClosed) {
                        // Check ownership and clear shared state atomically with publication.
                        // A late old reader must never clear a replacement connection's state.
                        aacpManager.disconnected()
                        updateNotificationContent(false)
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                            setPackage(packageName)
                        })
                    }
                }
                if (pendingAttSocket.get() != null && BluetoothConnectionManager.attSocket === pendingAttSocket.get()) {
                    BluetoothConnectionManager.attSocket = null
                }
            }
        }

        fun isCurrentConnection(): Boolean =
            !serviceResources.isClosed && !attempt.isClosed &&
                BluetoothConnectionManager.aacpSocket === socket

        try {
            serviceResources.track(attempt)
            timeoutJob = serviceScope.launch {
                delay(5000)
                if (connectionPhaseFinished.compareAndSet(false, true)) {
                    // BluetoothSocket.connect() is blocking. Closing it from another worker
                    // aborts the call; a coroutine timeout alone cannot interrupt it.
                    attempt.close()
                }
            }
            val connectedSocket = attempt.track(createBluetoothSocket(
                adapter, device, ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"), 4097
            ))
            socket = connectedSocket
            connectedSocket.connect()
            attempt.ensureOpen()

            if (!connectionPhaseFinished.compareAndSet(false, true)) {
                throw IOException("Bluetooth socket connection timed out")
            }
            timeoutJob.cancel()
            attempt.ensureOpen()

            // Publish AACP without waiting for the optional hearing-feature channel.
            serviceResources.whileOpen {
                synchronized(BluetoothConnectionManager) {
                    attempt.ensureOpen()
                    if (!device.address.equals(sharedPreferences.getString("mac_address", ""), ignoreCase = true)) throw CancellationException("Selected AirPods changed")
                    batteryNotification.invalidate()
                    packetGeneration = aacpManager.beginDeviceSession()
                    BluetoothConnectionManager.aacpSocket = connectedSocket
                    BluetoothConnectionManager.attSocket = null
                    ownedControlSocket.set(connectedSocket)
                    ownedAttSocket.set(null)
                    this@AirPodsService.device = device
                }
            }
            attempt.ensureOpen()
            serviceResources.ensureOpen()

            refreshCachedInformation()
            setMetadatas(device)
            if (!isCurrentConnection()) return
            updateNotificationContent(true, config.deviceName, batteryNotification.getBattery())
            Log.d(TAG, "<LogCollector:Complete:Success> Socket connected")
            sharedPreferences.edit {
                putBoolean("connection_successful", true)
                if (!sharedPreferences.contains("first_connection_successful_time")) {
                    putLong("first_connection_successful_time", System.currentTimeMillis())
                }
            }
            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_L2CAP_CONNECTED).apply {
                setPackage(packageName)
            })
            aacpManager.sendPacket(aacpManager.createHandshakePacket())
            aacpManager.sendSetFeatureFlagsPacket()
            aacpManager.sendNotificationRequest()
            requestSleepTimerDelivery()
            val bothKeys = (AACPManager.Companion.ProximityKeyType.IRK.value +
                AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte()
            aacpManager.sendRequestProximityKeys(bothKeys)

            val readerJob = serviceScope.launch {
                var startupSequence: Job? = null
                try {
                    // Drain incoming replies immediately while the spaced startup requests run.
                    startupSequence = launch startup@ {
                        delay(200)
                        if (!isCurrentConnection()) return@startup
                        aacpManager.sendPacket(aacpManager.createHandshakePacket())
                        delay(200)
                        if (!isCurrentConnection()) return@startup
                        aacpManager.sendSetFeatureFlagsPacket()
                        delay(200)
                        if (!isCurrentConnection()) return@startup
                        aacpManager.sendNotificationRequest()
                        delay(200)
                        if (!isCurrentConnection()) return@startup
                        aacpManager.sendSomePacketIDontKnowWhatItIs()
                        aacpManager.sendPacket(me.kavishdevar.librepods.bluetooth.RtBuddyHeadTracking.discoveryPacket())
                        delay(200)
                        if (!isCurrentConnection()) return@startup
                        aacpManager.sendRequestProximityKeys(bothKeys)
                        // Resume only an explicitly requested session for this exact peer. Settings
                        // discovery alone never enables head tracking or claims audio ownership.
                        val head = headTrackingSession.capture()
                        if (head.enabled && head.peer.equals(device.address, true)) enqueueHeadTrackingCommand(head)
                        if (handleIncomingCallOnceConnected) handleIncomingCall()
                        launch handshake@ {
                            delay(5000)
                            if (!isCurrentConnection()) return@handshake
                            aacpManager.sendPacket(aacpManager.createHandshakePacket())
                            aacpManager.sendSetFeatureFlagsPacket()
                            aacpManager.sendNotificationRequest()
                            aacpManager.sendRequestProximityKeys(AACPManager.Companion.ProximityKeyType.IRK.value)
                        }
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_CONNECTED).putExtra("device", device).apply {
                            setPackage(packageName)
                        })
                        setupStemActions()
                    }

                    val buffer = ByteArray(1024)
                    while (isActive && isCurrentConnection() && connectedSocket.isConnected) {
                        val bytesRead = connectedSocket.inputStream.read(buffer)
                        if (bytesRead == -1) break
                        if (bytesRead > 0 && isCurrentConnection()) {
                            val data = buffer.copyOfRange(0, bytesRead)
                            // AACP dispatches typed state callbacks; the former package-only
                            // raw-data broadcast had no consumers and caused IPC per sample.
                            // Notifications change on connection/name/battery events, not sensor samples.
                            packetSourceSocket.set(connectedSocket)
                            try {
                                aacpManager.receivePacket(data, packetGeneration) {
                                    isCurrentConnection() && isCurrentControlSocket(connectedSocket)
                                }
                            } finally { packetSourceSocket.remove() }
                            if (!isHeadTrackingData(data)) logPacket(data, "AirPods")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isCurrentConnection()) Log.w(TAG, "Error reading Bluetooth data", e)
                } finally {
                    startupSequence?.cancel()
                    closeAttempt(notifyDisconnect = true)
                }
            }
            // Also covers cancellation before the coroutine body ever starts.
            readerJob.invokeOnCompletion { closeAttempt() }
            handedToReader = true

            serviceScope.launch {
                // Unknown models get a short discovery window. AACP is already receiving
                // device information, so an AirPods 5 reply can avoid opening ATT at all.
                if (airpodsInstance == null) delay(1000)
                if (!isCurrentConnection() || !isCurrentControlSocket(connectedSocket)) return@launch
                val model = airpodsInstance?.model
                if (!shouldOpenAttChannel(model)) return@launch
                val attSocket = connectOptionalChannel(attempt, timeoutMillis = 5000,
                    connect = { resources ->
                        resources.track(createBluetoothSocket(adapter, device,
                            ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"), 31)).also { it.connect() }
                    },
                    publish = { attached ->
                        synchronized(BluetoothConnectionManager) {
                            if (!isActive || !isCurrentConnection() || !isCurrentControlSocket(connectedSocket)) false
                            else {
                                pendingAttSocket.set(attached)
                                BluetoothConnectionManager.attSocket = attached
                                ownedAttSocket.set(attached)
                                true
                            }
                        }
                    },
                    onError = { if (isCurrentConnection()) Log.w(TAG, "Optional ATT connection failed; AACP remains active", it) }
                ) ?: return@launch
                if (!isCurrentConnection() || BluetoothConnectionManager.attSocket !== attSocket) {
                    runCatching { attSocket.close() }
                    return@launch
                }
                if (!attManager.startReader(attSocket)) {
                    runCatching { attSocket.close() }
                    synchronized(BluetoothConnectionManager) {
                        if (BluetoothConnectionManager.attSocket === attSocket) BluetoothConnectionManager.attSocket = null
                    }
                    return@launch
                }
                if (!isCurrentConnection() || BluetoothConnectionManager.attSocket !== attSocket) return@launch
                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_ATT_CONNECTED).setPackage(packageName))
                for (handle in ATTHandles.entries) {
                    if (!isActive || !isCurrentConnection() || BluetoothConnectionManager.attSocket !== attSocket) break
                    attManager.readCharacteristic(handle)
                }
            }
        } catch (e: Exception) {
            if (!serviceResources.isClosed && e !is CancellationException) {
                Log.d(TAG, "<LogCollector:Complete:Failed> Socket not connected, ${e.message}")
                val reason = if (attempt.isClosed) "Bluetooth socket connection timed out" else e.localizedMessage
                if (manual) sendToast("Couldn't connect to socket: $reason")
                else showSocketConnectionFailureNotification("Couldn't connect to socket: $reason")
            }
        } finally {
            connectionPhaseFinished.set(true)
            timeoutJob?.cancel()
            if (!handedToReader) closeAttempt()
        }
    }

    fun disconnectForCD() {
        revokeAudioRestore(ownershipLost = true)
        BluetoothConnectionManager.aacpSocket?.close()
        MediaController.pausedWhileTakingOver = false
        Log.d(TAG, "Disconnected from AirPods, showing island.")
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.MOVED_TO_REMOTE
        )
        withAudioProfile(this, BluetoothProfile.A2DP, AudioProfileAction.PAUSE) { proxy ->
            if (proxy.connectedDevices.isNotEmpty() && isCurrentProfileAction()) MediaController.sendPause()
        }
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
    }

    fun disconnectAirPods() {
        if (serviceResources.isClosed || ServiceManager.getService() !== this) return
        revokeAudioRestore()
        val retiredDevice: BluetoothDevice?
        val retiredSockets = synchronized(BluetoothConnectionManager) {
            val aacp = BluetoothConnectionManager.aacpSocket
            val att = BluetoothConnectionManager.attSocket
            if (aacp == null && att == null) return
            retiredDevice = device
            // Revoke the old reader and writes before close can unblock them.
            // A new publication after this lock is released must survive cleanup.
            BluetoothConnectionManager.aacpSocket = null
            BluetoothConnectionManager.attSocket = null
            aacpManager.disconnected()
            updateNotificationContent(false)
            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                setPackage(packageName)
            })
            aacp to att
        }
        retiredSockets.second?.let { attManager.detachReader(it) }
        closeRetiredSockets(retiredSockets.first, retiredSockets.second)
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED){
            withAudioProfile(this, BluetoothProfile.A2DP, AudioProfileAction.PAUSE) { proxy ->
                if (proxy.connectedDevices.isNotEmpty() && isCurrentProfileAction()) MediaController.sendPause()
            }
            try {
                val currentDevice = retiredDevice
                if (currentDevice != null) {
                    if (Build.VERSION.SDK_INT >= 37) {
                        currentDevice.disconnect()
                    } else {
                        // Older privileged builds may expose this as a hidden API.
                        // Reflection is best-effort; failures are handled below.
                        BluetoothDevice::class.java.getMethod("disconnect").invoke(currentDevice)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "device.disconnect() failed, $e")
            }
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED){
            withAudioProfile(this, BluetoothProfile.HEADSET, AudioProfileAction.PAUSE) { proxy ->
                if (proxy.connectedDevices.isNotEmpty() && isCurrentProfileAction()) MediaController.sendPause()
            }
        }
        Log.d(TAG, "Disconnected AirPods upon user request")
    }

    private fun closeRetiredSockets(control: BluetoothSocket?, att: BluetoothSocket?) {
        // The references were revoked synchronously. Cleanup must survive service
        // cancellation and must never look up (or close) a replacement connection.
        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            for (socket in listOfNotNull(control, att).distinct()) {
                try { socket.close() }
                catch (error: Exception) { Log.w(TAG, "Retired Bluetooth socket close failed", error) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun retireControlForChangedPeer() {
        if (serviceResources.isClosed) return
        val retired = synchronized(BluetoothConnectionManager) {
            val control = BluetoothConnectionManager.aacpSocket ?: return
            val selected = me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "")
            val peer = if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                me.kavishdevar.librepods.data.batteryHistoryIdentity(control.remoteDevice.address) else null
            if (peer != null && peer == selected) return
            val att = BluetoothConnectionManager.attSocket
            BluetoothConnectionManager.aacpSocket = null
            BluetoothConnectionManager.attSocket = null
            aacpManager.disconnected()
            updateNotificationContent(false)
            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).setPackage(packageName))
            control to att
        }
        // Both sockets still belong to their tracked connection scope if cancellation
        // happens before this worker starts. Existing Bluetooth audio is left playing.
        serviceScope.launch {
            retired.second?.let { attManager.stopReader(it) }
            runCatching { retired.first.close() }
        }
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationalAwarenessNotification()

    @Suppress("unused")
    fun setEarDetection(enabled: Boolean) {
        if (config.earDetectionEnabled != enabled) {
            config.earDetectionEnabled = enabled
            sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        }
    }

    fun getBattery(): List<Battery> {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            batteryNotification.setBattery(CrossDevice.batteryBytes)
//        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            ancNotification.setStatus(CrossDevice.ancBytes)
//        }
        return ancNotification.status
    }

    private fun withAudioProfile(
        context: Context, requestedProfile: Int, purpose: AudioProfileAction, action: (BluetoothProfile) -> Unit
    ) {
        val offered = prepareAudioProfileRequest(requestedProfile, purpose, takeoverGeneration.get(), action) ?: return
        if (offered.acquire) acquireAudioProfile(context.applicationContext, offered.ticket)
    }

    private fun prepareAudioProfileRequest(
        profile: Int, purpose: AudioProfileAction, generation: Long, action: (BluetoothProfile) -> Unit
    ) = profileRequests.offer(profile, purpose, AudioProfileRequest(generation, action))

    private fun acquireAudioProfile(context: Context, ticket: PendingProfileRequests.Ticket<Int>) {
        serviceScope.launch {
            if (serviceResources.isClosed || !profileRequests.contains(ticket)) return@launch
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            if (adapter == null) { profileRequests.rejected(ticket); return@launch }
            val listener = createAudioProfileListener(adapter, ticket)
            if (listener.shouldAcquire) beginAudioProfileAcquisition(context, adapter, listener)
        }
    }

    // A framework listener may wait while Bluetooth is off. It must not retain the service,
    // pending actions or a service Context. Only the service-owned queue holds those actions.
    private class WeakAudioProfileListener(
        private val context: Context, private val adapter: BluetoothAdapter,
        private val requestedProfile: Int, val acquisition: PendingAcquisitions.Ticket<Int>?,
        val shouldAcquire: Boolean
    ) : BluetoothProfile.ServiceListener {
        private var delivered = false
        private var proxy = java.lang.ref.WeakReference<BluetoothProfile>(null)
        override fun onServiceConnected(profile: Int, value: BluetoothProfile) {
            val first = synchronized(this) {
                if (delivered) {
                    if (proxy.get() === value) return
                    false
                } else {
                    delivered = true
                    proxy = java.lang.ref.WeakReference(value)
                    true
                }
            }
            val recipient = if (first && acquisition != null) pendingAudioProfiles.take(acquisition) else null
            val service = recipient?.owner?.get()
            if (recipient != null && service != null && recipient.isCurrent()) {
                service.acceptAudioProfile(adapter, recipient.ticket, profile, value, acquisition!!, context)
            } else closeOrphan(context, adapter, requestedProfile, value, if (recipient != null) acquisition else null)
        }
        override fun onServiceDisconnected(profile: Int) {}
        companion object {
            fun closeOrphan(context: Context, adapter: BluetoothAdapter, profile: Int, proxy: BluetoothProfile,
                            acquisition: PendingAcquisitions.Ticket<Int>? = null) {
                CoroutineScope(NonCancellable + Dispatchers.IO).launch {
                    try { adapter.closeProfileProxy(profile, proxy) }
                    catch (error: Exception) { Log.w(TAG, "Could not release late audio profile", error) }
                    finally { if (acquisition != null) completeAudioProfileAcquisition(context, adapter, acquisition) }
                }
            }
        }
    }

    private fun createAudioProfileListener(adapter: BluetoothAdapter, ticket: PendingProfileRequests.Ticket<Int>): WeakAudioProfileListener {
        val recipient = PendingAudioProfileRecipient(this, profileOwnerToken, ticket)
        val offered = pendingAudioProfiles.offer(ticket.profile, recipient) { recipient.isCurrent() }
        offered?.previous?.takeUnless { it.sameRequest(recipient) }?.reject()
        if (offered == null) recipient.reject()
        return WeakAudioProfileListener(applicationContext, adapter, ticket.profile, offered?.ticket, offered?.acquire == true)
    }

    private fun acceptAudioProfile(
        adapter: BluetoothAdapter, ticket: PendingProfileRequests.Ticket<Int>, profile: Int, proxy: BluetoothProfile,
        acquisition: PendingAcquisitions.Ticket<Int>, context: Context
    ) {
        val requests = profileRequests.take(ticket)
        if (requests == null) { WeakAudioProfileListener.closeOrphan(context, adapter, ticket.profile, proxy, acquisition); return }
        lateinit var operation: AsyncResourceOperation<BluetoothProfile>
        operation = AsyncResourceOperation(proxy, Dispatchers.IO,
            release = { adapter.closeProfileProxy(ticket.profile, it) },
            onError = { Log.w(TAG, "Audio profile operation failed", it) },
            onFinished = {
                try {
                    serviceResources.release(operation)
                    profileRequests.finished(ticket)?.let { acquireAudioProfile(applicationContext, it) }
                } finally { completeAudioProfileAcquisition(context, adapter, acquisition) }
            })
        try {
            serviceResources.track(operation)
            operation.start(serviceScope, { profile == ticket.profile && profileRequests.contains(ticket) }) { value ->
                for (request in requests) {
                    val current = { !serviceResources.isClosed && profileRequests.contains(ticket) &&
                        request.generation == takeoverGeneration.get() }
                    if (!current()) continue
                    profileActionCurrent.set(current)
                    try { request.action(value) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Log.w(TAG, "Audio profile action failed", error) }
                    finally { profileActionCurrent.remove() }
                }
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // track() retires a late resource and schedules cleanup independently of the service.
        }
    }

    private fun isCurrentProfileAction(): Boolean = profileActionCurrent.get()?.invoke() != false

    @SuppressLint("MissingPermission")
    private fun audioOperationValid(
        target: BluetoothDevice, ticket: AudioProfileOwnership.Ticket, socket: BluetoothSocket?
    ): Boolean = !serviceResources.isClosed && target.address.equals(macAddress, ignoreCase = true) &&
        ((socket == null && ticket.operation == AudioProfileOwnership.Operation.EXPLICIT_CONNECT) ||
            (socket != null && isCurrentControlSocket(socket)))

    fun disconnectAudio(context: Context, device: BluetoothDevice?, restorable: Boolean = false) {
        val target = device ?: return
        val ticket = audioProfileOwnership.beginRelease(target.address, restorable) ?: return
        val socket = BluetoothConnectionManager.aacpSocket
        serviceScope.launch(Dispatchers.Main.immediate) { clearStaleA2dpConnectionReceiver() }
        for (profile in ticket.profiles) {
            val permission = if (profile == BluetoothProfile.A2DP) "android.permission.BLUETOOTH_PRIVILEGED" else "android.permission.MODIFY_PHONE_STATE"
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) continue
            withAudioProfile(context, profile, AudioProfileAction.POLICY) { proxy ->
                audioProfileOwnership.withCurrent(ticket, profile, { audioOperationValid(target, ticket, socket) }) {
                    if (proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED &&
                        isCurrentProfileAction() && audioOperationValid(target, ticket, socket)) {
                        audioProfileOwnership.applyReleasePolicy(ticket, profile,
                            { isCurrentProfileAction() && audioOperationValid(target, ticket, socket) }) {
                            proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
                                .invoke(proxy, target, 0) == true
                        }
                    }
                }
            }
        }
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val target = device ?: return
        val ticket = audioProfileOwnership.beginExplicitConnect(target.address)
        serviceScope.launch(Dispatchers.Main.immediate) { clearStaleA2dpConnectionReceiver() }
        connectAudioProfiles(context, target, ticket, socket = null, playWhenConnected = false)
    }

    private fun connectAudioProfiles(
        context: Context, target: BluetoothDevice, ticket: AudioProfileOwnership.Ticket,
        socket: BluetoothSocket?, playWhenConnected: Boolean, canConnect: () -> Boolean = { true }
    ) {
        for (profile in ticket.profiles) {
            val permission = if (profile == BluetoothProfile.A2DP) "android.permission.BLUETOOTH_PRIVILEGED" else "android.permission.MODIFY_PHONE_STATE"
            val canSetPolicy = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            if (profile == BluetoothProfile.HEADSET && !canSetPolicy) continue
            withAudioProfile(context, profile, AudioProfileAction.POLICY) { proxy ->
                var explicitMediaConnected = false
                var enteredOperation = false
                audioProfileOwnership.withCurrent(ticket, profile, { isCurrentProfileAction() && canConnect() && audioOperationValid(target, ticket, socket) }) operation@ {
                    enteredOperation = true
                    // Never undo a released policy after the permission needed to restore it was revoked.
                    if (profile in ticket.releasedProfiles && !canSetPolicy) return@operation
                    if (canSetPolicy) {
                        val allowed = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
                            .invoke(proxy, target, 100) == true
                        if (allowed) audioProfileOwnership.recordRestored(ticket, profile)
                        else if (ticket.operation == AudioProfileOwnership.Operation.RESTORE) return@operation
                    }
                    if (!audioProfileOwnership.isCurrent(ticket) || !isCurrentProfileAction() || !canConnect() || !audioOperationValid(target, ticket, socket)) return@operation
                    val receiver = if (playWhenConnected && profile == BluetoothProfile.A2DP && socket != null)
                        registerA2dpConnectionReceiver(target, ticket, socket) else null
                    try {
                        val accepted = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, target) == true
                        if (!audioProfileOwnership.isCurrent(ticket) || !isCurrentProfileAction() || !canConnect() || !audioOperationValid(target, ticket, socket)) {
                            if (receiver != null) clearA2dpConnectionReceiver(receiver)
                            return@operation
                        }
                        if (accepted) {
                            if (profile !in ticket.releasedProfiles) audioProfileOwnership.recordRestored(ticket, profile)
                            explicitMediaConnected = profile == BluetoothProfile.A2DP &&
                                ticket.operation == AudioProfileOwnership.Operation.EXPLICIT_CONNECT
                        } else if (receiver != null) clearA2dpConnectionReceiver(receiver)
                    } catch (e: Exception) {
                        if (receiver != null) clearA2dpConnectionReceiver(receiver)
                        throw e
                    }
                }
                // A returning wearer can queue behind a policy write that is later rejected.
                // Resume an existing local profile without granting or attempting a connection.
                if (!enteredOperation && playWhenConnected && profile == BluetoothProfile.A2DP &&
                    ticket.operation == AudioProfileOwnership.Operation.RESTORE && audioProfileOwnership.isCurrent(ticket) &&
                    isCurrentProfileAction() && canConnect() && audioOperationValid(target, ticket, socket) &&
                    proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED &&
                    audioProfileOwnership.isCurrent(ticket) && isCurrentProfileAction() && canConnect() &&
                    audioOperationValid(target, ticket, socket)) MediaController.sendPlay()
                // MediaController may call takeOver while holding its own lock. Keep its lock
                // outside the profile policy transaction to avoid an inverse lock order.
                if (explicitMediaConnected && isCurrentProfileAction() && audioProfileOwnership.isCurrent(ticket) && canConnect() &&
                    !serviceResources.isClosed && MediaController.pausedWhileTakingOver) MediaController.sendPlay()
            }
        }
    }

    fun setName(name: String) {
        requestRename(name, me.kavishdevar.librepods.data.batteryHistoryIdentity(macAddress))
    }

    internal fun requestRename(name: String, expectedPeer: String?): Boolean {
        if (serviceResources.isClosed || !::sharedPreferences.isInitialized ||
            me.kavishdevar.librepods.data.airPodsNameProblem(name) != null || expectedPeer == null ||
            expectedPeer != me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "")) return false
        val socket = BluetoothConnectionManager.aacpSocket ?: return false
        if (!isCurrentControlSocket(socket)) return false
        val offered = RenameRequest(expectedPeer, name, socket, aacpManager.captureDeviceSession(),
            device?.takeIf { me.kavishdevar.librepods.data.batteryHistoryIdentity(it.address) == expectedPeer })
        val request = synchronized(renameBindingLock) {
            desiredRename?.takeIf { it == offered } ?: offered.also { desiredRename = it; appliedRename = null }
        }
        if (!renameRequestCurrent(request)) return false
        if (appliedRename === request) return true
        return renameCommands.offer(Unit, request)
    }

    private fun renameRequestCurrent(request: RenameRequest): Boolean =
        !serviceResources.isClosed && !renameCommands.isClosed && ServiceManager.getService() === this &&
            desiredRename === request && aacpManager.isCurrentDeviceSession(request.deviceSession) &&
            request.peer == me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "") &&
            isCurrentControlSocket(request.socket)

    private fun writeRenameRequest(request: RenameRequest) {
        if (!renameRequestCurrent(request) || appliedRename === request) return
        val packet = aacpManager.createDataPacket(aacpManager.createRenamePacket(request.name))
        if (!aacpManager.sendPacket(packet, request.socket) { renameRequestCurrent(request) } || !renameRequestCurrent(request)) return
        try {
            request.target?.let {
                val status = it.setAlias(request.name)
                if (status != android.bluetooth.BluetoothStatusCodes.SUCCESS) Log.w(TAG, "Bluetooth alias update returned $status")
            }
        } catch (error: Exception) { Log.w(TAG, "Bluetooth alias update failed", error) }
        synchronized(renameBindingLock) {
            if (!renameRequestCurrent(request)) return
            appliedRename = request
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            if (!renameRequestCurrent(request)) return@launch
            try {
                // Publish after checking selection on Main; output and alias calls stay on IO.
                config.deviceName = request.name
                sharedPreferences.edit { putString("name", request.name) }
            } catch (error: Exception) { Log.w(TAG, "Rename display update failed", error) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        notificationLease?.close()
        notificationLease = null
        synchronized(renameBindingLock) { desiredRename = null; appliedRename = null }
        if (::batteryHistory.isInitialized) { batteryHistory.flush(); batteryHistory.close() }
        takeoverGeneration.incrementAndGet()
        audioProfileOwnership.revoke()
        cameraGeneration.incrementAndGet()
        cameraActive = false
        appliedHeadTrackingRequest = null
        headTrackingStartup.clear()
        headTrackingSession.close()
        serviceResources.close()
        val retiredControl = ownedControlSocket.getAndSet(null)
        val retiredAtt = ownedAttSocket.getAndSet(null)
        synchronized(BluetoothConnectionManager) {
            if (retiredControl != null && BluetoothConnectionManager.aacpSocket === retiredControl)
                BluetoothConnectionManager.aacpSocket = null
            if (retiredAtt != null && BluetoothConnectionManager.attSocket === retiredAtt)
                BluetoothConnectionManager.attSocket = null
        }
        val retiredReader = attManager.detachReader()
        closeRetiredSockets(retiredControl, retiredAtt)
        if (retiredReader != null && retiredReader !== retiredAtt) closeRetiredSockets(null, retiredReader)
        serviceScope.cancel()
        if (::aacpManager.isInitialized) aacpManager.disconnected()
        localMacLookupFinished.countDown()
        gestureDetector?.release()
        MediaController.release(this)
        popupWindow?.close()
        popupWindow = null
        islandWindow?.close()
        islandWindow = null
        if (ServiceManager.getService() === this) ServiceManager.setService(null)
        // History belongs to the process; retiring a service must not clear its successor.
        synchronized(inMemoryLogs) { packetLogFormatter.clear() }
        clearA2dpConnectionReceiver()
        showIslandReceiver?.let { runCatching { unregisterReceiver(it) } }
        showIslandReceiver = null
        if (phoneBatteryReceiverRegistered) {
            runCatching { unregisterReceiver(phoneBatteryReceiver) }
            phoneBatteryReceiverRegistered = false
        }
        Log.d(TAG, "Service stopped is being destroyed for some reason!")

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(externalBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bleManager.stopScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::batteryHistory.isInitialized && ServiceManager.getService() == null) {
            // A manifest receiver keeps its short background publication alive after
            // this service retires, without constructing views or querying hosts on Main.
            BatteryWidget.requestRefresh(this)
            NoiseControlWidget.requestRefresh(this)
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.unregisterTelephonyCallback(phoneStateListener)
        }
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    val isHeadTrackingActive: Boolean get() = headTrackingSession.capture().enabled

    private fun selectedHeadTrackingPeer() = me.kavishdevar.librepods.data.batteryHistoryIdentity(
        sharedPreferences.getString("mac_address", "") ?: "")

    fun startHeadTracking() {
        headTrackingSession.request(true, selectedHeadTrackingPeer())?.let(::applyHeadTrackingDesired)
    }

    fun stopHeadTracking() {
        headTrackingSession.request(false, selectedHeadTrackingPeer())?.let(::applyHeadTrackingDesired)
    }

    internal fun acquireHeadTracking(consumer: HeadTrackingSession.Consumer): Closeable? {
        if (serviceResources.isClosed) return null
        val peer = selectedHeadTrackingPeer() ?: return null
        val lease = headTrackingSession.acquire(consumer, peer) ?: return null
        applyHeadTrackingDesired(headTrackingSession.capture())
        val closed = AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) headTrackingSession.release(lease)?.let(::applyHeadTrackingDesired)
        }
    }

    private fun applyHeadTrackingDesired(ticket: HeadTrackingSession.Ticket) {
        if (serviceResources.isClosed || !headTrackingSession.isCurrent(ticket)) return
        if (!ticket.enabled) synchronized(headTrackingBindingLock) {
            headTrackingStartupJob?.cancel()
            headTrackingStartupJob = null
            headTrackingStartup.clear()
        }
        val alreadyApplied = synchronized(headTrackingBindingLock) {
            val applied = appliedHeadTrackingRequest
            if (applied != null && applied.ticket == ticket &&
                applied.preferredAlternate == sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true) &&
                headTrackingRequestCurrent(applied) &&
                (!ticket.enabled || headTrackingStatus.value != HeadTrackingStatus.UNAVAILABLE)) true
            else false
        }
        if (alreadyApplied) return
        if (ticket.enabled && aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)
                ?.value?.firstOrNull()?.toInt() != 1) takeOver("call", startHeadTrackingAgain = true)
        enqueueHeadTrackingCommand(ticket)
    }

    private fun enqueueHeadTrackingCommand(ticket: HeadTrackingSession.Ticket, restart: Boolean = false) {
        if (!headTrackingSession.isCurrent(ticket) || serviceResources.isClosed) return
        val applied = appliedHeadTrackingRequest
        if (!restart && applied != null && applied.ticket == ticket &&
            applied.preferredAlternate == sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true) &&
            headTrackingRequestCurrent(applied) &&
            (!ticket.enabled || headTrackingStatus.value != HeadTrackingStatus.UNAVAILABLE)) return
        val socket = BluetoothConnectionManager.aacpSocket
        if (socket == null || !isCurrentControlSocket(socket)) {
            headTrackingStartup.clear(if (ticket.enabled) HeadTrackingStatus.UNAVAILABLE else HeadTrackingStatus.INACTIVE)
            return
        }
        val preferred = sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        // Use the matching stop packet after a successful automatic fallback. A restart
        // on the same link also keeps the working variant instead of reselecting it.
        val alternate = applied?.takeIf {
            it.socket === socket && it.ticket.peer == ticket.peer && it.preferredAlternate == preferred &&
                aacpManager.isCurrentDeviceSession(it.deviceSession)
        }?.alternate ?: preferred
        val request = HeadTrackingRequest(ticket, socket, aacpManager.captureDeviceSession(),
            headTrackingAttempts.incrementAndGet(), preferred, alternate, motionService = aacpManager.headTrackingService)
        if (headTrackingRequestCurrent(request)) headTrackingCommands.offer(Unit, request)
    }

    private fun headTrackingRequestCurrent(request: HeadTrackingRequest): Boolean =
        !serviceResources.isClosed && !headTrackingCommands.isClosed && ServiceManager.getService() === this &&
            // Audio ownership notifications may revoke media restore without ending this sensor lease.
            headTrackingSession.isCurrent(request.ticket) &&
            request.preferredAlternate == sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true) &&
            request.ticket.peer != null && request.ticket.peer == me.kavishdevar.librepods.data.batteryHistoryIdentity(
                sharedPreferences.getString("mac_address", "") ?: "") &&
            aacpManager.isCurrentDeviceSession(request.deviceSession) && isCurrentControlSocket(request.socket) &&
            request.motionService == aacpManager.headTrackingService

    private fun writeHeadTrackingRequest(request: HeadTrackingRequest) {
        if (!headTrackingRequestCurrent(request)) return
        if (appliedHeadTrackingRequest == request) return
        if (request.retryOf != null && (appliedHeadTrackingRequest?.attempt != request.retryOf ||
                headTrackingStatus.value == HeadTrackingStatus.RECEIVING)) return
        val data = if (request.ticket.enabled) {
            if (request.alternate) aacpManager.createAlternateStartHeadTrackingPacket() else aacpManager.createStartHeadTrackingPacket()
        } else {
            if (request.alternate) aacpManager.createAlternateStopHeadTrackingPacket() else aacpManager.createStopHeadTrackingPacket()
        }
        val packet = request.motionService?.let {
            me.kavishdevar.librepods.bluetooth.RtBuddyHeadTracking.settingPacket(it, request.ticket.enabled)
        } ?: aacpManager.createDataPacket(data)
        val sent = aacpManager.sendPacket(packet, request.socket) { headTrackingRequestCurrent(request) }
        if (!sent) {
            if (headTrackingRequestCurrent(request)) {
                if (request.ticket.enabled) headTrackingStartup.failed() else headTrackingStartup.clear()
            }
            return
        }
        if (!headTrackingRequestCurrent(request)) return
        // This short publication lock never covers socket output. Joining a consumer
        // cannot clear a successful publication between its check and assignment.
        synchronized(headTrackingBindingLock) {
            if (!headTrackingRequestCurrent(request)) return
            if (request.ticket.enabled) HeadTracking.reset()
            appliedHeadTrackingRequest = request
            headTrackingStartupJob?.cancel()
            headTrackingStartupJob = null
            if (!request.ticket.enabled) headTrackingStartup.clear()
            else {
                headTrackingStartup.begin(request)
                headTrackingStartupJob = serviceScope.launch {
                    delay(2000)
                    synchronized(headTrackingBindingLock) {
                        if (appliedHeadTrackingRequest !== request || !headTrackingRequestCurrent(request)) return@launch
                        if (headTrackingStartup.timedOut(request, canRetry = request.retryOf == null)) {
                            Log.i(TAG, "No head tracking samples; trying the other start packet")
                            val retry = request.copy(attempt = headTrackingAttempts.incrementAndGet(),
                                alternate = !request.alternate, retryOf = request.attempt)
                            headTrackingCommands.offer(Unit, retry)
                        } else if (headTrackingStatus.value == HeadTrackingStatus.UNAVAILABLE) {
                            Log.w(TAG, "Neither head tracking start packet produced sensor samples")
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }
        val targetDevice = device ?: return
        serviceScope.launch {
            Log.d(TAG, "connecting to $macAddress")
            connectToSocket(bluetoothAdapter, targetDevice, manual = true)
            if (isActive && !serviceResources.isClosed) connectAudio(this@AirPodsService, targetDevice)
        }
    }
}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}

fun getNextMode(currentMode: Int, configByte: Int, offmodeEnabled: Boolean): Int {
    val enabledModes = buildList {
        if ((configByte and 0x01) != 0 && offmodeEnabled) add(1)
        if ((configByte and 0x04) != 0) add(3)
        if ((configByte and 0x08) != 0) add(4)
        if ((configByte and 0x02) != 0) add(2)
    }
    Log.d(TAG, "currentMode: $currentMode, config: ${configByte.toString(2)}")

    if (enabledModes.isEmpty()) return currentMode

    val currentIndex = enabledModes.indexOf(currentMode)
    val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % enabledModes.size

    return enabledModes[nextIndex]
}
