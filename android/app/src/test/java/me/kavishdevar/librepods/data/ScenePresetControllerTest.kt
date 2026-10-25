package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class ScenePresetControllerTest {
    private val context = SceneContext("peer-a", 1, 1, 1)
    private val mode = SceneValue(SceneSetting.LISTENING_MODE, 2)
    private val conversation = SceneValue(SceneSetting.CONVERSATION_AWARENESS, 2)
    private val volume = SceneValue(SceneSetting.PERSONALIZED_VOLUME, 2)
    private fun plan(vararg settings: SceneValue) =
        ScenePresetPlan(ScenePresetId.DEEP_FOCUS, SceneGroup.FOCUS, settings.toList(), emptyMap())
    private fun controller() = ScenePresetController().also { it.setContext(context) }
    private fun ScenePresetController.result(setting: SceneSetting = mode.setting) = state.value.results.first { it.target.setting == setting }
    private fun ScenePresetController.send(command: SceneCommand, sequence: Long = 10) {
        writeStarted(command, sequence)
        writeFinished(command, true)
        dispatchCompleted(command)
    }

    @Test fun transportSuccessAndCachedOrPredispatchValuesCannotConfirm() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.received(context, mode.setting, mode.value, 9) // while queued
        controller.send(command)
        controller.received(context, mode.setting, mode.value, 10) // stamped before dispatch, delivered later
        assertEquals(SceneSettingStatus.WAITING, controller.result().status)
        controller.expire(command.attempt)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.NO_DEVICE_REPORT, controller.result().detail)
    }

    @Test fun freshMatchingReportIsStagedUntilTheWriteSuccessfullyCompletes() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.writeStarted(command, 10)
        controller.received(context, mode.setting, mode.value, 11)
        assertEquals(SceneSettingStatus.WAITING, controller.result().status)
        controller.writeFinished(command, true)
        assertEquals(SceneSettingStatus.APPLIED, controller.result().status)
        assertEquals(SceneResultDetail.DEVICE_REPORTED, controller.result().detail)
        assertFalse(controller.state.value.isApplying)
    }

    @Test fun reportFollowedByFlushFailureIsUnconfirmedAndNeverObservedSuccess() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.writeStarted(command, 10)
        controller.received(context, mode.setting, mode.value, 11)
        controller.writeFinished(command, false)
        controller.dispatchCompleted(command)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.WRITE_FAILED, controller.result().detail)
    }

    @Test fun aDiscardWithoutDispatchFailsWithoutWaitingForTheTimeout() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.dispatchCompleted(command)
        assertEquals(SceneSettingStatus.FAILED, controller.result().status)
        assertEquals(SceneResultDetail.NOT_SENT, controller.result().detail)
        assertFalse(controller.state.value.isApplying)
        controller.writeFinished(command, true)
        controller.received(context, mode.setting, mode.value, 100)
        assertEquals(SceneSettingStatus.FAILED, controller.result().status)
    }

    @Test fun explicitQueueRejectionAndWriteFailureBeforeDispatchAreFailures() {
        val controller = controller()
        var command = controller.begin(context, plan(mode)).single()
        controller.rejected(command)
        assertEquals(SceneSettingStatus.FAILED, controller.result().status)
        command = controller.begin(context, plan(mode)).single()
        controller.writeFinished(command, false)
        assertEquals(SceneSettingStatus.FAILED, controller.result().status)
    }

    @Test fun unexpectedCompletionWithoutAWriteResultDoesNotConfirm() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.writeStarted(command, 10)
        controller.received(context, mode.setting, mode.value, 11)
        controller.dispatchCompleted(command)
        controller.writeFinished(command, true)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
    }

    @Test fun aSuccessfulWriteWithoutADispatchBoundaryMakesItsRetryAmbiguous() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.writeFinished(command, true)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        val retry = controller.begin(context, plan(mode)).single()
        controller.send(retry)
        controller.received(context, mode.setting, mode.value, 11)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, controller.result().detail)
    }

    @Test fun differentDeviceValueIsUnconfirmedRatherThanAnInventedRejection() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.send(command)
        controller.received(context, mode.setting, 3, 11)
        controller.expire(command.attempt)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.DIFFERENT_VALUE, controller.result().detail)
    }

    @Test fun lateDeliveryOfAnOlderMatchingReportCannotOverrideANewerValue() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.writeStarted(command, 10)
        controller.received(context, mode.setting, 3, 12)
        controller.received(context, mode.setting, mode.value, 11)
        controller.writeFinished(command, true)
        assertEquals(SceneSettingStatus.WAITING, controller.result().status)
        controller.received(context, mode.setting, mode.value, 13)
        assertEquals(SceneSettingStatus.APPLIED, controller.result().status)
    }

    @Test fun aGroupCanHaveIndependentObservedFailedAndUnconfirmedSettings() {
        val controller = controller()
        val commands = controller.begin(context, plan(mode, conversation, volume))
        controller.send(commands[0])
        controller.received(context, mode.setting, mode.value, 11)
        controller.rejected(commands[1])
        controller.send(commands[2])
        controller.expire(commands.first().attempt)
        assertEquals(listOf(SceneSettingStatus.APPLIED, SceneSettingStatus.FAILED, SceneSettingStatus.UNCONFIRMED),
            controller.state.value.results.map { it.status })
    }

    @Test fun anUnresolvedWriteMakesRetriesAmbiguousUntilTheConnectionChanges() {
        val controller = controller()
        val first = controller.begin(context, plan(mode)).single()
        controller.send(first)
        controller.expire(first.attempt)
        val retry = controller.begin(context, plan(mode)).single()
        controller.send(retry, 20)
        controller.received(context, mode.setting, mode.value, 21) // could be the earlier request
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, controller.result().detail)
        val reconnected = context.copy(deviceSession = 2)
        controller.setContext(reconnected)
        val fresh = controller.begin(reconnected, plan(mode)).single()
        controller.send(fresh, 30)
        controller.received(reconnected, mode.setting, mode.value, 31)
        assertEquals(SceneSettingStatus.APPLIED, controller.result().status)
    }

    @Test fun replacingAnUnsentRunDoesNotCreateFalseRetryAmbiguity() {
        val controller = controller()
        val old = controller.begin(context, plan(mode)).single()
        val latest = controller.begin(context, plan(mode)).single()
        controller.dispatchCompleted(old)
        controller.expire(old.attempt)
        controller.send(latest)
        controller.received(context, mode.setting, mode.value, 11)
        assertEquals(SceneSettingStatus.APPLIED, controller.result().status)
        assertFalse(controller.isCurrent(old))
    }

    @Test fun repeatedApplyNeverReusesThePreviousObservedValue() {
        val controller = controller()
        val first = controller.begin(context, plan(mode)).single()
        controller.send(first)
        controller.received(context, mode.setting, mode.value, 11)
        val repeat = controller.begin(context, plan(mode)).single()
        controller.send(repeat, 11)
        assertEquals(SceneSettingStatus.WAITING, controller.result().status)
        controller.received(context, mode.setting, mode.value, 11)
        assertEquals(SceneSettingStatus.WAITING, controller.result().status)
        controller.received(context, mode.setting, mode.value, 12)
        assertEquals(SceneSettingStatus.APPLIED, controller.result().status)
    }

    @Test fun retirementAfterTheFinalTransportGuardStillPoisonsRetryAttribution() {
        val controller = controller()
        val old = controller.begin(context, plan(mode)).single()
        val latest = controller.begin(context, plan(mode)).single()
        // The old transport passed its guard immediately before begin replaced the run.
        controller.writeStarted(old, 10)
        controller.writeFinished(old, true)
        controller.send(latest, 20)
        controller.received(context, mode.setting, mode.value, 21)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, controller.result().detail)
    }

    @Test fun dispatchRacingTheTimeoutDoesNotKeepAnUntrueNotSentResult() {
        val controller = controller()
        val command = controller.begin(context, plan(mode)).single()
        controller.expire(command.attempt)
        assertEquals(SceneSettingStatus.FAILED, controller.result().status)
        controller.writeStarted(command, 10)
        controller.writeFinished(command, true)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertFalse(controller.isCurrent(command))
    }

    @Test fun peerSelectionServiceAndTransportChangesAllRetireOldCommandsAndReports() {
        val replacements = listOf(null, context.copy(peer = "peer-b"), context.copy(selection = 2),
            context.copy(binding = 2), context.copy(deviceSession = 2))
        replacements.forEach { replacement ->
            val controller = controller()
            val commands = controller.begin(context, plan(mode, conversation))
            controller.send(commands[0])
            controller.setContext(replacement)
            controller.received(context, mode.setting, mode.value, 11)
            controller.writeFinished(commands[1], true)
            assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
            assertEquals(SceneSettingStatus.FAILED, controller.result(conversation.setting).status)
            assertTrue(controller.state.value.connectionChanged)
            assertTrue(commands.none(controller::isCurrent))
        }
    }

    @Test fun manualOverridesRetireOnlyTheChangedSetting() {
        val controller = controller()
        val commands = controller.begin(context, plan(mode, conversation))
        commands.forEach { controller.send(it) }
        controller.supersede(mode.setting)
        controller.received(context, mode.setting, mode.value, 11)
        controller.received(context, conversation.setting, conversation.value, 12)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result().status)
        assertEquals(SceneResultDetail.SETTING_CHANGED, controller.result().detail)
        assertEquals(SceneSettingStatus.APPLIED, controller.result(conversation.setting).status)
    }

    @Test fun capabilityOrEntitlementLossCancelsPendingWorkAndLeavesSentWorkUnconfirmed() {
        val controller = controller()
        val commands = controller.begin(context, plan(mode, conversation, volume))
        controller.send(commands[1])
        controller.retainSupported(setOf(mode.setting))
        assertEquals(SceneSettingStatus.WAITING, controller.result().status)
        assertEquals(SceneSettingStatus.UNCONFIRMED, controller.result(conversation.setting).status)
        assertEquals(SceneSettingStatus.FAILED, controller.result(volume.setting).status)
        assertFalse(controller.isCurrent(commands[1]))
        assertFalse(controller.isCurrent(commands[2]))
    }
}
