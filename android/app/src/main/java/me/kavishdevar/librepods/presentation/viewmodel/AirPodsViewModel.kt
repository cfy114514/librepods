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

package me.kavishdevar.librepods.presentation.viewmodel

import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.entitlementChanges
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTCCCDHandles
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.RemoteXposedPreferences
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.KeyedWorkSession
import me.kavishdevar.librepods.utils.InteractiveCommandQueue
import java.io.Closeable

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String = "AirPods",
    val selectedPeer: String? = null,
    val selectedPeerVersion: Long = 0,
    val serviceBindingId: Long = 0,

    val isLocallyConnected: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val offListeningMode: Boolean = true,

    val battery: List<Battery> = emptyList(),
    val ancMode: Int = 3,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headTrackingPeer: String? = null,
    val headGesturesEnabled: Boolean = true,

    val eqData: FloatArray = floatArrayOf(),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val loudSoundReductionEnabled: Boolean = false,
    val transparencyData: ByteArray = byteArrayOf(),
    val hearingAidData: ByteArray = byteArrayOf(),

    val isPremium: Boolean = false,
    val billingReady: Boolean = false,
    val billingError: Boolean = false,
    val vendorIdHook: Boolean = false,

    val dynamicEndOfCharge: Boolean = false,

    val connectionSuccessful: Boolean = false,
    val timeUntilFOSSPremiumExpiry: Long = 0L,

    val customEq: CustomEq = CustomEq(1, 50, 50, 50) // disabled
)

val demoInstance = AirPodsInstance(
    name = "[DEMO] AirPods Pro",
    model = AirPodsModels.getModelByModelNumber("A3064")!!,
    actualModelNumber = "A3064",
    serialNumber = "JXF9Q94A40",
    leftSerialNumber = "L-DEMO",
    rightSerialNumber = "R-DEMO",
    version1 = "90.3388000000000000.1786",
    version2 = "90.3388000000000000.1786",
    version3 = "9441861",
)

val demoState = AirPodsUiState(
    deviceName = demoInstance.name,

    isLocallyConnected = true,

    capabilities = demoInstance.model.capabilities,

    battery = listOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.RIGHT, 18, BatteryStatus.CHARGING),
        Battery(BatteryComponent.CASE, 76, BatteryStatus.NOT_CHARGING)
    ),

    ancMode = 3,
    offListeningMode = false,

    modelName = demoInstance.model.displayName,
    actualModel = demoInstance.actualModelNumber,
    serialNumbers =  listOf(
        demoInstance.serialNumber?: "",
        demoInstance.leftSerialNumber?: "",
        demoInstance.rightSerialNumber?: ""
    ),

    version1 = demoInstance.version1?: "",
    version2 = demoInstance.version2?: "",
    version3 = demoInstance.version3?: "",

    headTrackingActive = true,
    headGesturesEnabled = true,

    automaticEarDetectionEnabled = true,
    automaticConnectionEnabled = true,

    leftAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    rightAction = StemAction.DIGITAL_ASSISTANT,

    loudSoundReductionEnabled = true,

    isPremium = true,
    vendorIdHook = true,

    dynamicEndOfCharge = true,

    customEq = CustomEq(state = 2, low = 65, mid = 50, high = 70),

    controlStates = mapOf(
        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.STEM_CONFIG to byteArrayOf(0x00),
        ControlCommandIdentifiers.CLICK_HOLD_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG to byteArrayOf(0x00, 0x03),
        ControlCommandIdentifiers.CHIME_VOLUME to byteArrayOf(0x46, 0x50),
        ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_AID to byteArrayOf(0x01, 0x02),
        ControlCommandIdentifiers.HPS_GAIN_SWIPE to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_ASSIST_CONFIG to byteArrayOf(0x02),
        ControlCommandIdentifiers.HRM_STATE to byteArrayOf(0x01),
        ControlCommandIdentifiers.AUTO_ANC_STRENGTH to byteArrayOf(0x45),
        ControlCommandIdentifiers.ONE_BUD_ANC_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_TOGGLE_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_CAP_LEVEL_CONFIG to byteArrayOf(0x52),
        ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE to byteArrayOf(0x01),
        ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(0x04)
    )
)

