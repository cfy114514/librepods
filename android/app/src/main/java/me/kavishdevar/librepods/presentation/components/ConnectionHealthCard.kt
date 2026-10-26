package me.kavishdevar.librepods.presentation.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.ConnectionHealthReport
import me.kavishdevar.librepods.data.ConnectionHealthRow
import me.kavishdevar.librepods.data.ConnectionHealthSnapshot
import me.kavishdevar.librepods.data.HealthBackgroundPolicy
import me.kavishdevar.librepods.data.HealthProfileState
import me.kavishdevar.librepods.data.HealthRowKind
import me.kavishdevar.librepods.data.HealthStatus
import me.kavishdevar.librepods.data.connectionHealthReport
import me.kavishdevar.librepods.presentation.ConnectionHealthAction
import me.kavishdevar.librepods.presentation.ConnectionHealthObserver
import me.kavishdevar.librepods.presentation.openConnectionHealthSettings
import me.kavishdevar.librepods.presentation.performConnectionHealthAction
import me.kavishdevar.librepods.presentation.readConnectionHealthPlatform
import me.kavishdevar.librepods.presentation.theme.AppleSettingsCardShape
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

/** Attach only where visible. Disposing the card stops all observation, including queued refreshes. */
@Composable
fun ObservedConnectionHealthCard(
    selectedPeer: String?,
    selectionVersion: Long,
    serviceBindingId: Long,
    readService: () -> ConnectionHealthSnapshot?,
    onOpenDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preview = LocalInspectionMode.current
    val currentReadService by rememberUpdatedState(readService)
    val observer = remember(selectedPeer, selectionVersion, serviceBindingId, lifecycle) { ConnectionHealthObserver(selectedPeer) }
    val refresh = remember(observer) { Channel<Unit>(Channel.CONFLATED) }
    var report by remember(observer) { mutableStateOf(connectionHealthReport(selectedPeer, null, null, SystemClock.elapsedRealtime())) }
    var refreshing by remember(observer) { mutableStateOf(false) }
    var failedAction by remember(observer) { mutableStateOf(false) }
    var lastRefresh by remember(observer) { mutableStateOf(-1_000L) }

    DisposableEffect(observer, refresh, lifecycle) {
        val events = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                // Fence immediately; cancellation of a blocking Binder read can finish later.
                observer.stop()
                report = connectionHealthReport(selectedPeer, null, null, SystemClock.elapsedRealtime())
            }
        }
        lifecycle.addObserver(events)
        onDispose { lifecycle.removeObserver(events); observer.close(); refresh.close() }
    }
    LaunchedEffect(observer, lifecycle, preview, "expiry") {
        if (preview) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                observer.currentReport(SystemClock.elapsedRealtime())?.let { report = it }
                delay(5_000L)
            }
        }
    }
    LaunchedEffect(observer, lifecycle, preview) {
        if (preview) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observer.start()
            // A return from settings starts with unknowns until the fresh permission read completes.
            report = connectionHealthReport(selectedPeer, null, null, SystemClock.elapsedRealtime())
            try {
                while (isActive) {
                    val ticket = observer.beginRead() ?: break
                    refreshing = true
                    val reader = currentReadService
                    val result = withContext(Dispatchers.IO) {
                        val platform = readConnectionHealthPlatform(context)
                        val service = runCatching { reader() }.getOrNull()
                        service to platform
                    }
                    observer.publish(ticket, result.first, result.second, SystemClock.elapsedRealtime())?.let { report = it }
                    refreshing = false
                    withTimeoutOrNull(5_000L) { refresh.receiveCatching() }
                }
            } finally {
                observer.stop()
                refreshing = false
            }
        }
    }
    ConnectionHealthCard(report, refreshing,
        onRefresh = {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRefresh >= 1_000 && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lastRefresh = now
                refresh.trySend(Unit)
            }
        },
        onAction = { action ->
            failedAction = !performConnectionHealthAction(action,
                openSettings = { openConnectionHealthSettings(context, it) }, openDiagnostics = onOpenDiagnostics)
        },
        diagnosticsAvailable = onOpenDiagnostics != null,
        actionFailed = failedAction,
        modifier = modifier)
}

