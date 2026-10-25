package me.kavishdevar.librepods.bluetooth

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import me.kavishdevar.librepods.data.AirPods
import me.kavishdevar.librepods.data.AirPodsPro1
import me.kavishdevar.librepods.data.AirPodsPro2USBC
import me.kavishdevar.librepods.data.SceneApplyState
import me.kavishdevar.librepods.data.SceneCommand
import me.kavishdevar.librepods.data.SceneContext
import me.kavishdevar.librepods.data.SceneGroup
import me.kavishdevar.librepods.data.SceneOmission
import me.kavishdevar.librepods.data.ScenePresetController
import me.kavishdevar.librepods.data.ScenePresetId
import me.kavishdevar.librepods.data.ScenePresetPlan
import me.kavishdevar.librepods.data.SceneResultDetail
import me.kavishdevar.librepods.data.SceneSetting
import me.kavishdevar.librepods.data.SceneSettingResult
import me.kavishdevar.librepods.data.SceneSettingStatus
import me.kavishdevar.librepods.data.SceneValue
import me.kavishdevar.librepods.data.scenePresetPlans
import me.kavishdevar.librepods.utils.InteractiveCommandQueue

private fun SceneSetting.identifier(): Identifier = when (this) {
    SceneSetting.LISTENING_MODE -> Identifier.LISTENING_MODE
    SceneSetting.CONVERSATION_AWARENESS -> Identifier.CONVERSATION_DETECT_CONFIG
    SceneSetting.PERSONALIZED_VOLUME -> Identifier.ADAPTIVE_VOLUME_CONFIG
}

private fun plan(vararg values: SceneValue) = ScenePresetPlan(
    ScenePresetId.DEEP_FOCUS, SceneGroup.FOCUS,
    values.toList().ifEmpty { listOf(SceneValue(SceneSetting.LISTENING_MODE, 2)) }, emptyMap()
)

/** Transport behavior only: the real parser, writer, controller and model policy are compiled. */
private class ControlledWire : OutputStream() {
    val bytes = ByteArrayOutputStream()
    var onWrite: (() -> Unit)? = null
    var onFlush: (() -> Unit)? = null
    override fun write(value: Int) { bytes.write(value) }
    override fun write(data: ByteArray, offset: Int, length: Int) {
        bytes.write(data, offset, length)
        onWrite?.invoke()
    }
    override fun flush() { onFlush?.invoke() }
}

private data class QueuedWrite(val command: SceneCommand, val socket: WireSocket)

private class SceneWire(val wire: ControlledWire = ControlledWire()) : Closeable {
    val socket = WireSocket(wire)
    val aacp = AACPManager()
    val controller = ScenePresetController()
    var context = SceneContext("AA:BB:CC:DD:EE:01", 1, 1, aacp.beginDeviceSession())
        private set
    val state: SceneApplyState get() = controller.state.value
    private val registration: Closeable

    init {
        BluetoothConnectionManager.aacpSocket = socket
        controller.setContext(context)
        registration = aacp.observeReceivedControlCommands { report ->
            val setting = SceneSetting.entries.firstOrNull { it.identifier() == report.identifier }
            val value = report.value.singleOrNull()?.toInt()
            if (setting != null && value != null) {
                controller.received(context.copy(deviceSession = report.deviceSession), setting,
                    value, report.sequence)
            }
        }
    }

    fun queue(preset: ScenePresetPlan = plan()): List<QueuedWrite> =
        controller.begin(context, preset).map { QueuedWrite(it, BluetoothConnectionManager.aacpSocket as WireSocket) }

    /** Minimal callback adapter; this does not compile or verify the Android service wiring. */
    fun write(queued: QueuedWrite, beforeDispatch: () -> Unit = {}): Boolean {
        val sent = send(queued, beforeDispatch)
        controller.dispatchCompleted(queued.command)
        return sent
    }

    fun send(queued: QueuedWrite, beforeDispatch: () -> Unit = {}, valid: () -> Boolean = { true }): Boolean {
        val command = queued.command
        val sent = aacp.sendPacketObserved(controlReport(command.target.setting.identifier(), command.target.value),
            queued.socket, {
                beforeDispatch()
                controller.writeStarted(command, aacp.captureReceivedControlSequence())
            }) {
            valid() && controller.isCurrent(command)
        }
        controller.writeFinished(command, sent)
        return sent
    }

