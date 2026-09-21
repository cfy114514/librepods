package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.utils.SleepTimerManager

@Composable
fun SleepTimerSettings(
    endAt: Long,
    currentMode: Int,
    targetMode: Int,
    onStartTimer: (Int, Int) -> Long,
    onCancelTimer: () -> Unit,
    initiallyOpen: Boolean = false,
) {
    var timerEndAt by remember(endAt) { mutableLongStateOf(endAt) }
    var activeTargetMode by remember(targetMode) { mutableStateOf(targetMode) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSheet by remember(initiallyOpen) { mutableStateOf(initiallyOpen) }
    var selectedMode by remember(targetMode) { mutableStateOf(targetMode) }
    var durationText by remember { mutableStateOf("90") }

    LaunchedEffect(timerEndAt) {
        while (timerEndAt > 0L && timerEndAt > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
        if (timerEndAt > 0L) timerEndAt = 0L
    }

    val isActive = timerEndAt > now
    val description = if (isActive) {
        val minutes = SleepTimerManager.remainingMinutes(timerEndAt, now)
        if (minutes >= 60) {
            stringResource(
                R.string.sleep_timer_active_hours,
                modeLabel(activeTargetMode),
                minutes / 60,
                minutes % 60
            )
        } else {
            stringResource(
                R.string.sleep_timer_active_minutes,
                modeLabel(activeTargetMode),
                minutes
            )
        }
    } else {
        stringResource(R.string.sleep_timer_not_set)
    }

    StyledList(title = stringResource(R.string.sleep_timer)) {
        StyledListItem(
            name = stringResource(R.string.sleep_timer),
            description = description,
            onClick = { showSheet = true },
            leadingContent = {
                Text(
                    text = "◷",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 25.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(28.dp)
                )
            }
        )
    }

    if (showSheet) {
            StyledBottomSheet(
                visible = true,
                onDismiss = { showSheet = false },
                backdrop = rememberLayerBackdrop(),
                opaque = true
            ) { _, _ ->
            val duration = durationText.toIntOrNull()
            val canStart = duration != null && duration in 1..(24 * 60)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.sleep_timer),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(
                        R.string.sleep_timer_current_mode,
                        modeLabel(currentMode)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sleep_timer_duration),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        modifier = Modifier
                            .width(244.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(28.dp)
                            )
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(
                            onClick = {
                                durationText = ((duration ?: 90) - 5)
                                    .coerceAtLeast(1)
                                    .toString()
                            },
                            enabled = (duration ?: 1) > 1
                        ) {
                            Text(
                                text = "−",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        BasicTextField(
                            value = durationText,
                            onValueChange = {
                                durationText = it.filter(Char::isDigit).take(4)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(52.dp)
                        )

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        Text(
                            text = stringResource(R.string.minutes_short),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        IconButton(
                            onClick = {
                                durationText = ((duration ?: 0) + 5)
                                    .coerceAtMost(24 * 60)
                                    .toString()
                            },
                            enabled = (duration ?: 0) < 24 * 60
                        ) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.sleep_timer_choose_mode),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(1, 3, 4, 2).forEach { mode ->
                        val selected = selectedMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable { selectedMode = mode }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = modeLabel(mode),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isActive) {
                    TextButton(
                        onClick = {
                            onCancelTimer()
                            timerEndAt = 0L
                            showSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cancel_sleep_timer))
                    }
                }

                Button(
                    onClick = {
                        if (!canStart) return@Button
                        val minutes = duration
                        activeTargetMode = selectedMode
                        timerEndAt = onStartTimer(minutes, selectedMode)
                        showSheet = false
                    },
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(stringResource(R.string.start_timer))
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: Int): String = when (mode) {
    1 -> stringResource(R.string.off)
    2 -> stringResource(R.string.noise_cancellation)
    3 -> stringResource(R.string.transparency)
    4 -> stringResource(R.string.adaptive)
    else -> stringResource(R.string.off)
}

@Preview(name = "Sleep timer card", showBackground = true)
@Composable
private fun SleepTimerSettingsCardPreview() {
    LibrePodsTheme(m3eEnabled = false) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            SleepTimerSettings(
                endAt = 0L,
                currentMode = 3,
                targetMode = 1,
                onStartTimer = { _, _ -> 0L },
                onCancelTimer = {}
            )
        }
    }
}

@Preview(name = "Sleep timer sheet", showBackground = true)
@Composable
private fun SleepTimerSettingsSheetPreview() {
    LibrePodsTheme(m3eEnabled = false) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            SleepTimerSettings(
                endAt = 0L,
                currentMode = 3,
                targetMode = 1,
                onStartTimer = { _, _ -> 0L },
                onCancelTimer = {},
                initiallyOpen = true
            )
        }
    }
}
