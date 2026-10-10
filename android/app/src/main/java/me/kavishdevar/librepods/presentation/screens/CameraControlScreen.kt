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

package me.kavishdevar.librepods.presentation.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.services.AppListenerService
import me.kavishdevar.librepods.services.cameraActionFromString
import me.kavishdevar.librepods.services.validCameraPackage

@Composable
fun CameraControlScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(cameraActionFromString(prefs.getString("camera_action", null))) }
    var packageName by remember { mutableStateOf(prefs.getString("custom_camera_package", "") ?: "") }
    var listenerEnabled by remember { mutableStateOf(cameraListenerEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { listenerEnabled = cameraListenerEnabled(context) }
    val material = LocalDesignSystem.current == DesignSystem.Material
    val top = if (material) 16.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 100.dp
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(top))
        Text(stringResource(R.string.camera_remote_requirements), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        StyledList {
            StyledListItem(name = stringResource(R.string.off), selected = selected == null, onClick = {
                selected = null
                prefs.edit { remove("camera_action") }
            })
            listOf(StemPressType.SINGLE_PRESS to R.string.press_once, StemPressType.LONG_PRESS to R.string.press_and_hold_airpods).forEach { (action, label) ->
                StyledListItem(name = stringResource(label), selected = selected == action, onClick = {
                    selected = action
                    prefs.edit { putString("camera_action", action.name) }
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.camera_control_description), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        StyledListItem(name = stringResource(R.string.camera_listener_settings),
            description = stringResource(if (listenerEnabled) R.string.on else R.string.off),
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        Spacer(Modifier.height(16.dp))
        val valid = packageName.isBlank() || validCameraPackage(packageName.trim())
        OutlinedTextField(value = packageName, onValueChange = { packageName = it },
            label = { Text(stringResource(R.string.custom_camera_package)) }, singleLine = true,
            isError = !valid, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = valid, onClick = {
            prefs.edit {
                if (packageName.isBlank()) remove("custom_camera_package")
                else putString("custom_camera_package", packageName.trim())
            }
        }) { Text(stringResource(R.string.camera_save_package)) }
        Text(stringResource(R.string.camera_custom_package_help), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp))
    }
}

private fun cameraListenerEnabled(context: Context): Boolean {
    val component = ComponentName(context, AppListenerService::class.java)
    return context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == component.packageName && it.resolveInfo.serviceInfo.name == component.className
        }
}