    fun report(setting: SceneSetting = SceneSetting.LISTENING_MODE, value: Int = 2, session: Long = aacp.captureDeviceSession()) {
        aacp.receivePacket(controlReport(setting.identifier(), value), session)
    }

    fun result(setting: SceneSetting = SceneSetting.LISTENING_MODE): SceneSettingResult =
        state.results.single { it.target.setting == setting }

    fun expectStatus(status: SceneSettingStatus, description: String, setting: SceneSetting = SceneSetting.LISTENING_MODE) {
        expect(result(setting).status == status, "$description: expected $status, got ${result(setting)}")
    }

    fun expire() { controller.expire(state.attempt) }

    fun replaceConnection(resetDeviceSession: Boolean) {
        BluetoothConnectionManager.aacpSocket = WireSocket()
        context = context.copy(binding = context.binding + 1,
            deviceSession = if (resetDeviceSession) aacp.beginDeviceSession() else context.deviceSession)
        controller.setContext(context)
    }

    fun loseAndRestoreContext(selectionChanges: Boolean) {
        controller.setContext(null)
        if (selectionChanges) context = context.copy(selection = context.selection + 1)
        controller.setContext(context)
    }

    override fun close() { registration.close() }
}

/** Actual serial/coalescing worker, with only the service callback adapter supplied by this fixture. */
private class SceneWorker(private val scene: SceneWire, dispatcher: CoroutineDispatcher) : Closeable {
    private val errors = ConcurrentLinkedQueue<Exception>()
    private val queue = InteractiveCommandQueue<SceneSetting, QueuedWrite>(SceneSetting.entries.toSet(),
        dispatcher, onError = { errors.add(it) }) { pending, current ->
        scene.send(pending, valid = current)
    }

    fun offer(pending: QueuedWrite): CountDownLatch {
        val completed = CountDownLatch(1)
        expect(queue.offer(pending.command.target.setting, pending,
            valid = { scene.controller.isCurrent(pending.command) }) {
            scene.controller.dispatchCompleted(pending.command)
            completed.countDown()
        }, "Open scene worker accepts a command")
        return completed
    }

    override fun close() {
        queue.close()
        expect(errors.isEmpty(), "Asynchronous command worker must not throw: $errors")
    }
}

private fun withSceneWorker(scene: SceneWire, block: (SceneWorker) -> Unit) = withWorker { executor ->
    executor.asCoroutineDispatcher().use { dispatcher ->
        SceneWorker(scene, dispatcher).use(block)
    }
}

private fun localWriteWithoutReplyStaysUnconfirmed() = SceneWire().use { scene ->
    val command = scene.queue().single()
    expect(scene.write(command), "Control command written")
    scene.expectStatus(SceneSettingStatus.WAITING, "Optimistic cache must not confirm a scene")
    expect(scene.aacp.getControlCommandStatus(Identifier.LISTENING_MODE)?.value?.first() == 2.toByte(), "Real cache contains requested value")
    scene.expire()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Written command without device response")
    expect(scene.result().detail == SceneResultDetail.NO_DEVICE_REPORT, "Explain missing device feedback")
    scene.report()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "A post-deadline report cannot revive expired attempt")
}

private fun matchingReplyDuringWriteWaitsForFlushOutcome(success: Boolean) = SceneWire().use { scene ->
    val command = scene.queue().single()
    scene.wire.onWrite = {
        scene.report()
        scene.expectStatus(SceneSettingStatus.WAITING, "Reply while write is incomplete must be staged")
    }
    scene.wire.onFlush = {
        scene.expectStatus(SceneSettingStatus.WAITING, "Flush has not completed")
        if (!success) throw IOException("controlled flush failure after device reply")
    }
    expect(scene.write(command) == success, "Write result reflects actual flush outcome")
    scene.expectStatus(if (success) SceneSettingStatus.APPLIED else SceneSettingStatus.UNCONFIRMED,
        "Only successful write plus matching device report confirms")
    if (!success) expect(scene.result().detail == SceneResultDetail.WRITE_FAILED, "Explain transport failure despite matching report")
}

