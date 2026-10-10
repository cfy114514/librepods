package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.entitlementChanges
import me.kavishdevar.librepods.data.AppSetting
import me.kavishdevar.librepods.data.AppSettingsPreferenceState
import me.kavishdevar.librepods.data.AppSettingsPreferenceStores
import me.kavishdevar.librepods.data.temporaryPremiumPlan
import me.kavishdevar.librepods.data.RemoteXposedPreferences
import kotlin.math.roundToInt

data class AppSettingsUiState(
    val showPhoneBatteryInWidget: Boolean = false,
    val conversationalAwarenessPauseMusicEnabled: Boolean = false,
    val relativeConversationalAwarenessVolumeEnabled: Boolean = true,
    val disconnectWhenNotWearing: Boolean = false,
    val takeoverWhenDisconnected: Boolean = false,
    val takeoverWhenIdle: Boolean = false,
    val takeoverWhenMusic: Boolean = false,
    val takeoverWhenCall: Boolean = false,
    val takeoverWhenRingingCall: Boolean = false,
    val takeoverWhenMediaStart: Boolean = false,
    val useAlternateHeadTrackingPackets: Boolean = true,
    val conversationalAwarenessVolume: Float = 43f,
    val showCameraDialog: Boolean = false,
    val cameraPackageValue: String = "",
    val cameraPackageError: String? = null,
    val vendorIdHook: Boolean = false,
    val forceLegacyL2capWorkaround: Boolean = false,
    val xposedPreferencesReady: Boolean = false,
    val xposedPreferencesError: Boolean = false,
    val isPremium: Boolean = false,
    val billingReady: Boolean = false,
    val billingError: Boolean = false,
    val connectionSuccessful: Boolean = false,
    val showBottomSheetPopup: Boolean = true,
    val showIslandPopup: Boolean = true,
    val timeUntilFOSSPremiumExpiry: Long = 0L,
    val m3eEnabled: Boolean = false,
    val localSettingsReady: Boolean = false,
    val localSettingsError: Boolean = false
)

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppSettingsPreferenceStores.get(application)
    private val subscription = preferences.subscribe()
    private val _uiState = MutableStateFlow(AppSettingsUiState())
    val uiState = _uiState.asStateFlow()
    @Volatile private var closed = false
    private var paidPremium = false

    init {
        viewModelScope.launch {
            preferences.state.collect { if (!closed) applySettings(it) }
        }
        viewModelScope.launch {
            BillingManager.provider.entitlementChanges().collect { entitlement ->
                if (closed) return@collect
                _uiState.update { it.copy(billingReady = entitlement.ready, billingError = entitlement.failed) }
                if (entitlement.ready) paidPremium = entitlement.premium
                applySettings(preferences.state.value)
            }
        }
        viewModelScope.launch {
            RemoteXposedPreferences.state.collect {
                if (closed) return@collect
                val current = RemoteXposedPreferences.currentState()
                _uiState.update { it.copy(vendorIdHook = current.vendorIdHook,
                    forceLegacyL2capWorkaround = current.forceLegacyL2capWorkaround,
                    xposedPreferencesReady = current.ready, xposedPreferencesError = current.failed) }
            }
        }
        RemoteXposedPreferences.requestRefresh()
    }

    private fun applySettings(snapshot: AppSettingsPreferenceState) {
        val values = snapshot.values
        if (values == null || snapshot.readVersion < subscription.minimumReadVersion) {
            _uiState.update { it.copy(localSettingsError = snapshot.failed) }
            return
        }
        val premium = temporaryPremiumPlan(values, BuildConfig.PLAY_BUILD, paidPremium, System.currentTimeMillis())
        fun flag(key: AppSetting) = values.getValue(key) as Boolean
        val volume = values.getValue(AppSetting.VOLUME) as Int
        _uiState.update { current -> current.copy(
            localSettingsReady = true, localSettingsError = snapshot.failed,
            showPhoneBatteryInWidget = flag(AppSetting.PHONE_BATTERY),
            conversationalAwarenessPauseMusicEnabled = flag(AppSetting.PAUSE_CONVERSATION),
            relativeConversationalAwarenessVolumeEnabled = flag(AppSetting.RELATIVE_VOLUME),
            disconnectWhenNotWearing = flag(AppSetting.DISCONNECT_UNWORN),
            takeoverWhenDisconnected = flag(AppSetting.TAKEOVER_DISCONNECTED),
            takeoverWhenIdle = flag(AppSetting.TAKEOVER_IDLE),
            takeoverWhenMusic = flag(AppSetting.TAKEOVER_MUSIC),
            takeoverWhenCall = flag(AppSetting.TAKEOVER_CALL),
            takeoverWhenRingingCall = flag(AppSetting.TAKEOVER_RINGING),
            takeoverWhenMediaStart = flag(AppSetting.TAKEOVER_MEDIA),
            useAlternateHeadTrackingPackets = flag(AppSetting.ALTERNATE_HEAD),
            conversationalAwarenessVolume = if (current.conversationalAwarenessVolume.roundToInt() == volume)
                current.conversationalAwarenessVolume else volume.toFloat(),
            cameraPackageValue = if (current.showCameraDialog) current.cameraPackageValue else values.getValue(AppSetting.CAMERA_PACKAGE) as String,
            connectionSuccessful = flag(AppSetting.CONNECTION),
            showBottomSheetPopup = flag(AppSetting.BOTTOM_POPUP),
            showIslandPopup = flag(AppSetting.ISLAND_POPUP), m3eEnabled = flag(AppSetting.MATERIAL),
            isPremium = paidPremium || premium.remainingMillis > 0,
            timeUntilFOSSPremiumExpiry = premium.remainingMillis
        ) }
        if (premium.changes.isNotEmpty()) preferences.setPremium(premium.changes)
    }

    private fun set(key: AppSetting, value: Any?, update: (AppSettingsUiState) -> AppSettingsUiState) {
        if (!closed && _uiState.value.localSettingsReady && preferences.set(key, value)) _uiState.update(update)
    }
    fun refreshLocalSettings() { if (!closed) subscription.retry() }
    fun refreshBilling() { if (!closed) BillingManager.provider.queryPurchases() }
    override fun onCleared() { closed = true; subscription.close() }

    fun setShowPhoneBatteryInWidget(enabled: Boolean) {
        set(AppSetting.PHONE_BATTERY, enabled) { it.copy(showPhoneBatteryInWidget = enabled) }
    }

    fun setConversationalAwarenessPauseMusicEnabled(enabled: Boolean) {
        set(AppSetting.PAUSE_CONVERSATION, enabled) { it.copy(conversationalAwarenessPauseMusicEnabled = enabled) }
    }

    fun setRelativeConversationalAwarenessVolumeEnabled(enabled: Boolean) {
        set(AppSetting.RELATIVE_VOLUME, enabled) { it.copy(relativeConversationalAwarenessVolumeEnabled = enabled) }
    }

    fun setDisconnectWhenNotWearing(enabled: Boolean) {
        set(AppSetting.DISCONNECT_UNWORN, enabled) { it.copy(disconnectWhenNotWearing = enabled) }
    }

    fun setTakeoverWhenDisconnected(enabled: Boolean) {
        set(AppSetting.TAKEOVER_DISCONNECTED, enabled) { it.copy(takeoverWhenDisconnected = enabled) }
    }

    fun setTakeoverWhenIdle(enabled: Boolean) {
        set(AppSetting.TAKEOVER_IDLE, enabled) { it.copy(takeoverWhenIdle = enabled) }
    }

    fun setTakeoverWhenMusic(enabled: Boolean) {
        set(AppSetting.TAKEOVER_MUSIC, enabled) { it.copy(takeoverWhenMusic = enabled) }
    }

    fun setTakeoverWhenCall(enabled: Boolean) {
        set(AppSetting.TAKEOVER_CALL, enabled) { it.copy(takeoverWhenCall = enabled) }
    }

    fun setTakeoverWhenRingingCall(enabled: Boolean) {
        set(AppSetting.TAKEOVER_RINGING, enabled) { it.copy(takeoverWhenRingingCall = enabled) }
    }

    fun setTakeoverWhenMediaStart(enabled: Boolean) {
        set(AppSetting.TAKEOVER_MEDIA, enabled) { it.copy(takeoverWhenMediaStart = enabled) }
    }

    fun setUseAlternateHeadTrackingPackets(enabled: Boolean) {
        set(AppSetting.ALTERNATE_HEAD, enabled) { it.copy(useAlternateHeadTrackingPackets = enabled) }
    }

    fun setConversationalAwarenessVolume(volume: Float) {
        if (!volume.isFinite()) return
        val bounded = volume.coerceIn(0f, 100f)
        set(AppSetting.VOLUME, bounded.roundToInt()) { it.copy(conversationalAwarenessVolume = bounded) }
    }

    fun setShowCameraDialog(show: Boolean) {
        if (!closed) _uiState.update { it.copy(showCameraDialog = show) }
    }

    fun setCameraPackageValue(value: String) {
        if (!closed) _uiState.update { it.copy(cameraPackageValue = value) }
    }

    fun setCameraPackageError(error: String?) {
        if (!closed) _uiState.update { it.copy(cameraPackageError = error) }
    }

    fun saveCameraPackage() {
        val value = _uiState.value.cameraPackageValue.takeUnless { it.isBlank() }
        set(AppSetting.CAMERA_PACKAGE, value) { it.copy(showCameraDialog = false) }
    }

    fun refreshXposedPreferences() {
        if (!closed) RemoteXposedPreferences.requestRefresh()
    }

    fun setForceLegacyL2capWorkaround(enabled: Boolean): Boolean {
        if (closed || !RemoteXposedPreferences.setBoolean(RemoteXposedPreferences.Key.LEGACY_L2CAP, enabled)) return false
        _uiState.update { it.copy(forceLegacyL2capWorkaround = enabled) }
        return true
    }

    fun setVendorIdHook(enabled: Boolean): Boolean {
        if (closed || !RemoteXposedPreferences.setBoolean(RemoteXposedPreferences.Key.VENDOR_ID, enabled)) return false
        _uiState.update { it.copy(vendorIdHook = enabled) }
        return true
    }

    fun setShowBottomSheetPopup(enabled: Boolean) {
        set(AppSetting.BOTTOM_POPUP, enabled) { it.copy(showBottomSheetPopup = enabled) }
    }

    fun setShowIslandPopup(enabled: Boolean) {
        set(AppSetting.ISLAND_POPUP, enabled) { it.copy(showIslandPopup = enabled) }
    }

    fun setm3eEnabled(enabled: Boolean) {
        set(AppSetting.MATERIAL, enabled) { it.copy(m3eEnabled = enabled) }
    }
}
