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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.sectionHeader
import kotlin.math.roundToInt

private data class ModeOption(val mode: Int, val label: Int, val icon: Int)
private val listeningOptions = listOf(
    ModeOption(1, R.string.off, R.drawable.noise_cancellation),
    ModeOption(3, R.string.transparency, R.drawable.transparency),
    ModeOption(4, R.string.adaptive, R.drawable.adaptive),
    ModeOption(2, R.string.noise_cancellation, R.drawable.noise_cancellation)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NoiseControlSettings(
    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    supportedModes: List<Int> = listOf(1, 3, 4, 2)
) {
    val options = remember(showOffListeningMode, supportedModes) {
        listeningOptions.filter { it.mode in supportedModes && (it.mode != 1 || showOffListeningMode) }
    }
    if (options.isEmpty()) return
    val selectedIndex = options.indexOfFirst { it.mode == noiseControlModeValue }
    val material = LocalDesignSystem.current == DesignSystem.Material
    Column {
        Text(
            stringResource(R.string.noise_control),
            color = if (material) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
            style = MaterialTheme.typography.labelSmallEmphasized,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp)
        )
        if (material) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                options.forEachIndexed { index, option ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ToggleButton(
                            checked = selectedIndex == index,
                            onCheckedChange = { if (it) onNoiseControlModeChanged(option.mode) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = ToggleButtonDefaults.toggleButtonColors().copy(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(ImageBitmap.imageResource(option.icon), null, modifier = Modifier.size(42.dp))
                        }
                        Text(stringResource(option.label), style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            AppleListeningSelector(options, selectedIndex, onNoiseControlModeChanged)
        }
    }
}

@Composable
private fun AppleListeningSelector(options: List<ModeOption>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF1C1C1E) else Color(0xFFE3E3E8)
    val foreground = if (dark) Color.White else Color.Black
    val selection = if (dark) Color(0xBF5C5A5F) else Color.White
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val width = maxWidth / options.size
        val density = LocalDensity.current
        val widthPx = with(density) { width.toPx() }
        var dragging by remember(options, widthPx) { mutableStateOf(false) }
        var dragOffset by remember(options, widthPx) { mutableFloatStateOf(0f) }
        val selectedOffset = selectedIndex.coerceAtLeast(0) * widthPx
        val offset by animateFloatAsState(
            if (dragging) dragOffset else selectedOffset,
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "selector"
        )
        // Device replies derive selection directly. Composition never writes selected/divider state.
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(background, RoundedCornerShape(28.dp))
                .draggable(rememberDraggableState { delta ->
                    dragOffset = (dragOffset + delta).coerceIn(0f, widthPx * options.lastIndex)
                }, Orientation.Horizontal,
                    onDragStarted = { dragOffset = selectedOffset; dragging = true },
                    onDragStopped = {
                        val index = if (widthPx > 0) (dragOffset / widthPx).roundToInt().coerceIn(options.indices) else 0
                        dragging = false
                        if (index != selectedIndex) onSelect(options[index].mode)
                    })) {
                Box(Modifier.width(width).fillMaxHeight().offset { IntOffset(offset.roundToInt(), 0) }
                    .alpha(if (selectedIndex >= 0 || dragging) 1f else 0f).padding(3.dp)
                    .background(selection, RoundedCornerShape(26.dp)))
                Row(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        NoiseControlButton(ImageBitmap.imageResource(option.icon),
                            onClick = { if (index != selectedIndex) onSelect(option.mode) },
                            textColor = foreground, modifier = Modifier.weight(1f), usePadding = false)
                        if (index < options.lastIndex) VerticalDivider(
                            Modifier.padding(vertical = 10.dp).alpha(if (selectedIndex == index || selectedIndex == index + 1) 0f else 1f),
                            thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                options.forEach { option -> Text(stringResource(option.label), color = foreground,
                    fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Preview
@Composable
fun NoiseControlSettingsPreview() {
    LibrePodsTheme { NoiseControlSettings(true, 3, {}) }
}