class AirPodsViewModel(

) : ViewModel() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var appContext: Context
    private lateinit var service: AirPodsService
    private var nextServiceBindingId = 0L
    private var renamePeerIdentity: String? = null
    @Volatile private var renamePeerVersion = 0L
    private lateinit var controlRepo: ControlCommandRepository
    private data class SettingWrite(val socket: BluetoothSocket, val packet: ByteArray,
        val deviceSession: Long, val settingsVersion: Long)
    private val settingsVersion = AtomicLong()
    private var controlWrites: KeyedWorkSession<Int, SettingWrite>? = null
    private data class AttSettingWrite(val handle: ATTHandles, val value: ByteArray,
        val reader: ATTManagerv2.ReaderLease, val deviceSession: Long, val settingsVersion: Long,
        val uiVersion: Long, val confirmed: AtomicBoolean = AtomicBoolean())
    private var attWrites: InteractiveCommandQueue<ATTHandles, AttSettingWrite>? = null
    private val attUiVersions = AtomicLongArray(ATTHandles.entries.size)
    private val attPendingWrites = AtomicReferenceArray<AttSettingWrite?>(ATTHandles.entries.size)
    private enum class AttRefreshKind { LOAD, NOTIFICATIONS }
    private data class AttBinding(val owner: AirPodsService, val reader: ATTManagerv2.ReaderLease,
        val deviceSession: Long, val settingsVersion: Long, val bindingId: Long) {
        fun sameContext(other: AttBinding): Boolean = owner === other.owner && reader.identity === other.reader.identity &&
            deviceSession == other.deviceSession && settingsVersion == other.settingsVersion && bindingId == other.bindingId
    }
    private class AttRefreshRequest(val kind: AttRefreshKind, val binding: AttBinding,
        val versions: LongArray = LongArray(0), val pending: BooleanArray = BooleanArray(0)) {
        val finished = AtomicBoolean()
        val publishing = AtomicBoolean()
        @Volatile var subscribed = false
    }
    private var attRefreshes: InteractiveCommandQueue<AttRefreshKind, AttRefreshRequest>? = null
    private val attLoadRequest = AtomicReference<AttRefreshRequest?>()
    private val attSubscription = AtomicReference<AttRefreshRequest?>()
    private var customEqObserver: Closeable? = null
    private var attObserver: Closeable? = null
    private var headPreviewToken: Any? = null
    private var headPreviewRequested = false
    private var headPreviewLease: Closeable? = null
    private val customEqWriteKey = 256

    var isReady by mutableStateOf(false)
        private set

    fun init(service: AirPodsService, controlRepo: ControlCommandRepository, sharedPreferences: SharedPreferences, appContext: Context) {
        // The Activity can reconnect or be recreated while this ViewModel survives.
        // Keep one set of observers for a service, and release the old set on replacement.
        if (isReady && this.service === service) {
            if (!isDemoMode) {
                loadCurrentStatus()
                loadATT()
                enableATTNotifications()
                loadEq()
            }
            return
        }
        clearObservers()
        this.service = service
        this.controlRepo = controlRepo
        this.sharedPreferences = sharedPreferences
        this.appContext = appContext
        val bindingId = ++nextServiceBindingId
        _uiState.update { it.copy(serviceBindingId = bindingId) }

        val owner = service
        lateinit var writer: KeyedWorkSession<Int, SettingWrite>
        writer = KeyedWorkSession(ControlCommandIdentifiers.entries.map { it.value.toInt() and 0xFF }.toSet() + customEqWriteKey,
            Dispatchers.IO, onError = { Log.w("AirPodsViewModel", "Setting write failed", it) }) { request ->
            owner.aacpManager.sendPacket(request.packet, request.socket) {
                !writer.isClosed && !isDemoMode && settingsVersion.get() == request.settingsVersion &&
                    owner.aacpManager.isCurrentDeviceSession(request.deviceSession) &&
                    ServiceManager.getService() === owner && owner.isCurrentControlSocket(request.socket)
            }
        }
        controlWrites = writer
        lateinit var attWriter: InteractiveCommandQueue<ATTHandles, AttSettingWrite>
        attWriter = InteractiveCommandQueue(ATTHandles.entries.toSet(), Dispatchers.IO,
            onError = { Log.w("AirPodsViewModel", "ATT setting write failed", it) }) { request, current ->
            request.confirmed.set(owner.attManager.writeCharacteristicIfCurrent(request.reader, request.handle, request.value, canSend = {
                current() && !isDemoMode && settingsVersion.get() == request.settingsVersion &&
                    service === owner && ServiceManager.getService() === owner &&
                    owner.aacpManager.isCurrentDeviceSession(request.deviceSession) && owner.isCurrentAttSocket(request.reader.socket)
            }))
        }
        attWrites = attWriter
        attRefreshes = InteractiveCommandQueue(AttRefreshKind.entries.toSet(), Dispatchers.IO,
            onError = { Log.w("AirPodsViewModel", "ATT refresh failed", it) }, write = ::performAttRefresh)

        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        observeAACP()
        loadCurrentStatus()
        loadEq()
        loadATT()
        observeATT()
        observeSharedPreferences()
        observeBilling()
        observeRemoteXposedPreferences()
        if (isDemoMode) activateDemoMode()
        isReady = true
        renewHeadPreviewLease()
    }

    private val _uiState = MutableStateFlow(AirPodsUiState())

    val uiState: StateFlow<AirPodsUiState> = _uiState

    @Volatile private var isDemoMode = false

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private var remotePreferencesJob: Job? = null

    private var broadcastReceiver: BroadcastReceiver? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var billingJob: Job? = null

