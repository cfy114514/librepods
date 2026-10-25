package me.kavishdevar.librepods.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SceneSettingStatus { WAITING, APPLIED, FAILED, UNCONFIRMED }
enum class SceneResultDetail {
    QUEUED, WAITING_FOR_DEVICE, DEVICE_REPORTED, NOT_SENT, WRITE_FAILED,
    NO_DEVICE_REPORT, DIFFERENT_VALUE, CONNECTION_CHANGED, SETTING_CHANGED,
    SUPPORT_CHANGED, EARLIER_REQUEST_UNRESOLVED
}
data class SceneSettingResult(val target: SceneValue, val status: SceneSettingStatus, val detail: SceneResultDetail)
data class SceneApplyState(
    val attempt: Long = 0,
    val preset: ScenePresetId? = null,
    val results: List<SceneSettingResult> = emptyList(),
    val isApplying: Boolean = false,
    val connectionChanged: Boolean = false
)

internal data class SceneContext(val peer: String, val selection: Long, val binding: Long, val deviceSession: Long)
internal data class SceneCommand(val attempt: Long, val context: SceneContext, val target: SceneValue)

/**
 * AACP has no transaction IDs. Applied means a fresh device report matched after dispatch;
 * a write or optimistic cache entry alone cannot confirm anything. After an unresolved write,
 * retries of that setting remain unconfirmed on the same connection while this controller lives:
 * a late earlier report cannot be attributed to the retry. A fresh settings ViewModel starts a new
 * history even if the service transport survives; observed values never prove causal acknowledgement.
 * All methods are short and never call transport or user callbacks.
 */
internal class ScenePresetController {
    private class Entry(val command: SceneCommand) {
        var active = true
        var boundary: Long? = null
        var written: Boolean? = null
        var latestSequence = Long.MIN_VALUE
        var reported: Int? = null
        var ambiguous = false
        var result = SceneSettingResult(command.target, SceneSettingStatus.WAITING, SceneResultDetail.QUEUED)
    }
    private var context: SceneContext? = null
    private data class TransportOwner(val peer: String, val binding: Long, val deviceSession: Long)
    private var unresolvedOwner: TransportOwner? = null
    private var attempt = 0L
    private var preset: ScenePresetId? = null
    private val entries = linkedMapOf<SceneSetting, Entry>()
    private val unresolved = mutableSetOf<SceneSetting>()
    private var connectionChanged = false
    private val mutableState = MutableStateFlow(SceneApplyState())
    val state: StateFlow<SceneApplyState> = mutableState

    @Synchronized fun setContext(next: SceneContext?) {
        if (context == next) return
        entries.values.filter { it.active }.forEach { finishUncertain(it, SceneResultDetail.CONNECTION_CHANGED) }
        if (entries.isNotEmpty()) connectionChanged = true
        context = next
        // A temporarily unavailable selection/permission is not proof of a new connection.
        // Returning to the same live transport must retain ambiguity from earlier writes.
        if (next != null) {
            val owner = TransportOwner(next.peer, next.binding, next.deviceSession)
            if (owner != unresolvedOwner) unresolved.clear()
            unresolvedOwner = owner
        }
        publish()
    }

    @Synchronized fun begin(expectedContext: SceneContext, plan: ScenePresetPlan): List<SceneCommand> {
        if (context != expectedContext || plan.settings.isEmpty()) return emptyList()
        // Replacing a run retires its queued work and callbacks. Dispatched unresolved work is
        // retained in the ambiguity set even though the old result is no longer displayed.
        entries.clear()
        attempt++
        preset = plan.id
        connectionChanged = false
        plan.settings.forEach { target ->
            entries[target.setting] = Entry(SceneCommand(attempt, expectedContext, target))
        }
        publish()
        return entries.values.map { it.command }
    }

    @Synchronized fun isCurrent(command: SceneCommand): Boolean = entry(command)?.active == true

    @Synchronized fun writeStarted(command: SceneCommand, receivedSequence: Long) {
        if (context != command.context) return
        val entry = entry(command)
        if (entry == null || !entry.active) {
            // Retirement can race the gap between the final transport guard and this hook.
            // Such a write can still reach the wire; never let its late report confirm a retry.
            if (entry?.boundary != null) return
            unresolved.add(command.target.setting)
            entries[command.target.setting]?.takeIf { it !== entry && it.boundary != null }?.let { successor ->
                successor.ambiguous = true
                if (successor.result.status == SceneSettingStatus.APPLIED) {
                    finishUncertain(successor, SceneResultDetail.SETTING_CHANGED)
                }
            }
            if (entry != null) {
                entry.boundary = receivedSequence
                entry.result = entry.result.copy(status = SceneSettingStatus.UNCONFIRMED,
                    detail = if (entry.result.detail == SceneResultDetail.NOT_SENT) SceneResultDetail.NO_DEVICE_REPORT else entry.result.detail)
            }
            publish()
            return
        }
        if (entry.boundary != null) return
        entry.boundary = receivedSequence
        entry.ambiguous = command.target.setting in unresolved
        unresolved.add(command.target.setting)
        entry.result = entry.result.copy(detail = SceneResultDetail.WAITING_FOR_DEVICE)
        publish()
    }