private fun replyDuringBlockedFlushWaitsForResult(success: Boolean) = SceneWire().use { scene ->
    val command = scene.queue().single()
    val flushEntered = CountDownLatch(1)
    val releaseFlush = CountDownLatch(1)
    scene.wire.onFlush = {
        flushEntered.countDown()
        releaseFlush.reached("release controlled flush")
        if (!success) throw IOException("controlled blocked flush failure")
    }
    withWorker { worker ->
        val sent = worker.submit<Boolean> { scene.write(command) }
        try {
            flushEntered.reached("writer has entered flush")
            scene.report()
            scene.expectStatus(SceneSettingStatus.WAITING, "Device reply on another thread cannot confirm while flush is blocked")
        } finally { releaseFlush.countDown() }
        expect(sent.get(5, TimeUnit.SECONDS) == success, "Blocked flush returns intended result")
    }
    scene.expectStatus(if (success) SceneSettingStatus.APPLIED else SceneSettingStatus.UNCONFIRMED,
        "Cross-thread report is committed only after successful flush")
}

private fun oldReportDeliveredAfterDispatchCannotConfirm() = SceneWire().use { scene ->
    val command = scene.queue().single()
    val firstCall = AtomicBoolean(true)
    scene.aacp.registerControlCommandListener(Identifier.LISTENING_MODE, object : AACPManager.ControlCommandListener {
        override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
            if (firstCall.compareAndSet(true, false)) expect(scene.write(command), "New dispatch from ordinary listener")
        }
    })
    scene.report()
    scene.expectStatus(SceneSettingStatus.WAITING, "Report received before dispatch cannot confirm even if delivered after write")
    scene.expire()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Only pre-dispatch matching report was observed")
}

private fun reportWhileWaitingForOutputCannotConfirm() = SceneWire().use { scene ->
    val command = scene.queue().single()
    val resolved = CountDownLatch(1)
    scene.socket.onResolve = { resolved.countDown() }
    withWorker { worker ->
        val sent = synchronized(scene.wire) {
            val pending = worker.submit<Boolean> { scene.write(command) }
            resolved.reached("scene writer queued behind stream monitor")
            scene.report()
            scene.expectStatus(SceneSettingStatus.WAITING, "Pre-write report while waiting for output")
            pending
        }
        expect(sent.get(5, TimeUnit.SECONDS), "Scene writes after output monitor released")
    }
    scene.expire()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Waiting on stream must not establish an earlier dispatch boundary")
}

private fun freshReplyAfterSuccessfulWriteConfirms() = SceneWire().use { scene ->
    expect(scene.write(scene.queue().single()), "Scene write succeeded")
    scene.report(SceneSetting.CONVERSATION_AWARENESS, 2)
    scene.expectStatus(SceneSettingStatus.WAITING, "Unrelated setting cannot confirm")
    scene.report(value = 3)
    scene.expectStatus(SceneSettingStatus.WAITING, "Wrong value cannot confirm")
    scene.report()
    scene.expectStatus(SceneSettingStatus.APPLIED, "Fresh matching device response confirms")
    expect(!scene.state.isApplying && scene.result().detail == SceneResultDetail.DEVICE_REPORTED, "Applied attempt is complete")
}

private fun newerWrongReplyCannotBeOverwrittenByDelayedMatchingReply() = SceneWire().use { scene ->
    val command = scene.queue().single()
    val hold = AtomicBoolean(true)
    scene.aacp.registerControlCommandListener(Identifier.LISTENING_MODE, object : AACPManager.ControlCommandListener {
        override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
            if (controlCommand.value[0] == 2.toByte() && hold.compareAndSet(true, false)) {
                scene.report(value = 3)
            }
        }
    })
    // The local optimistic callback is outside the observation interval being tested.
    hold.set(false)
    expect(scene.write(command), "Scene write succeeded")
    hold.set(true)
    scene.report()
    scene.expectStatus(SceneSettingStatus.WAITING, "Later wrong device value takes precedence over delayed earlier match")
    scene.expire()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "No current matching device response")
    expect(scene.result().detail == SceneResultDetail.DIFFERENT_VALUE, "Explain mismatching latest device value")
}

