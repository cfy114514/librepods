package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.SleepTimerManager

@Composable
fun SleepTimerSettings(
    endAt: Long,
    currentMode: Int,
    targetMode: Int,
    onStartTimer: (Int, Int) -> Long,
    onCancelTimer: () -> Unit,
) {
    var timerEndAt by remember(endAt) { mutableLongStateOf(endAt) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDialog by remember { mutableStateOf(false) }
    var selectedMode by remember(targetMode) { mutableStateOf(targetMode) }
    var durationText by remember { mutableStateOf("90") }

    LaunchedEffect(timerEndAt) {
        while (timerEndAt > 0L && timerEndAt > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
        if (timerEndAt > 0L) timerEndAt = 0L
    }

    val description = if (timerEndAt > now) {
        val minutes = SleepTimerManager.remainingMinutes(timerEndAt, now)
        if (minutes >= 60) {
            stringResource(
                R.string.sleep_timer_active_hours,
                modeLabel(selectedMode),
                minutes / 60,
                minutes % 60
            )
        } else {
            stringResource(
                R.string.sleep_timer_active_minutes,
                modeLabel(selectedMode),
                minutes
            )
        }
    } else {
        stringResource(R.string.sleep_timer_description)
    }

    StyledListItem(
        name = stringResource(R.string.sleep_timer),
        description = description,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.sleep_timer_current_mode,
                            modeLabel(currentMode)
                        ),
                        fontSize = 14.sp
                    )
                    Text(
                        stringResource(R.string.sleep_timer_choose_mode),
                        fontSize = 14.sp
                    )
                    listOf(1, 3, 4, 2).forEach { mode ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedMode = mode
                            }
                        ) {
                            Text(
                                text = if (selectedMode == mode) {
                                    "✓ ${modeLabel(mode)}"
                                } else {
                                    modeLabel(mode)
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.sleep_timer_duration)) },
                        suffix = { Text(stringResource(R.string.minutes_short)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (timerEndAt > 0L) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onCancelTimer()
                                timerEndAt = 0L
                                showDialog = false
                            }
                        ) {
                            Text(stringResource(R.string.cancel_sleep_timer))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minutes = durationText.toIntOrNull()?.coerceIn(1, 24 * 60)
                            ?: return@TextButton
                        timerEndAt = onStartTimer(minutes, selectedMode)
                        showDialog = false
                    }
                ) {
                    Text(stringResource(R.string.start_timer))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
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
