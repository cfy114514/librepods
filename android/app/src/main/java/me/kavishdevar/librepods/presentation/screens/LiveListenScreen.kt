package me.kavishdevar.librepods.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.LiveListenController
import me.kavishdevar.librepods.audio.LiveListenError
import me.kavishdevar.librepods.audio.LiveListenPhase
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.services.LiveListenService

@Composable
fun LiveListenScreen() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val state by LiveListenController.state.collectAsState()
    var gain by remember { mutableFloatStateOf(preferences.getFloat("live_listen_gain", 1f).takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f) }
    var permissionDenied by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) {
            LiveListenController.gain = gain
            LiveListenService.start(context)
        }
    }
    val material = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (material) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(topPadding + 16.dp))
        Text(stringResource(R.string.live_listen_description), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        StyledToggle(
            label = stringResource(R.string.live_listen),
            description = stringResource(when (state.phase) {
                LiveListenPhase.STARTING -> R.string.live_listen_starting
                LiveListenPhase.LISTENING -> R.string.live_listen_notification
                LiveListenPhase.STOPPING -> R.string.live_listen_stopping
                LiveListenPhase.IDLE -> R.string.live_listen_off
            }),
            checked = state.phase == LiveListenPhase.STARTING || state.phase == LiveListenPhase.LISTENING,
            enabled = state.phase != LiveListenPhase.STOPPING,
            onCheckedChange = { enabled ->
                if (!enabled) LiveListenService.stop(context)
                else {
                    permissionDenied = false
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        LiveListenController.gain = gain
                        LiveListenService.start(context)
                    } else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.live_listen_gain), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        StyledList {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { value ->
                StyledListItem(name = "${value}×", selected = gain == value, onClick = {
                    gain = value
                    LiveListenController.gain = value
                    preferences.edit { putFloat("live_listen_gain", value) }
                })
            }
        }
        val error = if (permissionDenied) LiveListenError.PERMISSION else state.error
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(when (error) {
                LiveListenError.PERMISSION -> R.string.live_listen_permission
                LiveListenError.NO_HEADPHONES -> R.string.live_listen_no_headphones
                LiveListenError.NO_MICROPHONE -> R.string.live_listen_no_microphone
                LiveListenError.MICROPHONE_BUSY -> R.string.translation_microphone_busy
                LiveListenError.FOCUS_LOST -> R.string.live_listen_focus_lost
                LiveListenError.ROUTE_CHANGED -> R.string.live_listen_route_changed
                else -> R.string.live_listen_audio_error
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (error == LiveListenError.PERMISSION) {
                Spacer(Modifier.height(8.dp))
                StyledListItem(name = stringResource(R.string.live_listen_open_permissions), onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.live_listen_latency), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(bottomPadding))
    }
}