private fun groupedPresetKeepsIndependentResults() = SceneWire().use { scene ->
    val preset = scenePresetPlans(AirPodsPro2USBC().capabilities, premium = true)
        .single { it.id == ScenePresetId.DEEP_FOCUS }
    val queued = scene.queue(preset)
    expect(queued.size == 3, "Real supported model includes all three scene settings")
    for (pending in queued) {
        when (pending.command.target.setting) {
            SceneSetting.LISTENING_MODE -> {
                scene.wire.onWrite = { scene.report() }
                scene.wire.onFlush = null
            }
            SceneSetting.CONVERSATION_AWARENESS -> {
                scene.controller.rejected(pending.command)
                continue
            }
            SceneSetting.PERSONALIZED_VOLUME -> {
                scene.wire.onWrite = null
                scene.wire.onFlush = null
            }
        }
        scene.write(pending)
    }
    scene.expire()
    scene.expectStatus(SceneSettingStatus.APPLIED, "Listening mode has device confirmation")
    scene.expectStatus(SceneSettingStatus.FAILED, "Conversation setting was rejected before dispatch", SceneSetting.CONVERSATION_AWARENESS)
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Volume written without device report", SceneSetting.PERSONALIZED_VOLUME)
    expect(!scene.state.isApplying, "Grouped attempt settles after every setting completes")
}

private fun replacementDuringFlushCannotApply(resetDeviceSession: Boolean) = SceneWire().use { scene ->
    val command = scene.queue().single()
    val oldSession = scene.aacp.captureDeviceSession()
    scene.wire.onWrite = { scene.report() }
    scene.wire.onFlush = { scene.replaceConnection(resetDeviceSession) }
    scene.write(command)
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Connection replacement invalidates staged match and late write result")
    expect(scene.result().detail == SceneResultDetail.CONNECTION_CHANGED && scene.state.connectionChanged, "Explain replacement")
    scene.report(session = oldSession)
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Late old connection feedback cannot revive attempt")
    expect(scene.aacp.getControlCommandStatusSnapshot().isEmpty() || !resetDeviceSession,
        "Old write cannot repopulate cache after session reset")
}

private fun replacementWhileQueuedNeverWrites() = SceneWire().use { scene ->
    val command = scene.queue().single()
    scene.replaceConnection(resetDeviceSession = true)
    expect(!scene.write(command), "Retired queued command rejected")
    expect(scene.wire.bytes.size() == 0, "No write on old connection")
    val replacement = BluetoothConnectionManager.aacpSocket as WireSocket
    expect((replacement.wire as ByteArrayOutputStream).size() == 0, "No write leaked onto replacement connection")
    scene.expectStatus(SceneSettingStatus.FAILED, "Unsent command is failed, not unconfirmed")
}

private fun unresolvedRetryCannotClaimEarlierReply() = SceneWire().use { scene ->
    expect(scene.write(scene.queue().single()), "Initial command written")
    scene.expire()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Initial request unresolved")
    expect(scene.write(scene.queue().single()), "Retry command written")
    scene.report()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Reply cannot be attributed between unresolved same-setting requests")
    expect(scene.result().detail == SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, "Explain ambiguous retry")
    scene.replaceConnection(resetDeviceSession = true)
    expect(scene.write(scene.queue().single()), "New-session request written")
    scene.report()
    scene.expectStatus(SceneSettingStatus.APPLIED, "New session clears earlier ambiguity")
}

private fun temporaryContextLossCannotEraseSameTransportUncertainty() {
    for (selectionChanges in listOf(false, true)) SceneWire().use { scene ->
        expect(scene.write(scene.queue().single()), "Initial request written on current transport")
        scene.expire()
        scene.loseAndRestoreContext(selectionChanges)
        expect(scene.write(scene.queue().single()), "Retry after temporary context loss written")
        scene.report()
        scene.expectStatus(SceneSettingStatus.UNCONFIRMED,
            "Temporary context loss or selection-only change cannot make an old report attributable")
        expect(scene.result().detail == SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, "Same transport retains unresolved request history")
    }
}

