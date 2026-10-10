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

package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.presentation.components.StyledInputField
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNameProblem
import me.kavishdevar.librepods.data.airPodsNameProblem
import kotlinx.coroutines.delay
import kotlin.io.encoding.ExperimentalEncodingApi


@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun RenameScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        key(viewModel, state.selectedPeer, state.selectedPeerVersion, state.serviceBindingId) {
            val focusRequester = remember { FocusRequester() }
            val initialName = remember { state.deviceName }
            val textFieldState = rememberTextFieldState(initialText = initialName)
            val submit = remember { viewModel.createRenameEditor() }
            var edited by remember { mutableStateOf(false) }
            var unavailable by remember { mutableStateOf(false) }
            val name = textFieldState.text.toString()
            val problem = airPodsNameProblem(name)
            LaunchedEffect(focusRequester) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            LaunchedEffect(name) {
                if (name != initialName) edited = true
                if (!edited || problem != null) return@LaunchedEffect
                delay(250)
                unavailable = !submit(name)
            }
            DisposableEffect(textFieldState, submit) {
                onDispose {
                    val lastName = textFieldState.text.toString()
                    if ((edited || lastName != initialName) && airPodsNameProblem(lastName) == null) submit(lastName)
                }
            }
            StyledInputField(textFieldState, focusRequester)
            val error = when (problem) {
                AirPodsNameProblem.EMPTY -> R.string.rename_name_empty
                AirPodsNameProblem.TOO_LONG -> R.string.rename_name_too_long
                AirPodsNameProblem.INVALID -> R.string.rename_name_invalid
                null -> if (unavailable) R.string.rename_connect_first else null
            }
            if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(modifier = Modifier.height(bottomPadding))

    }
}
