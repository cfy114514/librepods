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


import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import me.kavishdevar.librepods.utils.KeyedWorkSession
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG="AppListenerService"

@Volatile var cameraOpen = false
    private set
@Volatile private var cameraListenerOwner: AppListenerService? = null

class AppListenerService: AccessibilityService() {
    @Volatile private var active = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferenceRevision = AtomicLong()
    private val preferenceResources = CloseableResourceScope(closeResources = ::closeResourcesOnIo)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var preferenceRegistration: DeferredRegistration? = null
    private val foreground = CameraForegroundState()
    private val preferenceUpdates = KeyedWorkSession<Unit, Long>(setOf(Unit), Dispatchers.IO,
        onError = { Log.w(TAG, "Could not read camera preferences", it) }, consume = ::loadCameraPreferences)
    private var screenReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { clearCamera() }
    }
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "custom_camera_package" || key == null) requestCameraPreferences()
    }

    override fun onCreate() {
        super.onCreate()
        cameraListenerOwner = this
        active = true
        clearCamera()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        requestCameraPreferences()
    }

    override fun onDestroy() {
        active = false
        preferenceResources.close()
        preferenceUpdates.close()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenReceiverRegistered = false
        }
        clearCamera()
        if (cameraListenerOwner === this) cameraListenerOwner = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(ev: AccessibilityEvent?) {
        if (!isCurrent()) return
        try {
            if (ev?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = ev.packageName?.toString() ?: return
                foreground.windowChanged(pkg)
                publishCameraState()
            }
        } catch(e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}")
        }
    }

    private fun publishCameraState() {
        if (cameraListenerOwner !== this) return
        if (cameraOpen == foreground.active) return
        cameraOpen = foreground.active
        if (cameraOpen) ServiceManager.getService()?.cameraOpened()
        else ServiceManager.getService()?.cameraClosed()
    }

    private fun clearCamera() {
        if (cameraListenerOwner !== this) return
        foreground.reset()
        publishCameraState()
    }

    private fun isCurrent(): Boolean = active && cameraListenerOwner === this

    private fun requestCameraPreferences() {
        if (isCurrent()) preferenceUpdates.offer(Unit, preferenceRevision.incrementAndGet())
    }

    private fun loadCameraPreferences(revision: Long) {
        if (!isCurrent()) return
        val shared = prefs
        if (preferenceRegistration == null) {
            val registration = preferenceResources.track(DeferredRegistration {
                shared.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
            })
            try {
                shared.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
                registration.didRegister()
                preferenceRegistration = registration
            } catch (error: Exception) {
                // Unregister is safe even if a preferences implementation partially registered.
                runCatching { shared.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener) }
                registration.close()
                preferenceResources.release(registration)
                throw error
            }
        }
        if (!isCurrent()) return
        val custom = shared.getString("custom_camera_package", null)
        mainHandler.post {
            if (isCurrent() && preferenceRevision.get() == revision) {
                foreground.customPackageChanged(custom)
                publishCameraState()
            }
        }
    }

    override fun onInterrupt() { clearCamera() }
}