private fun supersedingAttemptRetiresUnsentCallbacks() = SceneWire().use { scene ->
    val old = scene.queue().single()
    val next = scene.queue().single()
    scene.controller.dispatchCompleted(old.command)
    scene.controller.writeFinished(old.command, false)
    scene.controller.expire(old.command.attempt)
    expect(scene.state.attempt == next.command.attempt, "Old callbacks retain newer attempt")
    scene.expectStatus(SceneSettingStatus.WAITING, "Old completion/failure/deadline cannot settle replacement")
    expect(!scene.write(old), "Old queued write rejected")
    expect(scene.write(next), "Replacement write succeeds")
    scene.report()
    scene.expectStatus(SceneSettingStatus.APPLIED, "Unsent superseded request adds no ambiguity")
}

private fun manualChangeRetiresSceneConfirmation() = SceneWire().use { scene ->
    val command = scene.queue().single()
    expect(scene.write(command), "Scene command written")
    scene.controller.supersede(SceneSetting.LISTENING_MODE)
    scene.report()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Manual setting change retires scene confirmation")
    expect(scene.result().detail == SceneResultDetail.SETTING_CHANGED, "Explain superseding manual setting")
}

private fun manualChangesOnReplacementCannotRewriteEarlierResults() = SceneWire().use { scene ->
    val targets = plan(SceneValue(SceneSetting.LISTENING_MODE, 2),
        SceneValue(SceneSetting.CONVERSATION_AWARENESS, 2))
    scene.queue(targets).forEach { expect(scene.write(it), "Earlier-connection command written") }
    scene.report()
    scene.expectStatus(SceneSettingStatus.APPLIED, "Earlier listening-mode target was observed")
    scene.replaceConnection(resetDeviceSession = true)
    val history = scene.state

    // A manual edit now belongs to the replacement connection. The previous result is history.
    scene.controller.supersede(SceneSetting.LISTENING_MODE)
    scene.controller.supersede(SceneSetting.CONVERSATION_AWARENESS)
    scene.report(value = 3)

    expect(scene.state == history, "Manual changes on a replacement cannot rewrite earlier-connection history")
    scene.expectStatus(SceneSettingStatus.APPLIED, "Historical observed target remains observed")
    expect(scene.result(SceneSetting.CONVERSATION_AWARENESS).detail == SceneResultDetail.CONNECTION_CHANGED,
        "Historical unresolved target retains its actual retirement reason")
}

private fun replacementDuringDispatchCallbackPreservesRetryUncertainty() = SceneWire().use { scene ->
    val old = scene.queue().single()
    var replacement: QueuedWrite? = null
    expect(!scene.write(old) { replacement = scene.queue().single() }, "Retirement in dispatch callback suppresses old physical write")
    expect(scene.wire.bytes.size() == 0, "Old packet never reaches stream after replacement")
    scene.expectStatus(SceneSettingStatus.WAITING, "Old callbacks cannot settle replacement attempt")
    expect(scene.write(replacement!!), "Replacement request writes normally")
    scene.report()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Retired dispatch conservatively prevents claiming ambiguous retry feedback")
    expect(scene.result().detail == SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, "Explain retired-dispatch uncertainty")
}

private fun expiryDuringDispatchCallbackCannotReviveConfirmation() = SceneWire().use { scene ->
    val command = scene.queue().single()
    expect(!scene.write(command) { scene.expire() }, "Expiry in dispatch callback suppresses physical write")
    expect(scene.wire.bytes.size() == 0, "Expired command never reaches stream")
    scene.report()
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Observed dispatch remains conservatively unconfirmed after expiry")
    expect(!scene.state.isApplying, "Late dispatch and report cannot revive an expired attempt")
}

