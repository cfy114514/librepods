package me.kavishdevar.librepods.presentation.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.BATTERY_SESSION_DISPLAY_INTERVAL_MS
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatterySessionEstimateState
import me.kavishdevar.librepods.data.BatterySessionObserver
import me.kavishdevar.librepods.data.BatterySessionSidePresentation
import me.kavishdevar.librepods.data.BatterySessionSnapshot
import me.kavishdevar.librepods.data.BatterySessionStore
import me.kavishdevar.librepods.data.batterySessionPresentation
import me.kavishdevar.librepods.services.ServiceManager

/** A lifecycle-scoped display clock expires evidence; it never records battery observations. */
@Composable
internal fun BatterySessionCard(address: String, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val observer = remember(address, lifecycle) { BatterySessionObserver(address) }
    val empty = remember(address) { batterySessionPresentation(address, BatterySessionSnapshot(), 0) }
    var state by remember(observer) { mutableStateOf(empty) }
    val display = remember(observer) {
        val clock = flow {
            while (true) {
                emit(SystemClock.elapsedRealtime())
                delay(BATTERY_SESSION_DISPLAY_INTERVAL_MS)
            }
        }
        observer.presentations(BatterySessionStore.snapshots, clock, SystemClock::elapsedRealtime)
            .flowOn(Dispatchers.Default)
    }
    DisposableEffect(observer, lifecycle) {
        val events = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                observer.stop()
                state = empty
            }
        }
        lifecycle.addObserver(events)
        onDispose { lifecycle.removeObserver(events); observer.close() }
    }
    LaunchedEffect(observer, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            display.collect { state = it }
        }
    }
    LaunchedEffect(observer, lifecycle, "availability") {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observer.start()
            try {
                while (isActive) {
                    observer.refreshAvailability(SystemClock::elapsedRealtime) {
                        ServiceManager.getService()?.deviceObservationAvailable() == true
                    }
                    delay(BATTERY_SESSION_DISPLAY_INTERVAL_MS)
                }
            } finally {
                observer.stop()
            }
        }
    }
    StyledList(modifier = modifier, title = stringResource(R.string.battery_session_title),
        description = stringResource(R.string.battery_session_description)) {
        item { _, _ ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                BatterySessionSide(state.left)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                BatterySessionSide(state.right)
            }
        }
    }
}

@Composable
private fun BatterySessionSide(side: BatterySessionSidePresentation) {
    val name = stringResource(if (side.component == BatteryComponent.LEFT) R.string.left else R.string.right)
    val level = side.level?.let { stringResource(R.string.battery_session_level, it) }
    Text(if (level == null) name else "$name · $level", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    val message = when (side.state) {
        BatterySessionEstimateState.DISCONNECTED -> stringResource(R.string.battery_session_disconnected)
        BatterySessionEstimateState.WAITING -> stringResource(R.string.battery_session_waiting)
        BatterySessionEstimateState.CHARGING -> stringResource(R.string.battery_session_charging)
        BatterySessionEstimateState.UNAVAILABLE -> stringResource(R.string.battery_session_unavailable)
        BatterySessionEstimateState.STALE -> stringResource(R.string.battery_session_stale)
        BatterySessionEstimateState.CLOCK_CHANGED -> stringResource(R.string.battery_session_clock_changed)
        BatterySessionEstimateState.COLLECTING -> stringResource(R.string.battery_session_collecting)
        BatterySessionEstimateState.INSUFFICIENT_DRAIN -> stringResource(R.string.battery_session_insufficient_drain)
        BatterySessionEstimateState.ROUGH_ESTIMATE -> {
            val minutes = requireNotNull(side.roughRemainingMinutes)
            if (minutes == 0) stringResource(R.string.battery_session_rough_under_five)
            else stringResource(R.string.battery_session_rough_minutes, minutes)
        }
    }
    Text(message, style = MaterialTheme.typography.bodyMedium)
    if (side.points.size >= 2 && side.observedMinutes > 0) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.battery_session_trend, side.points.first().level,
            side.points.last().level, side.observedMinutes),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BatterySessionPlot(side)
    }
}

/** Actual percentage points over elapsed time, on a fixed 0–100% vertical scale. */
@Composable
private fun BatterySessionPlot(side: BatterySessionSidePresentation) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(40.dp).clearAndSetSemantics { }) {
        val first = side.points.first().receivedAtElapsedMs
        val span = (side.points.last().receivedAtElapsedMs - first).coerceAtLeast(1)
        val inset = 2.dp.toPx()
        val width = (size.width - inset * 2).coerceAtLeast(0f)
        val height = (size.height - inset * 2).coerceAtLeast(0f)
        for (fraction in listOf(0f, 0.5f, 1f)) {
            val y = inset + height * fraction
            drawLine(grid, Offset(inset, y), Offset(inset + width, y), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        side.points.forEachIndexed { index, point ->
            val x = inset + ((point.receivedAtElapsedMs - first).toDouble() / span * width).toFloat()
            val y = inset + (1f - point.level / 100f) * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
