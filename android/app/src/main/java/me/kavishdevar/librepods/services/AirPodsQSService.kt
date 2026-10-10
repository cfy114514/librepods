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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import me.kavishdevar.librepods.QuickSettingsDialogActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.NoiseControlMode
import kotlin.io.encoding.ExperimentalEncodingApi
import java.io.Closeable
import me.kavishdevar.librepods.utils.OwnedCallbackLifecycle

class AirPodsQSService : TileService() {

    private lateinit var sharedPreferences: SharedPreferences
    private var currentAncMode: Int = NoiseControlMode.OFF.ordinal + 1
    private var isAirPodsConnected: Boolean = false
    private val listeningCallbacks = OwnedCallbackLifecycle()

    private val ancStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPodsNotifications.ANC_DATA) {
                val newMode = intent.getIntExtra("data", NoiseControlMode.OFF.ordinal + 1)
                Log.d("AirPodsQSService", "Received ANC update: $newMode")
                currentAncMode = newMode
                updateTile()
            }
        }
    }

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPodsNotifications.AIRPODS_CONNECTED -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_CONNECTED")
                    isAirPodsConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
                    currentAncMode =
                        ServiceManager.getService()?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)
                    updateTile()
                }
                AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_DISCONNECTED")
                    // A delayed disconnect for an old socket must not disable a replacement link.
                    isAirPodsConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
                    currentAncMode = ServiceManager.getService()?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)
                    updateTile()
                }
            }
        }
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("off_listening_mode", "off_listening_mode_address", "airpods_model_number", "airpods_model_address", "mac_address")) {
            Log.d("AirPodsQSService", "Preference changed: $key")
            updateTile()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
    }

    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    override fun onStartListening() {
        super.onStartListening()
        Log.d("AirPodsQSService", "onStartListening")

        val service = ServiceManager.getService()
        isAirPodsConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
        currentAncMode = service?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)

        if (currentAncMode == NoiseControlMode.OFF.ordinal + 1 && !isOffModeEnabled()) {
             currentAncMode = NoiseControlMode.TRANSPARENCY.ordinal + 1
        }

        val ancIntentFilter = IntentFilter(AirPodsNotifications.ANC_DATA)
        val availabilityIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        try {
            listeningCallbacks.start(this) { session ->
                val registrations = CloseableResourceScope()
                fun guarded(receiver: BroadcastReceiver) = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (session.isActive) receiver.onReceive(context, intent)
                    }
                }
                val anc = guarded(ancStatusReceiver)
                val availability = guarded(availabilityReceiver)
                val preferences = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (session.isActive) preferenceChangeListener.onSharedPreferenceChanged(prefs, key)
                }
                try {
                    registerReceiver(anc, ancIntentFilter, RECEIVER_NOT_EXPORTED)
                    registrations.track(Closeable { unregisterReceiver(anc) })
                    registerReceiver(availability, availabilityIntentFilter, RECEIVER_NOT_EXPORTED)
                    registrations.track(Closeable { unregisterReceiver(availability) })
                    sharedPreferences.registerOnSharedPreferenceChangeListener(preferences)
                    registrations.track(Closeable { sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferences) })
                } catch (error: Throwable) {
                    registrations.close()
                    throw error
                }
                val cleanup: () -> Unit = { registrations.close() }
                cleanup
            }
            Log.d("AirPodsQSService", "Receivers registered")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error registering receivers: $e")
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d("AirPodsQSService", "onStopListening")
        listeningCallbacks.stop(this)
    }

    override fun onDestroy() {
        listeningCallbacks.stop(this)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        Log.d("AirPodsQSService", "onClick - Current state: $isAirPodsConnected, Current mode: $currentAncMode")
        if (!isAirPodsConnected) {
            Log.d("AirPodsQSService", "Tile clicked but AirPods not connected.")
            return
        }

        val clickBehavior = "cycle" // sharedPreferences.getString("qs_click_behavior", "dialog") ?: "dialog"

        if (clickBehavior == "dialog") {
            launchDialogActivity()
        } else {
            cycleAncMode()
        }
    }

    private fun launchDialogActivity() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, QuickSettingsDialogActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                val intent = Intent(this, QuickSettingsDialogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                @Suppress("DEPRECATION")
                @SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            Log.d("AirPodsQSService", "Called startActivityAndCollapse for QuickSettingsDialogActivity")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error launching QuickSettingsDialogActivity: $e")
        }
    }

    private fun cycleAncMode() {
        val service = ServiceManager.getService()
        if (service == null) {
            Log.d("AirPodsQSService", "Tile clicked (cycle mode) but service is null.")
            return
        }
        val nextMode = getNextAncMode() ?: return
        Log.d("AirPodsQSService", "Cycling ANC mode to: $nextMode")
        service.setListeningModeAsync(nextMode)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        Log.d("AirPodsQSService", "updateTile - Connected: $isAirPodsConnected, Mode: $currentAncMode")

        val deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods"

        if (isAirPodsConnected && getAvailableModes().isNotEmpty()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getModeLabel(currentAncMode)
            tile.subtitle = deviceName
            tile.icon = Icon.createWithResource(this, getModeIcon(currentAncMode))
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "AirPods"
            tile.subtitle = if (isAirPodsConnected) getString(R.string.noise_control_unavailable) else "Disconnected"
            tile.icon = Icon.createWithResource(this, R.drawable.airpods)
        }

        try {
            tile.updateTile()
            Log.d("AirPodsQSService", "Tile updated successfully")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error updating tile: $e")
        }
    }

    private fun isOffModeEnabled(): Boolean {
        return ServiceManager.getService()?.isOffListeningModeEnabled()
            ?: me.kavishdevar.librepods.data.cachedOffListeningMode(sharedPreferences.all)
    }

    private fun getAvailableModes(): List<Int> =
        ServiceManager.getService()?.supportedListeningModes()
            ?: me.kavishdevar.librepods.data.cachedListeningModes(this)

    private fun getNextAncMode(): Int? =
        me.kavishdevar.librepods.data.nextListeningMode(currentAncMode, getAvailableModes())

    private fun getModeLabel(mode: Int): String {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> "Off"
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> "Transparency"
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> "Adaptive"
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> "Noise Cancellation"
            else -> "Unknown"
        }
    }

     private fun getModeIcon(mode: Int): Int {
         return when (mode) {
             NoiseControlMode.OFF.ordinal + 1 -> R.drawable.noise_cancellation
             NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.transparency
             NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.adaptive
             NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.noise_cancellation
             else -> R.drawable.airpods
         }
     }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d("AirPodsQSService", "Tile added")
    }
}
