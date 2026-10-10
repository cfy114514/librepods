package me.kavishdevar.librepods.presentation.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.BatteryHistoryStore
import me.kavishdevar.librepods.data.BatteryHistoryPresentation
import me.kavishdevar.librepods.data.BatteryHistorySnapshot
import me.kavishdevar.librepods.data.batteryHistoryPresentation
import me.kavishdevar.librepods.data.HistoryTimeFormatter
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.services.ServiceManager
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

private data class HistorySettings(val address: String, val enabled: Boolean, val days: Int)

@Composable
private fun rememberHistoryUi(preview: Boolean = false): BatteryHistoryPresentation {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val store = remember(context) { BatteryHistoryStore(context) }
    fun readSettings() = HistorySettings(settings.getString("mac_address", "") ?: "",
            settings.getBoolean("show_battery_history", true), settings.getInt("battery_history_days", 7).coerceIn(1, 30))
    val values = remember(settings) { MutableStateFlow(readSettings()) }
    val pulse = remember { MutableStateFlow(0L) }
    val locale = LocalLocale.current.platformLocale
    val display = remember(store, values, pulse, locale, preview) {
        flow {
            // Each collector owns its formatter; formatting and equality run off Main.
            val formatter = DateFormat.getDateTimeInstance(
                if (preview) DateFormat.SHORT else DateFormat.MEDIUM, DateFormat.SHORT, locale)
            val textFormatter = HistoryTimeFormatter { formatter.format(Date(it)) }
            var zone = formatter.timeZone
            emitAll(combine(store.snapshots, values, pulse) { history, policy, _ ->
                val currentZone = TimeZone.getDefault()
                if (zone != currentZone) {
                    formatter.timeZone = currentZone
                    zone = currentZone
                    textFormatter.clear()
                }
                batteryHistoryPresentation(policy.address, history, policy.enabled, policy.days,
                    System.currentTimeMillis(), includeReadingTimes = !preview, formatTime = textFormatter::format)
            }.distinctUntilChanged())
        }.flowOn(Dispatchers.Default)
    }
    val initial = remember(values, preview) {
        val policy = values.value
        batteryHistoryPresentation(policy.address, BatteryHistorySnapshot(), policy.enabled,
            policy.days, System.currentTimeMillis(), !preview) { _, _ -> "" }
    }
    val state by display.collectAsStateWithLifecycle(initialValue = initial)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, pulse) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { pulse.value++; delay(60_000) }
        }
    }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "mac_address" || key == "show_battery_history" || key == "battery_history_days")
                values.value = readSettings()
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settings.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        values.value = readSettings()
        pulse.value++
    }
    return state
}

@Composable
fun BatteryHistoryScreen() {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val state = rememberHistoryUi()
    val material = LocalDesignSystem.current == DesignSystem.Material
    val top = if (material) 16.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 100.dp
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(top))
        StyledToggle(label = stringResource(R.string.show_battery_history), description = stringResource(R.string.battery_history_description),
            checked = state.enabled, onCheckedChange = { value ->
                settings.edit { putBoolean("show_battery_history", value) }
                refreshOfflineWidgets(context)
            })
        Spacer(Modifier.height(16.dp))
        StyledList {
            state.readings.forEachIndexed { index, reading ->
                val name = intArrayOf(R.string.left, R.string.right, R.string.case_alt)[index]
                StyledListItem(name = stringResource(name), onClick = null, description =
                    if (reading.level == null) stringResource(R.string.battery_history_unknown)
                    else stringResource(R.string.battery_history_reading, reading.level,
                        reading.reportedAt!!))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.battery_history_expiry), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        StyledList {
            listOf(1, 7, 30).forEach { days ->
                StyledListItem(name = stringResource(R.string.battery_history_days, days), selected = state.days == days, onClick = {
                    settings.edit { putInt("battery_history_days", days) }
                    refreshOfflineWidgets(context)
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.battery_history_limitations), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp))
    }
}

@Composable
fun BatteryHistoryPreview(onClick: () -> Unit) {
    val state = rememberHistoryUi(preview = true)
    val known = state.readings.filter { it.level != null }
    if (known.isEmpty()) return
    val names = listOf(stringResource(R.string.left), stringResource(R.string.right), stringResource(R.string.case_alt))
    val summary = state.readings.mapIndexedNotNull { index, reading ->
        reading.level?.let { "${names[index]} $it%" }
    }.joinToString(" · ")
    val oldest = state.oldestReportedAt ?: return
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.battery_history) + "\n" + summary + "\n" +
        stringResource(R.string.battery_history_oldest, oldest),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun refreshOfflineWidgets(context: Context) {
    if (ServiceManager.getService() != null) return // Its preference observer refreshes live widgets.
    BatteryWidget.requestRefresh(context)
}