//    private val _cameraAction = MutableStateFlow(
//        sharedPreferences.getString("camera_action", null)
//            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } })
//
//    val cameraAction: StateFlow<AACPManager.Companion.StemPressType?> = _cameraAction
//
//    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
//        sharedPreferences.edit {
//            if (action == null) remove("camera_action")
//            else putString("camera_action", action.name)
//        }
//        _cameraAction.value = action
//    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        val updatedEq = _uiState.value.customEq.copy(low = low, mid = mid, high = high)
        enqueueSetting(customEqWriteKey, service.aacpManager.createDataPacket(updatedEq.toPacket()))
        _uiState.update {
            it.copy(
                customEq = updatedEq
            )
        }
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        enqueueSetting(customEqWriteKey, service.aacpManager.createDataPacket(
            _uiState.value.customEq.copy(state = if (enabled) 2 else 1).toPacket()))
        _uiState.update {
            it.copy(
                customEq = it.customEq.copy(state = if (enabled) 2 else 1)
            )
        }
    }

    override fun onCleared() {
        synchronized(this) {
            headPreviewToken = null
            headPreviewRequested = false
            headPreviewLease?.close()
            headPreviewLease = null
        }
        clearObservers()
        super.onCleared()
    }

    private fun clearObservers() {
        remotePreferencesJob?.cancel()
        remotePreferencesJob = null
        attRefreshes?.close()
        attRefreshes = null
        attWrites?.close()
        attWrites = null
        controlWrites?.close()
        controlWrites = null
        billingJob?.cancel()
        billingJob = null
        clearATTJobs()
        listeners.forEach { (id, listener) ->
            controlRepo.remove(id, listener)
        }
        listeners.clear()
        customEqObserver?.close()
        customEqObserver = null
        attObserver?.close()
        attObserver = null
        preferenceListener?.let { sharedPreferences.unregisterOnSharedPreferenceChangeListener(it) }
        preferenceListener = null
        broadcastReceiver?.let { appContext.unregisterReceiver(it) }
        broadcastReceiver = null
        isReady = false
    }

    private fun loadName() {
        val name = sharedPreferences.getString("name", "AirPods Pro")!!
        _uiState.update { it.copy(deviceName = name) }
    }

    private fun observeBilling() {
        if (isDemoMode) return
        billingJob?.cancel()
        billingJob = viewModelScope.launch {
            var lastPremium: Boolean? = null
            BillingManager.provider.entitlementChanges().collect { entitlement ->
                _uiState.update { it.copy(billingReady = entitlement.ready, billingError = entitlement.failed) }
                if (!entitlement.ready || entitlement.premium == lastPremium) return@collect
                val premium = entitlement.premium
                lastPremium = premium
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        setControlCommandBoolean(
                            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                            false
                        )
                        setHeadGesturesEnabled(false)
                        _uiState.update { it.copy(isPremium = false) }
                    }
                }
            }
        }
    }

    private fun observeSharedPreferences() {
        preferenceListener?.let { sharedPreferences.unregisterOnSharedPreferenceChangeListener(it) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "name" -> loadName()
                "mac_address" -> if (!isDemoMode) { loadInstance(); loadSharedPreferences(); renewHeadPreviewLease() }
                "airpods_model_address", "airpods_model_number",
                "airpods_serial_number", "airpods_left_serial_number", "airpods_right_serial_number",
                "airpods_version1", "airpods_version2", "airpods_version3" -> if (!isDemoMode) loadInstance()
                "off_listening_mode", "off_listening_mode_address", "automatic_ear_detection", "automatic_connection_ctrl_cmd",
                "head_gestures", "left_long_press_action", "right_long_press_action",
                "dynamic_end_of_charge", "foss_upgraded", "premium_expiry_time" -> loadSharedPreferences()
            }
        }
        preferenceListener = listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        loadCurrentStatus()
                        loadEq()
                        loadATT()
                        enableATTNotifications()
                    }
                    AirPodsNotifications.AIRPODS_ATT_CONNECTED -> {
                        loadATT()
                        enableATTNotifications()
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        // A queued disconnect from the previous transport must not
                        // cancel observers already attached to its replacement.
                        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
                        clearATTJobs()
                        _uiState.update {
                            it.copy(isLocallyConnected = false, controlStates = emptyMap(), ancMode = 1,
                                eqData = floatArrayOf(), customEq = CustomEq(1, 50, 50, 50),
                                loudSoundReductionEnabled = false, hearingAidData = byteArrayOf(), transparencyData = byteArrayOf())
                        }
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(battery = service.getBattery())
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadInstance()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_ATT_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ) {
        val ownedValue = value.copyOf()
        if (!isDemoMode) {
            if (identifier == ControlCommandIdentifiers.LISTENING_MODE ||
                identifier == ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG) {
                val writer = controlWrites
                val version = settingsVersion.get()
                if (writer != null) service.enqueueInteractiveCommand(identifier.value, ownedValue,
                    valid = { !writer.isClosed && !isDemoMode && settingsVersion.get() == version })
            } else enqueueSetting(identifier.value.toInt() and 0xFF,
                service.aacpManager.createDataPacket(service.aacpManager.createControlCommandPacket(identifier.value, ownedValue)))
        }
        _uiState.update {
            it.copy(
                controlStates = it.controlStates + (identifier to ownedValue)
            )
        }
    }

    private fun enqueueSetting(key: Int, packet: ByteArray) {
        if (isDemoMode) return
        val socket = BluetoothConnectionManager.aacpSocket ?: return
        if (!socket.isConnected) return
        controlWrites?.offer(key, SettingWrite(socket, packet.copyOf(),
            service.aacpManager.captureDeviceSession(), settingsVersion.get()))
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        if (identifier in listeners) return
        val owner = service
        val bindingId = _uiState.value.serviceBindingId
        val listener = controlRepo.observe(identifier) { value ->
            _uiState.update { state ->
                if (isDemoMode || service !== owner || state.serviceBindingId != bindingId) return@update state
                val current = state.controlStates[identifier]
                if (current?.contentEquals(value) == true) return@update state

                if (identifier == ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE) {
                    state.copy(
                        dynamicEndOfCharge = value[0] == 0x01.toByte(),
                        controlStates = state.controlStates + (identifier to value)
                    )
                } else {
                    state.copy(
                        controlStates = state.controlStates + (identifier to value)
                    )
                }
            }
        }

        listeners[identifier] = listener
    }

    // I'm lazy, sorry.
    fun observeAACP() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
        customEqObserver?.close()
        val currentService = service
        customEqObserver = currentService.aacpManager.registerCustomEqCallback { customEq ->
            if (service === currentService && !isDemoMode) _uiState.update { it.copy(customEq = customEq) }
        }
    }

    private fun observeRemoteXposedPreferences() {
        val owner = service
        val bindingId = _uiState.value.serviceBindingId
        remotePreferencesJob?.cancel()
        remotePreferencesJob = viewModelScope.launch {
            RemoteXposedPreferences.state.collect {
                val current = RemoteXposedPreferences.currentState()
                _uiState.update { state ->
                    if (isDemoMode || service !== owner || ServiceManager.getService() !== owner || state.serviceBindingId != bindingId) state
                    else state.copy(vendorIdHook = current.vendorIdHook)
                }
            }
        }
        RemoteXposedPreferences.requestRefresh()
    }

    fun loadCurrentStatus() {
        if (isDemoMode) return
        loadInstance()
        service.let { service ->
            _uiState.update {
                it.copy(
                    isLocallyConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true,
                    battery = service.getBattery(),
                    ancMode = controlRepo.getValue(ControlCommandIdentifiers.LISTENING_MODE)?.get(0)?.toInt() ?: 1,
                    controlStates = controlRepo.getMap(),
                    vendorIdHook = RemoteXposedPreferences.currentState().vendorIdHook
                )
            }
        }
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = me.kavishdevar.librepods.data.cachedOffListeningMode(sharedPreferences.all)
        val automaticEarDetectionEnabled =
            sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled =
            sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val leftAction = StemAction.fromString(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES
        val rightAction = StemAction.fromString(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES
        val vendorIdHook = RemoteXposedPreferences.currentState().vendorIdHook
        val dynamicEndOfCharge = sharedPreferences.getBoolean("dynamic_end_of_charge", false)

        val connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                headGesturesEnabled = headGesturesEnabled,
                leftAction = leftAction,
                rightAction = rightAction,
                vendorIdHook = vendorIdHook,
                dynamicEndOfCharge = dynamicEndOfCharge,
                connectionSuccessful = connectionSuccessful,
            )
        }

        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase
        if (BuildConfig.PLAY_BUILD) {
            val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
            val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
            val now = System.currentTimeMillis()

            when {
                // existing temporary premium
                expiryTime > 0L -> {
                    if (expiryTime <= now) {
                        sharedPreferences.edit {
                            remove("premium_expiry_time")
                            remove("foss_upgraded")
                        }

                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = 0L,
                                isPremium = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = expiryTime - now,
                                isPremium = true
                            )
                        }
                    }
                }

                // First migration from accidental FOSS Play build
                fossUpgraded && !_uiState.value.isPremium -> {
                    val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                    sharedPreferences.edit {
                        putLong("premium_expiry_time", newExpiry)
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = newExpiry - now,
                            isPremium = true
                        )
                    }
                }
            }
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        if (!isDemoMode) {
            val selected = sharedPreferences.getString("mac_address", "") ?: ""
            if (!me.kavishdevar.librepods.data.saveOffListeningMode(sharedPreferences, selected, enabled)) return
            setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
        }
        _uiState.update {
            it.copy(offListeningMode = enabled)
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setDynamicEndOfCharge(enabled: Boolean) {
        setControlCommandBoolean(ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE, enabled)
        sharedPreferences.edit { putBoolean("dynamic_end_of_charge", enabled) }
        _uiState.update {
            it.copy(dynamicEndOfCharge = enabled)
        }
    }

    private fun loadEq() {
        _uiState.update {
            it.copy(
                customEq = service.aacpManager.customEq,
                eqData = service.aacpManager.eqData
            )
        }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance
        val selected = me.kavishdevar.librepods.data.batteryHistoryIdentity(sharedPreferences.getString("mac_address", "") ?: "")
        if (renamePeerIdentity != selected) { renamePeerIdentity = selected; renamePeerVersion++ }

        _uiState.update {
            it.copy(
                selectedPeer = selected,
                selectedPeerVersion = renamePeerVersion,
                capabilities = instance?.model?.capabilities.orEmpty(),
                instance = instance,
                modelName = instance?.model?.name ?: "AirPods",
                actualModel = instance?.actualModelNumber ?: "",
                serialNumbers = listOf(
                    instance?.serialNumber ?: "",
                    instance?.leftSerialNumber ?: "",
                    instance?.rightSerialNumber ?: ""
                ),
                version1 = instance?.version1 ?: "",
                version2 = instance?.version2 ?: "",
                version3 = instance?.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        service.reconnectFromSavedMac()
    }

    fun setName(name: String) {
        createRenameEditor()(name)
    }

    fun createRenameEditor(): (String) -> Boolean {
        if (!isReady) return { false }
        val owner = service
        val peer = _uiState.value.selectedPeer
        val bindingId = _uiState.value.serviceBindingId
        val peerVersion = renamePeerVersion
        val demo = isDemoMode
        return { name ->
            if (!isReady || this.service !== owner || isDemoMode != demo ||
                _uiState.value.selectedPeer != peer || renamePeerVersion != peerVersion || _uiState.value.serviceBindingId != bindingId ||
                me.kavishdevar.librepods.data.airPodsNameProblem(name) != null) false
            else if (demo) { _uiState.update { it.copy(deviceName = name) }; true }
            else owner.requestRename(name, peer)
        }
    }

    @Synchronized
    fun beginHeadTrackingPreview(): Closeable {
        val token = Any()
        headPreviewToken = token
        headPreviewRequested = true
        renewHeadPreviewLease()
        return Closeable {
            synchronized(this) {
                if (headPreviewToken === token) {
                    headPreviewToken = null
                    headPreviewRequested = false
                    renewHeadPreviewLease()
                }
            }
        }
    }

    @Synchronized
    fun startHeadTracking() {
        if (headPreviewToken == null) headPreviewToken = Any()
        headPreviewRequested = true
        renewHeadPreviewLease()
    }

    @Synchronized
    fun stopHeadTracking() {
        headPreviewRequested = false
        renewHeadPreviewLease()
    }

    @Synchronized
    private fun renewHeadPreviewLease() {
        val previous = headPreviewLease
        // Acquire the replacement before closing the old handle. A stale close must not
        // interrupt a replacement preview or another consumer's calibration.
        headPreviewLease = if (headPreviewRequested && ::service.isInitialized && !isDemoMode)
            service.acquireHeadTracking(me.kavishdevar.librepods.services.HeadTrackingSession.Consumer.PREVIEW) else null
        previous?.close()
        val peer = if (::sharedPreferences.isInitialized) me.kavishdevar.librepods.data.batteryHistoryIdentity(
            sharedPreferences.getString("mac_address", "") ?: "") else null
        _uiState.update { it.copy(headTrackingActive = headPreviewRequested, headTrackingPeer = peer) }
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray) {
        val ownedValue = value.copyOf()
        val binding = captureAttBinding()
        val version = attUiVersions.incrementAndGet(handle.ordinal)
        val request = binding?.let { AttSettingWrite(handle, ownedValue.copyOf(), it.reader,
            it.deviceSession, it.settingsVersion, version) }
        if (request != null) attPendingWrites.set(handle.ordinal, request)
        _uiState.update { state ->
            if (attUiVersions.get(handle.ordinal) != version) state else when (handle) {
                ATTHandles.LOUD_SOUND_REDUCTION -> state.copy(loudSoundReductionEnabled = ownedValue.firstOrNull() == 0x01.toByte())
                ATTHandles.HEARING_AID -> state.copy(hearingAidData = ownedValue)
                ATTHandles.TRANSPARENCY -> state.copy(transparencyData = ownedValue)
            }
        }
        if (binding == null || request == null) return
        val writer = attWrites
        if (writer == null) {
            attPendingWrites.compareAndSet(handle.ordinal, request, null)
            return
        }
        writer.offer(handle, request, onComplete = {
            if (attPendingWrites.compareAndSet(handle.ordinal, request, null) && !request.confirmed.get()) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (validAttBinding(binding) && attUiVersions.get(handle.ordinal) == version) loadATT()
                }
            }
        })
    }

    fun loadATT() {
        val binding = captureAttBinding() ?: return
        val versions = LongArray(ATTHandles.entries.size) { attUiVersions.get(it) }
        val old = attLoadRequest.get()
        if (old != null && !old.finished.get() && old.binding.sameContext(binding) && old.versions.contentEquals(versions)) return
        val pending = BooleanArray(versions.size) { index ->
            attPendingWrites.get(index)?.let {
                it.uiVersion == versions[index] && it.reader.identity === binding.reader.identity &&
                    it.deviceSession == binding.deviceSession && it.settingsVersion == binding.settingsVersion
            } == true
        }
        val request = AttRefreshRequest(AttRefreshKind.LOAD, binding, versions, pending)
        attLoadRequest.set(request)
        offerAttRefresh(request)
    }

    private fun clearATTJobs() {
        attLoadRequest.set(null)
        attSubscription.set(null)
        for (handle in ATTHandles.entries) {
            attUiVersions.incrementAndGet(handle.ordinal)
            attPendingWrites.set(handle.ordinal, null)
        }
    }

    private fun enableATTNotifications() {
        val binding = captureAttBinding() ?: return
        val old = attSubscription.get()
        if (old != null && old.binding.sameContext(binding) && (!old.finished.get() || old.subscribed)) return
        val request = AttRefreshRequest(AttRefreshKind.NOTIFICATIONS, binding)
        attSubscription.set(request)
        offerAttRefresh(request)
    }

    private fun captureAttBinding(): AttBinding? {
        if (isDemoMode || !::service.isInitialized || attRefreshes == null || attWrites == null) return null
        val owner = service
        val socket = BluetoothConnectionManager.attSocket ?: return null
        if (ServiceManager.getService() !== owner || !owner.isCurrentAttSocket(socket)) return null
        val reader = owner.attManager.captureReader(socket) ?: return null
        return AttBinding(owner, reader, owner.aacpManager.captureDeviceSession(), settingsVersion.get(), _uiState.value.serviceBindingId)
    }

    private fun validAttTransport(binding: AttBinding): Boolean = !isDemoMode && service === binding.owner &&
        ServiceManager.getService() === binding.owner && _uiState.value.serviceBindingId == binding.bindingId &&
        binding.owner.isCurrentAttSocket(binding.reader.socket) && binding.owner.attManager.isCurrentReader(binding.reader)

    private fun validAttBinding(binding: AttBinding): Boolean = validAttTransport(binding) &&
        settingsVersion.get() == binding.settingsVersion && binding.owner.aacpManager.isCurrentDeviceSession(binding.deviceSession)

    private fun offerAttRefresh(request: AttRefreshRequest) {
        val worker = attRefreshes ?: run {
            request.finished.set(true)
            attLoadRequest.compareAndSet(request, null)
            attSubscription.compareAndSet(request, null)
            return
        }
        worker.offer(request.kind, request, onComplete = {
            if (!request.publishing.get()) request.finished.set(true)
            if (request.kind == AttRefreshKind.NOTIFICATIONS && !request.subscribed) attSubscription.compareAndSet(request, null)
        })
    }

    private fun performAttRefresh(request: AttRefreshRequest, current: () -> Boolean) {
        val reference = if (request.kind == AttRefreshKind.LOAD) attLoadRequest else attSubscription
        val valid = { current() && reference.get() === request && validAttBinding(request.binding) }
        if (!valid()) return
        val manager = request.binding.owner.attManager
        if (request.kind == AttRefreshKind.NOTIFICATIONS) {
            request.subscribed = ATTCCCDHandles.entries.all { valid() &&
                manager.enableNotificationIfCurrent(request.binding.reader, it, valid) } && valid()
            return
        }
        val values = ATTHandles.entries.associateWith { if (valid()) manager.getCharacteristicIfCurrent(request.binding.reader, it, valid) else null }
        if (!valid()) return
        request.publishing.set(true)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!valid()) return@launch
            _uiState.update { state ->
                if (!valid()) return@update state
                fun apply(handle: ATTHandles): Boolean = values[handle]?.isNotEmpty() == true && !request.pending[handle.ordinal] &&
                    attUiVersions.get(handle.ordinal) == request.versions[handle.ordinal]
                state.copy(
                    loudSoundReductionEnabled = if (apply(ATTHandles.LOUD_SOUND_REDUCTION))
                        values[ATTHandles.LOUD_SOUND_REDUCTION]?.firstOrNull() == 0x01.toByte() else state.loudSoundReductionEnabled,
                    hearingAidData = if (apply(ATTHandles.HEARING_AID)) values[ATTHandles.HEARING_AID] ?: byteArrayOf() else state.hearingAidData,
                    transparencyData = if (apply(ATTHandles.TRANSPARENCY)) values[ATTHandles.TRANSPARENCY] ?: byteArrayOf() else state.transparencyData
                )
            }
        }.invokeOnCompletion { request.finished.set(true) }
    }

    fun observeATT() {
        val currentService = service
        val attManager = currentService.attManager
        attObserver?.close()
        attObserver = attManager.registerReaderNotifications { source, handle, value ->
            val request = attSubscription.get() ?: return@registerReaderNotifications
            if (service !== currentService || !validAttTransport(request.binding) ||
                source.identity !== request.binding.reader.identity || (request.finished.get() && !request.subscribed)) return@registerReaderNotifications
            val attribute = ATTHandles.entries.find { it.value == (handle.toInt() and 0xFF) } ?: return@registerReaderNotifications
            val version = attUiVersions.incrementAndGet(attribute.ordinal)
            val ownedValue = value.copyOf()
            _uiState.update { state ->
                if (attSubscription.get() !== request || !validAttTransport(request.binding) ||
                    attUiVersions.get(attribute.ordinal) != version) state else when (attribute) {
                    ATTHandles.LOUD_SOUND_REDUCTION -> state.copy(loudSoundReductionEnabled = ownedValue.firstOrNull() == 0x01.toByte())
                    ATTHandles.HEARING_AID -> state.copy(hearingAidData = ownedValue)
                    ATTHandles.TRANSPARENCY -> state.copy(transparencyData = ownedValue)
                }
            }
        }
        enableATTNotifications()
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticEarDetectionEnabled = enabled
            )
        }
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_connection_ctrl_cmd", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticConnectionEnabled = enabled
            )
        }
    }

    fun activateDemoMode() {
        settingsVersion.incrementAndGet()
        isDemoMode = true
        _uiState.update { demoState.copy(serviceBindingId = it.serviceBindingId) }
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
        sharedPreferences.edit { putInt("long_press_byte", newValue) }
    }

    fun disconnect() {
        if (isDemoMode) {
            settingsVersion.incrementAndGet()
            isDemoMode = false
            _uiState.update {
                it.copy(isLocallyConnected = false)
            }
            loadATT()
            enableATTNotifications()
            _uiState.update { it.copy(vendorIdHook = RemoteXposedPreferences.currentState().vendorIdHook) }
            RemoteXposedPreferences.requestRefresh()
        } else {
            service.disconnectAirPods()
            if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    appContext, "App has disconnected, disconnect from Android Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
