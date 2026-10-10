package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.StartupSettingsSnapshot
import me.kavishdevar.librepods.presentation.viewmodel.StartupSettingsState

@Composable
fun StartupSettingsGate(state: StartupSettingsState, retry: () -> Unit, content: @Composable (StartupSettingsSnapshot) -> Unit) {
    val snapshot = state.snapshot
    LibrePodsTheme(m3eEnabled = snapshot?.m3eEnabled ?: false) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (snapshot == null) {
                Column(Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    StartupSettingsStatus(state.failed, retry)
                }
            } else {
                content(snapshot)
                if (state.failed) {
                    Surface(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StartupSettingsStatus(true, retry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupSettingsStatus(failed: Boolean, retry: () -> Unit) {
    Text(stringResource(if (failed) R.string.startup_settings_error else R.string.startup_settings_loading))
    if (failed) Button(onClick = retry) { Text(stringResource(R.string.startup_settings_retry)) }
    else CircularProgressIndicator(Modifier.size(24.dp))
}