private fun asyncDeadlineDuringFlushRetiresTheWholeGroup() = SceneWire().use { scene ->
    val preset = plan(SceneValue(SceneSetting.LISTENING_MODE, 2),
        SceneValue(SceneSetting.CONVERSATION_AWARENESS, 2), SceneValue(SceneSetting.PERSONALIZED_VOLUME, 2))
    val flushEntered = CountDownLatch(1)
    val releaseFlush = CountDownLatch(1)
    val firstFlush = AtomicBoolean(true)
    scene.wire.onFlush = {
        if (firstFlush.compareAndSet(true, false)) {
            flushEntered.countDown()
            releaseFlush.reached("release flush after deadline")
        }
    }
    withSceneWorker(scene) { worker ->
        val completed = scene.queue(preset).map(worker::offer)
        try {
            flushEntered.reached("first group command has reached physical output")
            scene.report()
            scene.expectStatus(SceneSettingStatus.WAITING, "Report is pending while flush is blocked")
            scene.expire()
        } finally { releaseFlush.countDown() }
        completed.forEach { it.reached("expired command completed or discarded") }
        scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Late successful flush cannot revive expired sent setting")
        scene.expectStatus(SceneSettingStatus.FAILED, "Expired queued conversation command was not sent", SceneSetting.CONVERSATION_AWARENESS)
        scene.expectStatus(SceneSettingStatus.FAILED, "Expired queued volume command was not sent", SceneSetting.PERSONALIZED_VOLUME)
        expect(scene.wire.bytes.size() == controlReport().size, "Only the already-dispatched command reached the wire")

        worker.offer(scene.queue().single()).reached("same-transport retry completed")
        scene.report()
        scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Timeout history survives asynchronous late completion and retry")
        expect(scene.result().detail == SceneResultDetail.EARLIER_REQUEST_UNRESOLVED, "Explain unresolved sent command")
    }
}

private fun asyncReplacementDuringFlushCannotSendQueuedSettings() = SceneWire().use { scene ->
    val preset = plan(SceneValue(SceneSetting.LISTENING_MODE, 2), SceneValue(SceneSetting.CONVERSATION_AWARENESS, 2))
    val flushEntered = CountDownLatch(1)
    val releaseFlush = CountDownLatch(1)
    scene.wire.onFlush = {
        flushEntered.countDown()
        releaseFlush.reached("release flush after connection replacement")
    }
    withSceneWorker(scene) { worker ->
        val completed = scene.queue(preset).map(worker::offer)
        val previousSession = scene.context.deviceSession
        try {
            flushEntered.reached("old transport flush started")
            scene.report()
            scene.replaceConnection(resetDeviceSession = true)
        } finally { releaseFlush.countDown() }
        completed.forEach { it.reached("retired command completed or discarded") }
        scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Retired successful flush cannot confirm old connection")
        scene.expectStatus(SceneSettingStatus.FAILED, "Queued old-connection command did not dispatch", SceneSetting.CONVERSATION_AWARENESS)
        expect(scene.wire.bytes.size() == controlReport().size, "Only old in-flight command reached old stream")
        val replacement = BluetoothConnectionManager.aacpSocket as WireSocket
        expect((replacement.wire as ByteArrayOutputStream).size() == 0, "Queued work cannot migrate to replacement stream")

        worker.offer(scene.queue().single()).reached("replacement command completed")
        scene.report(session = previousSession)
        scene.expectStatus(SceneSettingStatus.WAITING, "Delayed report from same-MAC previous session cannot confirm")
        scene.report()
        scene.expectStatus(SceneSettingStatus.APPLIED, "Fresh replacement-session report remains usable")
    }
}

private fun asyncSupersedingGroupRetainsOnlySentSettingAmbiguity() = SceneWire().use { scene ->
    val oldPreset = plan(SceneValue(SceneSetting.LISTENING_MODE, 2),
        SceneValue(SceneSetting.CONVERSATION_AWARENESS, 2), SceneValue(SceneSetting.PERSONALIZED_VOLUME, 2))
    val newPreset = plan(SceneValue(SceneSetting.LISTENING_MODE, 3), SceneValue(SceneSetting.CONVERSATION_AWARENESS, 1))
    val flushEntered = CountDownLatch(1)
    val releaseFlush = CountDownLatch(1)
    val firstFlush = AtomicBoolean(true)
    scene.wire.onFlush = {
        if (firstFlush.compareAndSet(true, false)) {
            flushEntered.countDown()
            releaseFlush.reached("release superseded command flush")
        }
    }
    withSceneWorker(scene) { worker ->
        val oldCompletions = scene.queue(oldPreset).map(worker::offer)
        val newCompletions = try {
            flushEntered.reached("superseded command already reached physical output")
            scene.queue(newPreset).map(worker::offer)
        } finally { releaseFlush.countDown() }
        (oldCompletions + newCompletions).forEach { it.reached("superseded or replacement command completed") }
        expect(scene.wire.bytes.size() == 3 * controlReport().size,
            "Actual worker writes old in-flight mode and two replacement settings only")
        scene.report(value = 2)
        scene.expectStatus(SceneSettingStatus.WAITING, "Old target does not match replacement target")
        scene.report(value = 3)
        scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Sent superseded mode leaves conservative same-setting ambiguity")
        scene.report(SceneSetting.CONVERSATION_AWARENESS, 1)
        scene.expectStatus(SceneSettingStatus.APPLIED, "Discarded unsent conversation command adds no ambiguity", SceneSetting.CONVERSATION_AWARENESS)
        expect(!scene.state.isApplying, "Both replacement results settle independently")
    }
}

