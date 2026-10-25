package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.SceneApplyState
import me.kavishdevar.librepods.data.SceneGroup
import me.kavishdevar.librepods.data.SceneOmission
import me.kavishdevar.librepods.data.ScenePresetId
import me.kavishdevar.librepods.data.SceneResultDetail
import me.kavishdevar.librepods.data.SceneSetting
import me.kavishdevar.librepods.data.SceneSettingStatus
import me.kavishdevar.librepods.data.SceneValue
import me.kavishdevar.librepods.data.scenePresetPlans

@Composable
internal fun ScenePresetsDialog(
    capabilities: Set<Capability>, premium: Boolean, canApply: Boolean,
    application: SceneApplyState, onApply: (ScenePresetId) -> Unit, onDismiss: () -> Unit
) {
    val plans = scenePresetPlans(capabilities, premium)
    var group by remember { mutableStateOf(SceneGroup.FOCUS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scene_presets_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scene_close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scene_manual_explanation))
                Text(stringResource(R.string.scene_firmware_explanation), style = MaterialTheme.typography.bodySmall)
                if (!canApply) Text(stringResource(R.string.scene_connection_required), color = MaterialTheme.colorScheme.error)
                if (plans.isEmpty()) {
                    Text(stringResource(R.string.scene_no_supported_presets))
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneGroup.entries.forEach { item ->
                            FilterChip(selected = group == item, onClick = { group = item }, label = { Text(sceneGroupName(item)) })
                        }
                    }
                    plans.filter { it.group == group }.forEach { plan ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(scenePresetName(plan.id), style = MaterialTheme.typography.titleSmall)
                                plan.settings.forEach { Text(sceneTargetDescription(it), style = MaterialTheme.typography.bodyMedium) }
                                plan.omitted.forEach { (setting, reason) ->
                                    Text(stringResource(R.string.scene_omitted_setting, sceneSettingName(setting), stringResource(
                                        if (reason == SceneOmission.PREMIUM_REQUIRED) R.string.scene_requires_premium else R.string.scene_model_unsupported
                                    )), style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { onApply(plan.id) }, enabled = canApply && !application.isApplying,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.scene_apply_named, scenePresetName(plan.id)))
                                }
                            }
                        }
                    }
                }
                if (application.preset != null) {
                    HorizontalDivider()
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.scene_result_title, scenePresetName(application.preset)), style = MaterialTheme.typography.titleSmall)
                        if (application.connectionChanged) {
                            Text(stringResource(R.string.scene_previous_connection), color = MaterialTheme.colorScheme.error)
                        }
                        application.results.forEach { result ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sceneTargetDescription(result.target), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(when (result.status) {
                                    SceneSettingStatus.WAITING -> R.string.scene_status_waiting
                                    SceneSettingStatus.APPLIED -> R.string.scene_status_observed
                                    SceneSettingStatus.FAILED -> R.string.scene_status_failed
                                    SceneSettingStatus.UNCONFIRMED -> R.string.scene_status_unconfirmed
                                }), style = MaterialTheme.typography.labelLarge,
                                    color = if (result.status == SceneSettingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                Text(stringResource(sceneDetailResource(result.detail)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(stringResource(R.string.scene_result_explanation), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable private fun sceneGroupName(group: SceneGroup): String = stringResource(when (group) {
    SceneGroup.FOCUS -> R.string.scene_group_focus
    SceneGroup.TRAVEL -> R.string.scene_group_travel
    SceneGroup.CONVERSATION -> R.string.scene_group_conversation
})

@Composable private fun scenePresetName(preset: ScenePresetId): String = stringResource(when (preset) {
    ScenePresetId.DEEP_FOCUS -> R.string.scene_deep_focus
    ScenePresetId.BALANCED_FOCUS -> R.string.scene_balanced_focus
    ScenePresetId.QUIET_JOURNEY -> R.string.scene_quiet_journey
    ScenePresetId.AWARE_JOURNEY -> R.string.scene_aware_journey
    ScenePresetId.TALK_NEARBY -> R.string.scene_talk_nearby
})

@Composable private fun sceneSettingName(setting: SceneSetting): String = stringResource(when (setting) {
    SceneSetting.LISTENING_MODE -> R.string.scene_noise_control
    SceneSetting.CONVERSATION_AWARENESS -> R.string.scene_conversation_awareness
    SceneSetting.PERSONALIZED_VOLUME -> R.string.scene_personalized_volume
})

@Composable private fun sceneTargetDescription(target: SceneValue): String {
    val value = stringResource(if (target.setting == SceneSetting.LISTENING_MODE) when (target.value) {
        2 -> R.string.scene_noise_cancellation
        3 -> R.string.scene_transparency
        else -> R.string.scene_adaptive
    } else if (target.value == 1) R.string.scene_on else R.string.scene_off)
    return stringResource(R.string.scene_target_setting, sceneSettingName(target.setting), value)
}

private fun sceneDetailResource(detail: SceneResultDetail): Int = when (detail) {
    SceneResultDetail.QUEUED -> R.string.scene_detail_queued
    SceneResultDetail.WAITING_FOR_DEVICE -> R.string.scene_detail_waiting
    SceneResultDetail.DEVICE_REPORTED -> R.string.scene_detail_observed
    SceneResultDetail.NOT_SENT -> R.string.scene_detail_not_sent
    SceneResultDetail.WRITE_FAILED -> R.string.scene_detail_write_failed
    SceneResultDetail.NO_DEVICE_REPORT -> R.string.scene_detail_no_report
    SceneResultDetail.DIFFERENT_VALUE -> R.string.scene_detail_different_value
    SceneResultDetail.CONNECTION_CHANGED -> R.string.scene_detail_connection_changed
    SceneResultDetail.SETTING_CHANGED -> R.string.scene_detail_setting_changed
    SceneResultDetail.SUPPORT_CHANGED -> R.string.scene_detail_support_changed
    SceneResultDetail.EARLIER_REQUEST_UNRESOLVED -> R.string.scene_detail_earlier_request
}