@Composable
fun ConnectionHealthCard(
    report: ConnectionHealthReport,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAction: (ConnectionHealthAction) -> Unit,
    diagnosticsAvailable: Boolean = false,
    actionFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = if (LocalDesignSystem.current == DesignSystem.Material) RoundedCornerShape(24.dp) else AppleSettingsCardShape
    Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.connection_health_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.connection_health_scope), style = MaterialTheme.typography.bodySmall)
            report.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(healthTitle(row.kind)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(healthStatus(row.status)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(stringResource(healthDetail(row)), style = MaterialTheme.typography.bodySmall)
                    if (row.status == HealthStatus.STALE && row.lastProfileState != null) {
                        Text(stringResource(R.string.connection_health_last_profile,
                            stringResource(healthProfileState(row.lastProfileState))), style = MaterialTheme.typography.bodySmall)
                    }
                    if (row.kind == HealthRowKind.SERVICE) {
                        Text(stringResource(when (report.backgroundPolicy) {
                            HealthBackgroundPolicy.RESTRICTED -> R.string.connection_health_background_restricted
                            HealthBackgroundPolicy.OPTIMIZED -> R.string.connection_health_background_optimized
                            HealthBackgroundPolicy.UNRESTRICTED -> R.string.connection_health_background_unrestricted
                            HealthBackgroundPolicy.UNKNOWN -> R.string.connection_health_background_unknown
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(stringResource(R.string.connection_health_freshness), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(ConnectionHealthAction.BLUETOOTH_SETTINGS) }) {
                    Text(stringResource(R.string.connection_health_bluetooth_settings))
                }
                if (report.permissionRequired || report.backgroundPolicy != HealthBackgroundPolicy.UNRESTRICTED) {
                    OutlinedButton(onClick = { onAction(ConnectionHealthAction.APP_SETTINGS) }) {
                        Text(stringResource(R.string.connection_health_app_settings))
                    }
                }
                if (report.backgroundPolicy == HealthBackgroundPolicy.OPTIMIZED || report.backgroundPolicy == HealthBackgroundPolicy.RESTRICTED) {
                    OutlinedButton(onClick = { onAction(ConnectionHealthAction.BATTERY_SETTINGS) }) {
                        Text(stringResource(R.string.connection_health_battery_settings))
                    }
                }
                if (diagnosticsAvailable) TextButton(onClick = { onAction(ConnectionHealthAction.DIAGNOSTICS) }) {
                    Text(stringResource(R.string.connection_health_diagnostics))
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(stringResource(if (refreshing) R.string.connection_health_refreshing else R.string.connection_health_refresh))
                }
            }
            if (actionFailed) Text(stringResource(R.string.connection_health_action_failed),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun healthTitle(kind: HealthRowKind): Int = when (kind) {
    HealthRowKind.MEDIA -> R.string.connection_health_media
    HealthRowKind.CALLS -> R.string.connection_health_calls
    HealthRowKind.CONTROL -> R.string.connection_health_control
    HealthRowKind.SERVICE -> R.string.connection_health_service
}

private fun healthProfileState(state: HealthProfileState): Int = healthStatus(when (state) {
    HealthProfileState.CONNECTED -> HealthStatus.CONNECTED
    HealthProfileState.DISCONNECTED -> HealthStatus.DISCONNECTED
    HealthProfileState.CONNECTING -> HealthStatus.CONNECTING
    HealthProfileState.DISCONNECTING -> HealthStatus.DISCONNECTING
})

private fun healthStatus(status: HealthStatus): Int = when (status) {
    HealthStatus.NO_DEVICE -> R.string.connection_health_no_device
    HealthStatus.PERMISSION_REQUIRED -> R.string.connection_health_permission
    HealthStatus.BLUETOOTH_OFF -> R.string.connection_health_bluetooth_off
    HealthStatus.UNKNOWN -> R.string.connection_health_unknown
    HealthStatus.STALE -> R.string.connection_health_stale
    HealthStatus.DISCONNECTED -> R.string.connection_health_disconnected
    HealthStatus.CONNECTING -> R.string.connection_health_connecting
    HealthStatus.CONNECTED -> R.string.connection_health_connected
    HealthStatus.DISCONNECTING -> R.string.connection_health_disconnecting
    HealthStatus.WAITING_FOR_DATA -> R.string.connection_health_waiting
    HealthStatus.RECEIVING_DATA -> R.string.connection_health_receiving
    HealthStatus.SERVICE_RESPONDING -> R.string.connection_health_responding
    HealthStatus.SERVICE_STARTING -> R.string.connection_health_starting
    HealthStatus.SERVICE_STOPPED -> R.string.connection_health_stopped
}

private fun healthDetail(row: ConnectionHealthRow): Int = when (row.status) {
    HealthStatus.NO_DEVICE -> R.string.connection_health_detail_no_device
    HealthStatus.PERMISSION_REQUIRED -> R.string.connection_health_detail_permission
    HealthStatus.BLUETOOTH_OFF -> R.string.connection_health_detail_bluetooth_off
    HealthStatus.STALE -> if (row.kind == HealthRowKind.CONTROL) R.string.connection_health_detail_control_stale else R.string.connection_health_detail_stale
    HealthStatus.UNKNOWN -> if (row.kind == HealthRowKind.SERVICE) R.string.connection_health_detail_service_unknown else R.string.connection_health_detail_unknown
    HealthStatus.WAITING_FOR_DATA -> R.string.connection_health_detail_waiting
    HealthStatus.RECEIVING_DATA -> R.string.connection_health_detail_receiving
    HealthStatus.SERVICE_RESPONDING -> R.string.connection_health_detail_responding
    HealthStatus.SERVICE_STARTING -> R.string.connection_health_detail_starting
    HealthStatus.SERVICE_STOPPED -> R.string.connection_health_detail_stopped
    else -> when (row.kind) {
        HealthRowKind.MEDIA -> R.string.connection_health_detail_media
        HealthRowKind.CALLS -> R.string.connection_health_detail_calls
        HealthRowKind.CONTROL -> R.string.connection_health_detail_control_disconnected
        HealthRowKind.SERVICE -> R.string.connection_health_detail_service_unknown
    }
}