private fun unknownMultiByteSettingReportCannotConfirm() = SceneWire().use { scene ->
    expect(scene.write(scene.queue().single()), "Scene command sent")
    // The parser retains the second nonzero byte; the ViewModel rejects unknown payload shapes.
    scene.aacp.receivePacket(controlReport().apply { this[8] = 7 })
    scene.expectStatus(SceneSettingStatus.WAITING, "A matching first byte in an unknown payload is not a supported report")
    scene.report()
    scene.expectStatus(SceneSettingStatus.APPLIED, "A later canonical single-byte report remains usable")
}

private fun capturedOldReportCannotCrossSameMacSessionReset() = SceneWire().use { scene ->
    val first = AtomicBoolean(true)
    scene.aacp.registerControlCommandListener(Identifier.LISTENING_MODE, object : AACPManager.ControlCommandListener {
        override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
            if (first.compareAndSet(true, false)) {
                scene.replaceConnection(resetDeviceSession = true)
                expect(scene.write(scene.queue().single()), "New-session scene written before captured old report delivery")
            }
        }
    })
    scene.report()
    scene.expectStatus(SceneSettingStatus.WAITING, "Captured pre-reset report cannot confirm the new same-MAC session")
    scene.report()
    scene.expectStatus(SceneSettingStatus.APPLIED, "New-session report confirms the observed target")
}

private fun recreatedControllerObservesValuesWithoutDurableRequestHistory() = SceneWire().use { scene ->
    expect(scene.write(scene.queue().single()), "Original controller sent an unresolved request")
    scene.expire()
    val fresh = ScenePresetController().also { it.setContext(scene.context) }
    val command = fresh.begin(scene.context, plan()).single()
    scene.aacp.observeReceivedControlCommands { report ->
        if (report.identifier == Identifier.LISTENING_MODE) report.value.singleOrNull()?.let {
            fresh.received(scene.context.copy(deviceSession = report.deviceSession), SceneSetting.LISTENING_MODE,
                it.toInt(), report.sequence)
        }
    }.use {
        val sent = scene.aacp.sendPacketObserved(controlReport(), scene.socket, {
            fresh.writeStarted(command, scene.aacp.captureReceivedControlSequence())
        }) { fresh.isCurrent(command) }
        fresh.writeFinished(command, sent)
        fresh.dispatchCompleted(command)
        scene.report()
    }
    expect(fresh.state.value.results.single().status == SceneSettingStatus.APPLIED,
        "A fresh controller has no durable request history: this is a value observation, never a causal acknowledgement")
    scene.expectStatus(SceneSettingStatus.UNCONFIRMED, "Old controller cannot revive its expired attempt")
}

private fun actualModelPolicyOmitsUnsupportedSceneOptions() {
    expect(scenePresetPlans(AirPods().capabilities, premium = true).isEmpty(), "Model without listening modes must not offer scenes")
    val oldPro = scenePresetPlans(AirPodsPro1().capabilities, premium = true)
    expect(oldPro.isNotEmpty() && oldPro.none { it.id == ScenePresetId.BALANCED_FOCUS }, "Non-adaptive model must hide adaptive scene")
    expect(oldPro.all { preset ->
        preset.settings.all { it.setting == SceneSetting.LISTENING_MODE && it.value != 1 } &&
            preset.omitted[SceneSetting.CONVERSATION_AWARENESS] == SceneOmission.MODEL_UNSUPPORTED &&
            preset.omitted[SceneSetting.PERSONALIZED_VOLUME] == SceneOmission.MODEL_UNSUPPORTED
    }, "Unsupported controls must be omitted with model explanation")
    val locked = scenePresetPlans(AirPodsPro2USBC().capabilities, premium = false)
    expect(locked.all { preset ->
        preset.settings.size == 1 &&
            preset.omitted[SceneSetting.CONVERSATION_AWARENESS] == SceneOmission.PREMIUM_REQUIRED &&
            preset.omitted[SceneSetting.PERSONALIZED_VOLUME] == SceneOmission.PREMIUM_REQUIRED
    }, "Feature gating must be preserved by grouped presets")
}

