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

package me.kavishdevar.librepods

// import me.kavishdevar.librepods.screens.Onboarding
// import me.kavishdevar.librepods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.android.play.core.review.ReviewManagerFactory
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.presentation.navigation.NavigationRoot
import me.kavishdevar.librepods.presentation.components.StartupSettingsGate
import me.kavishdevar.librepods.presentation.viewmodel.StartupSettingsSnapshot
import me.kavishdevar.librepods.presentation.viewmodel.StartupSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.utils.XposedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        private val nativeLoadLock = Any()
        private var nativeLoaded = false
        private fun loadNativeLibrary() {
            synchronized(nativeLoadLock) {
                if (nativeLoaded || !XposedState.isAvailable || !XposedState.bluetoothScopeEnabled) return
                try {
                    System.loadLibrary("l2c_fcr_hook")
                    nativeLoaded = true
                } catch (error: LinkageError) {
                    Log.w("MainActivity", "Could not load optional native library", error)
                }
            }
        }
    }

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LaunchedEffect(XposedState.isAvailable, XposedState.bluetoothScopeEnabled) {
                if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                    withContext(Dispatchers.IO) { loadNativeLibrary() }
                }
            }
            val startup: StartupSettingsViewModel = viewModel()
            val settings by startup.state.collectAsState()
            StartupSettingsGate(settings, startup::refresh) { snapshot ->
//                For demo screenshots
//                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
//                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

                Main(snapshot)
            }
        }
    }

    override fun onDestroy() {
        sendBroadcast(Intent(AirPodsNotifications.DISCONNECT_RECEIVERS))
        super.onDestroy()
    }
}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main(startup: StartupSettingsSnapshot) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sharedPreferences = startup.preferences

    val airPodsViewModel: AirPodsViewModel = viewModel()

    LaunchedEffect(Unit) {
        if (BuildConfig.PLAY_BUILD) {
            val now = System.currentTimeMillis()
            val firstConn = startup.firstConnectionTime
            val alreadyPrompted = startup.reviewPrompted

            val oneDay = 24 * 60 * 60 * 1000L

            if (
                firstConn != 0L &&
                !alreadyPrompted &&
                (now - firstConn) > oneDay
            ) {
                triggerReviewFlow(context as? Activity ?: return@LaunchedEffect)

                sharedPreferences.edit {
                    putBoolean("review_prompted", true)
                }
            }
        }
    }

    var onboardingComplete by remember(sharedPreferences) {
        mutableStateOf(startup.onboardingComplete)
    }

    val releaseNotesShownPrefKey = startup.releaseNotesPrefKey
    val releaseNotesShown = startup.releaseNotesShown

    DisposableEffect(context, lifecycleOwner, airPodsViewModel, onboardingComplete) {
        var disposed = false
        var currentConnection: ServiceConnection? = null
        var connectedService: AirPodsService? = null

        fun unbindService() {
            val connection = currentConnection
            // Revoke callbacks before a platform unbind can dispatch more work.
            currentConnection = null
            connectedService = null
            if (connection != null) {
                runCatching { context.unbindService(connection) }
                    .onFailure { Log.w("MainActivity", "Unable to release service binding", it) }
            }
        }

        fun bindService() {
            if (disposed || !onboardingComplete || currentConnection != null) return
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (disposed || currentConnection !== this) return
                    val service = (binder as? AirPodsService.LocalBinder)?.getService()
                    if (service == null) { unbindService(); return }
                    connectedService = service
                    airPodsViewModel.init(
                        service = service,
                        controlRepo = ControlCommandRepository(service.aacpManager),
                        sharedPreferences = sharedPreferences,
                        appContext = context.applicationContext
                    )
                    service.requestConnectionNotificationRefresh()
                    if (!sharedPreferences.contains("first_connection_successful_time")) {
                        sharedPreferences.edit {
                            putLong("first_connection_successful_time", System.currentTimeMillis())
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Android can reconnect this registration while the page remains started.
                    if (currentConnection === this) connectedService = null
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (currentConnection === this) unbindService()
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (currentConnection === this) unbindService()
                }
            }
            val intent = Intent(context, AirPodsService::class.java)
            try {
                context.startForegroundService(intent)
                currentConnection = connection
                // false can still leave an SDK registration that needs unbind.
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) unbindService()
            } catch (error: Exception) {
                unbindService()
                Log.w("MainActivity", "Unable to start or bind service", error)
            }
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                connectedService?.requestConnectionNotificationRefresh()
            }

            override fun onStart(owner: LifecycleOwner) {
                bindService()
            }

            override fun onStop(owner: LifecycleOwner) {
                unbindService()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbindService()
        }
    }

    NavigationRoot(
        showReleaseNotes = !releaseNotesShown,
        updatesShown = { sharedPreferences.edit { putBoolean(releaseNotesShownPrefKey, true) } },
        showOnboarding = !onboardingComplete,
        onboardingComplete = {
            sharedPreferences.edit { putBoolean("onboarding_complete", true) }
            onboardingComplete = true
        },
        airPodsViewModel = airPodsViewModel
    )
}

private fun triggerReviewFlow(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    val request = manager.requestReviewFlow()
    request.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val reviewInfo = task.result
            manager.launchReviewFlow(activity, reviewInfo)
        }
    }
}