    @Synchronized fun writeFinished(command: SceneCommand, success: Boolean) {
        val entry = activeEntry(command) ?: return
        entry.written = success
        if (!success) {
            entry.active = false
            // A write/flush error after dispatch may have reached the device partially or fully.
            entry.result = entry.result.copy(status = if (entry.boundary == null) SceneSettingStatus.FAILED else SceneSettingStatus.UNCONFIRMED,
                detail = SceneResultDetail.WRITE_FAILED)
        } else if (entry.boundary == null) {
            // An observer failure must not turn a completed write into a confirmation.
            unresolved.add(command.target.setting)
            finishUncertain(entry, SceneResultDetail.NO_DEVICE_REPORT)
        } else confirmIfPossible(entry)
        publish()
    }

    @Synchronized fun dispatchCompleted(command: SceneCommand) {
        val entry = activeEntry(command) ?: return
        if (entry.written == null) {
            finishUncertain(entry, if (entry.boundary == null) SceneResultDetail.NOT_SENT else SceneResultDetail.NO_DEVICE_REPORT)
            publish()
        }
    }

    @Synchronized fun rejected(command: SceneCommand) {
        val entry = activeEntry(command) ?: return
        entry.active = false
        entry.result = entry.result.copy(status = SceneSettingStatus.FAILED, detail = SceneResultDetail.NOT_SENT)
        publish()
    }

    @Synchronized fun received(reportContext: SceneContext, setting: SceneSetting, value: Int, sequence: Long) {
        if (context != reportContext) return
        val entry = entries[setting]?.takeIf { it.active } ?: return
        val boundary = entry.boundary ?: return
        if (sequence <= boundary || sequence <= entry.latestSequence) return
        entry.latestSequence = sequence
        entry.reported = value
        confirmIfPossible(entry)
        publish()
    }

    @Synchronized fun supersede(setting: SceneSetting, reason: SceneResultDetail = SceneResultDetail.SETTING_CHANGED) {
        val entry = entries[setting] ?: return
        if (entry.result.status == SceneSettingStatus.FAILED) return
        finishUncertain(entry, reason)
        publish()
    }

    @Synchronized fun retainSupported(supported: Set<SceneSetting>) {
        entries.filterKeys { it !in supported }.values.filter { it.active }.forEach {
            finishUncertain(it, SceneResultDetail.SUPPORT_CHANGED)
        }
        publish()
    }

    @Synchronized fun expire(expectedAttempt: Long) {
        if (attempt != expectedAttempt) return
        entries.values.filter { it.active }.forEach { entry ->
            val detail = when {
                entry.boundary == null -> SceneResultDetail.NOT_SENT
                entry.ambiguous -> SceneResultDetail.EARLIER_REQUEST_UNRESOLVED
                entry.reported != null -> SceneResultDetail.DIFFERENT_VALUE
                else -> SceneResultDetail.NO_DEVICE_REPORT
            }
            finishUncertain(entry, detail)
        }
        publish()
    }

    private fun entry(command: SceneCommand): Entry? = entries[command.target.setting]?.takeIf {
        context == command.context && it.command == command
    }
    private fun activeEntry(command: SceneCommand): Entry? = entry(command)?.takeIf { it.active }
    private fun confirmIfPossible(entry: Entry) {
        if (entry.written != true || entry.reported != entry.command.target.value) return
        if (entry.ambiguous) {
            finishUncertain(entry, SceneResultDetail.EARLIER_REQUEST_UNRESOLVED)
        } else {
            entry.active = false
            unresolved.remove(entry.command.target.setting)
            entry.result = entry.result.copy(status = SceneSettingStatus.APPLIED, detail = SceneResultDetail.DEVICE_REPORTED)
        }
    }
    private fun finishUncertain(entry: Entry, detail: SceneResultDetail) {
        entry.active = false
        entry.result = entry.result.copy(
            status = if (entry.boundary == null && entry.written != true) SceneSettingStatus.FAILED else SceneSettingStatus.UNCONFIRMED,
            detail = detail
        )
    }
    private fun publish() {
        mutableState.value = SceneApplyState(attempt, preset, entries.values.map { it.result },
            entries.values.any { it.active }, connectionChanged)
    }
}