internal fun sceneIntegrationCases(): List<IntegrationCase> = listOf(
    "local_write_without_reply_stays_unconfirmed" to ::localWriteWithoutReplyStaysUnconfirmed,
    "matching_reply_during_write_waits_for_successful_flush" to { matchingReplyDuringWriteWaitsForFlushOutcome(true) },
    "matching_reply_during_failed_flush_cannot_apply" to { matchingReplyDuringWriteWaitsForFlushOutcome(false) },
    "reply_during_blocked_successful_flush_waits_for_result" to { replyDuringBlockedFlushWaitsForResult(true) },
    "reply_during_blocked_failed_flush_cannot_apply" to { replyDuringBlockedFlushWaitsForResult(false) },
    "old_report_delivered_after_dispatch_cannot_confirm" to ::oldReportDeliveredAfterDispatchCannotConfirm,
    "report_while_waiting_for_output_cannot_confirm" to ::reportWhileWaitingForOutputCannotConfirm,
    "fresh_reply_after_successful_write_confirms" to ::freshReplyAfterSuccessfulWriteConfirms,
    "newer_wrong_reply_dominates_delayed_matching_reply" to ::newerWrongReplyCannotBeOverwrittenByDelayedMatchingReply,
    "grouped_preset_keeps_independent_results" to ::groupedPresetKeepsIndependentResults,
    "session_reset_during_flush_cannot_apply" to { replacementDuringFlushCannotApply(true) },
    "socket_replacement_during_flush_cannot_apply" to { replacementDuringFlushCannotApply(false) },
    "replacement_while_queued_never_writes" to ::replacementWhileQueuedNeverWrites,
    "unresolved_retry_cannot_claim_earlier_reply" to ::unresolvedRetryCannotClaimEarlierReply,
    "temporary_context_loss_preserves_same_transport_uncertainty" to ::temporaryContextLossCannotEraseSameTransportUncertainty,
    "superseding_attempt_retires_unsent_callbacks" to ::supersedingAttemptRetiresUnsentCallbacks,
    "manual_change_retires_scene_confirmation" to ::manualChangeRetiresSceneConfirmation,
    "manual_changes_on_replacement_preserve_earlier_results" to ::manualChangesOnReplacementCannotRewriteEarlierResults,
    "replacement_during_dispatch_callback_preserves_retry_uncertainty" to ::replacementDuringDispatchCallbackPreservesRetryUncertainty,
    "expiry_during_dispatch_callback_cannot_revive_confirmation" to ::expiryDuringDispatchCallbackCannotReviveConfirmation,
    "async_deadline_during_flush_retires_whole_group" to ::asyncDeadlineDuringFlushRetiresTheWholeGroup,
    "async_replacement_during_flush_cannot_send_queued_settings" to ::asyncReplacementDuringFlushCannotSendQueuedSettings,
    "async_superseding_group_retains_only_sent_ambiguity" to ::asyncSupersedingGroupRetainsOnlySentSettingAmbiguity,
    "unknown_multibyte_setting_report_cannot_confirm" to ::unknownMultiByteSettingReportCannotConfirm,
    "captured_old_report_cannot_cross_same_mac_session_reset" to ::capturedOldReportCannotCrossSameMacSessionReset,
    "recreated_controller_observes_without_durable_request_history" to ::recreatedControllerObservesValuesWithoutDurableRequestHistory,
    "actual_model_policy_omits_unsupported_scene_options" to ::actualModelPolicyOmitsUnsupportedSceneOptions,
)
