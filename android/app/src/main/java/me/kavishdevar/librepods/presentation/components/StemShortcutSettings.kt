package me.kavishdevar.librepods.presentation.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AndroidStemShortcuts
import me.kavishdevar.librepods.data.StemShortcutCodec
import me.kavishdevar.librepods.data.StemShortcutDescription
import me.kavishdevar.librepods.data.StemShortcutOption
import me.kavishdevar.librepods.data.StemShortcutSide
import me.kavishdevar.librepods.data.stemShortcutStore
import me.kavishdevar.librepods.shortcuts.StemShortcutDispatcher

@Composable
fun StemShortcutSettings(
    side: StemShortcutSide,
    active: Boolean,
    enabled: Boolean,
    pickerOpen: Boolean,
    onOpenPicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onActivate: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var description by remember(side) { mutableStateOf<StemShortcutDescription?>(null) }
    var options by remember(side) { mutableStateOf<List<StemShortcutOption>?>(null) }
    var loadFailed by remember(side) { mutableStateOf(false) }
    var saveFailed by remember(side) { mutableStateOf(false) }
    var saving by remember(side) { mutableStateOf(false) }
    var query by remember(side, pickerOpen) { mutableStateOf("") }
    var refresh by remember(side) { mutableIntStateOf(0) }

    LaunchedEffect(side, lifecycle, pickerOpen, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            loadFailed = false
            if (pickerOpen) options = null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val store = stemShortcutStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
                    val adapter = AndroidStemShortcuts(context)
                    val current = store.read(side)?.let(adapter::describe)
                    current to if (pickerOpen) adapter.options() else null
                }
            }
            loaded.onSuccess { (current, choices) -> description = current; options = choices }
                .onFailure { loadFailed = true }
            awaitCancellation()
        }
    }

    if (active) {
        Spacer(Modifier.height(24.dp))
        StyledList(
            title = stringResource(R.string.stem_shortcuts_title),
            description = stringResource(R.string.stem_shortcut_behavior),
        ) {
            StyledListItem(
                name = description?.label ?: stringResource(R.string.stem_shortcut_choose),
                description = when {
                    loadFailed -> stringResource(R.string.stem_shortcut_load_failed)
                    description == null -> stringResource(R.string.stem_shortcut_none)
                    description?.available == false -> stringResource(R.string.stem_shortcut_target_unavailable)
                    else -> stringResource(R.string.stem_shortcut_change)
                },
                orientation = ListItemOrientation.Vertical,
                onClick = onOpenPicker,
                enabled = enabled,
            )
            StyledListItem(
                name = stringResource(R.string.stem_shortcut_try),
                onClick = { StemShortcutDispatcher.request(context, side) },
                enabled = enabled,
            )
        }
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { if (!saving) onDismissPicker() },
            title = { Text(stringResource(R.string.stem_shortcut_choose)) },
            text = {
                Column {
                    Text(stringResource(R.string.stem_shortcut_supported), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.stem_shortcut_search)) },
                        singleLine = true,
                        enabled = !saving,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (saveFailed) Text(stringResource(R.string.stem_shortcut_save_failed), color = MaterialTheme.colorScheme.error)
                    if (loadFailed) {
                        Text(stringResource(R.string.stem_shortcut_load_failed))
                        TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.stem_shortcut_retry)) }
                    } else if (options == null) {
                        Text(stringResource(R.string.stem_shortcut_loading))
                    } else {
                        val filtered = options.orEmpty().filter { it.label.contains(query, true) || it.detail.contains(query, true) }
                        if (filtered.isEmpty()) Text(stringResource(R.string.stem_shortcut_no_matches))
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(filtered, key = { StemShortcutCodec.encode(it.target)!! }) { option ->
                                TextButton(
                                    enabled = enabled && !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        saving = true
                                        saveFailed = false
                                        scope.launch {
                                            try {
                                                val saved = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        stemShortcutStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
                                                            .write(side, option.target)
                                                    }.getOrDefault(false)
                                                }
                                                if (saved) {
                                                    onActivate()
                                                    onDismissPicker()
                                                    refresh++
                                                } else saveFailed = true
                                            } finally { saving = false }
                                        }
                                    },
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(option.detail, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissPicker, enabled = !saving) { Text(stringResource(R.string.stem_shortcut_cancel)) }
            },
        )
    }
}
