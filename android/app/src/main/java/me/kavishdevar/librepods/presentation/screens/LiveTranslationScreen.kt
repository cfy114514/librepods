package me.kavishdevar.librepods.presentation.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.translation.TranslationCapability
import android.view.translation.TranslationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.services.LiveTranslationService
import me.kavishdevar.librepods.translation.*
import java.util.Locale

@Composable
fun LiveTranslationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LiveTranslationController.state.collectAsState()
    var pairs by remember { mutableStateOf<List<TranslationPair>>(emptyList()) }
    var source by remember { mutableStateOf(state.pair?.source.orEmpty()) }
    var target by remember { mutableStateOf(state.pair?.target.orEmpty()) }
    var output by remember { mutableStateOf(state.output) }
    var loading by remember { mutableStateOf(true) }
    var discoveryError by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var choosingSource by remember { mutableStateOf<Boolean?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var permissionStart by remember { mutableStateOf<Pair<TranslationPair, TranslationOutput>?>(null) }
    val idle = state.phase == TranslationPhase.IDLE
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(context, refresh) {
        loading = true
        try {
            pairs = withTimeout(10_000) { PlatformTranslationCapabilities.load(context, refresh = true) }
                .filter { it.state == TranslationCapability.STATE_ON_DEVICE }.map { it.pair() }.distinct()
                .filter { it.source != it.target }
            if (pairs.none { it.source == source && it.target == target }) {
                val preferred = pairs.firstOrNull { Locale.forLanguageTag(it.source).language == "en" && Locale.forLanguageTag(it.target).language == Locale.getDefault().language }
                    ?: pairs.firstOrNull()
                source = preferred?.source.orEmpty(); target = preferred?.target.orEmpty()
            }
            discoveryError = pairs.isEmpty()
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) discoveryError = true else throw error
        } catch (_: Exception) { discoveryError = true }
        finally { loading = false }
    }
    fun start() { LiveTranslationService.start(context, TranslationPair(source, target), output) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        val requested = permissionStart
        permissionStart = null
        if (granted && requested != null) LiveTranslationService.start(context, requested.first, requested.second)
    }
    val material = LocalDesignSystem.current == DesignSystem.Material
    val top = if (material) 16.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 100.dp
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(top))
        Text(stringResource(R.string.translation_description), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        StyledList {
            StyledListItem(name = stringResource(R.string.translation_source), description = languageName(if (idle) source else state.pair?.source.orEmpty()),
                onClick = if (idle && pairs.isNotEmpty()) ({ choosingSource = true }) else null)
            StyledListItem(name = stringResource(R.string.translation_target), description = languageName(if (idle) target else state.pair?.target.orEmpty()),
                onClick = if (idle && pairs.isNotEmpty()) ({ choosingSource = false }) else null)
        }
        Spacer(Modifier.height(16.dp))
        StyledList {
            TranslationOutput.entries.forEach { value ->
                StyledListItem(name = stringResource(when (value) {
                    TranslationOutput.AIRPODS -> R.string.translation_output_airpods
                    TranslationOutput.CAPTIONS -> R.string.translation_output_captions
                    TranslationOutput.SPEAKER -> R.string.translation_output_speaker
                }), selected = (if (idle) output else state.output) == value, onClick = if (idle) ({ output = value }) else null)
            }
        }
        Spacer(Modifier.height(16.dp))
        StyledToggle(label = stringResource(R.string.live_translation), checked = state.phase in listOf(TranslationPhase.STARTING, TranslationPhase.LISTENING),
            enabled = state.phase != TranslationPhase.STOPPING && (!idle || (!loading && !discoveryError && source.isNotEmpty() && target.isNotEmpty())),
            description = stringResource(when (state.phase) {
                TranslationPhase.STARTING -> R.string.translation_starting
                TranslationPhase.LISTENING -> R.string.translation_listening
                TranslationPhase.STOPPING -> R.string.live_listen_stopping
                TranslationPhase.IDLE -> if (loading) R.string.translation_checking else R.string.live_listen_off
            }), onCheckedChange = { enabled ->
                if (!enabled) LiveTranslationService.stop(context)
                else {
                    permissionDenied = false
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else {
                        permissionStart = TranslationPair(source, target) to output
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            })
        if (discoveryError && idle) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.translation_no_translator), color = MaterialTheme.colorScheme.error)
        }
        val error = if (permissionDenied) TranslationError.PERMISSION else state.error
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(translationErrorResource(error)), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        StyledListItem(name = stringResource(R.string.translation_model_settings), onClick = {
            scope.launch {
                try {
                    val intent = withContext(Dispatchers.IO) { context.getSystemService(TranslationManager::class.java)?.onDeviceTranslationSettingsActivityIntent }
                    if (intent != null) intent.send() else discoveryError = true
                } catch (_: Exception) { discoveryError = true }
            }
        })
        StyledListItem(name = stringResource(R.string.translation_voice_settings), onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        })
        StyledListItem(name = stringResource(R.string.translation_tts_settings), onClick = {
            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        })
        StyledListItem(name = stringResource(R.string.translation_refresh), onClick = { refresh++ })
        if (state.partial.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(state.partial, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.lines.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            state.lines.forEach { line ->
                Text(line.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (line.translated.isNotEmpty()) Text(line.translated, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (state.skipped > 0) Text(stringResource(R.string.translation_skipped), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.translation_limitations), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp))
    }
    choosingSource?.let { sourceChoice ->
        val options = remember(pairs, source, sourceChoice) {
            (if (sourceChoice) pairs.map { it.source } else pairs.filter { it.source == source }.map { it.target })
                .distinct().sortedBy(::languageName)
        }
        AlertDialog(onDismissRequest = { choosingSource = null }, title = { Text(stringResource(if (sourceChoice) R.string.translation_source else R.string.translation_target)) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(options, key = { it }) { tag -> StyledListItem(name = languageName(tag), onClick = {
                    if (sourceChoice) {
                        source = tag
                        if (pairs.none { it.source == source && it.target == target }) target = pairs.first { it.source == source }.target
                    } else target = tag
                    choosingSource = null
                }) }
            } }, confirmButton = { TextButton(onClick = { choosingSource = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

private fun languageName(tag: String): String = if (tag.isBlank()) "—" else Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
private fun translationErrorResource(error: TranslationError): Int = when (error) {
    TranslationError.PERMISSION -> R.string.live_listen_permission
    TranslationError.MICROPHONE_BUSY -> R.string.translation_microphone_busy
    TranslationError.NO_RECOGNIZER -> R.string.translation_no_recognizer
    TranslationError.NO_TRANSLATOR -> R.string.translation_no_translator
    TranslationError.LANGUAGE_MODEL -> R.string.translation_language_model
    TranslationError.NO_VOICE -> R.string.translation_no_voice
    TranslationError.NO_HEADPHONES -> R.string.live_listen_no_headphones
    TranslationError.ROUTE_CHANGED -> R.string.live_listen_route_changed
    TranslationError.TIMEOUT -> R.string.translation_timeout
    else -> R.string.translation_error
}
