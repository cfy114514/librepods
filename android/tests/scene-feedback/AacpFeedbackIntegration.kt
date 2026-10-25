package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers

internal typealias Identifier = ControlCommandIdentifiers
internal typealias IntegrationCase = Pair<String, () -> Unit>

/** Wire bytes are literal protocol fixtures, independent of the production packet builder. */
internal fun controlReport(identifier: Identifier = Identifier.LISTENING_MODE, value: Int = 2): ByteArray =
    byteArrayOf(4, 0, 4, 0, 9, 0, identifier.value, value.toByte(), 0, 0, 0)

internal class WireSocket(val wire: OutputStream = ByteArrayOutputStream()) : BluetoothSocket() {
    @Volatile var connected = true
    @Volatile var onResolve: (() -> Unit)? = null
    override val isConnected: Boolean get() = connected
    override val inputStream = ByteArrayInputStream(byteArrayOf())
    override val outputStream: OutputStream get() { onResolve?.invoke(); return wire }
    override fun close() { connected = false }
}

internal fun expect(condition: Boolean, description: String) {
    if (!condition) throw AssertionError(description)
}

internal fun expectBytes(expected: ByteArray, actual: ByteArray, description: String) =
    expect(expected.contentEquals(actual), "$description: expected ${expected.toList()}, got ${actual.toList()}")

internal fun CountDownLatch.reached(description: String) =
    expect(await(5, TimeUnit.SECONDS), "Timed out: $description")

internal fun <T> withWorker(block: (ExecutorService) -> T): T {
    val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scene-feedback-integration").apply { isDaemon = true }
    }
    try { return block(worker) } finally {
        worker.shutdownNow()
        expect(worker.awaitTermination(5, TimeUnit.SECONDS), "Worker did not terminate")
    }
}

private fun manager(socket: WireSocket = WireSocket()): AACPManager {
    BluetoothConnectionManager.aacpSocket = socket
    return AACPManager().apply { beginDeviceSession() }
}

private fun outgoingCacheAndListenersNeverBecomeReports() {
    val socket = WireSocket()
    val aacp = manager(socket)
    val reports = mutableListOf<AACPManager.ReceivedControlCommand>()
    var ordinaryCalls = 0
    aacp.registerControlCommandListener(Identifier.LISTENING_MODE, object : AACPManager.ControlCommandListener {
        override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) { ordinaryCalls++ }
    })
    aacp.observeReceivedControlCommands { reports.add(it) }.use {
        val baseline = aacp.captureReceivedControlSequence()
        expect(aacp.sendPacket(controlReport(), socket), "Successful output should be accepted")
        expectBytes(controlReport(), (socket.wire as ByteArrayOutputStream).toByteArray(), "Exact outgoing packet")
        expect(reports.isEmpty(), "Outgoing writes must never count as device reports")
        expect(aacp.captureReceivedControlSequence() == baseline, "Outgoing writes must not move receive watermark")
        expect(ordinaryCalls == 1, "Exercise actual optimistic ordinary-listener publication")
        expect(aacp.getControlCommandStatus(Identifier.LISTENING_MODE)?.value?.first() == 2.toByte(), "Optimistic cache updated")
        expect(aacp.setControlCommandStatusValue(Identifier.LISTENING_MODE, byteArrayOf(3, 0, 0, 0)), "Direct cache update accepted")
        expect(ordinaryCalls == 2, "Direct optimistic update reaches ordinary listener")
        expect(reports.isEmpty(), "Direct cache changes must never count as device reports")
        expect(aacp.captureReceivedControlSequence() == baseline, "Outgoing/cache changes must not move receive watermark")
        aacp.receivePacket(controlReport())
        expect(reports.size == 1, "Actual incoming packet must produce one report")
        expect(reports.single().sequence > baseline, "Only incoming report advances sequence")
        expect(reports.single().deviceSession == aacp.captureDeviceSession(), "Report retains device session")
    }
}

private fun observerValuesAndRegistrationsAreIndependent() {
    val aacp = manager()
    val seen = mutableListOf<AACPManager.ReceivedControlCommand>()
    val mutator = aacp.observeReceivedControlCommands { it.value[0] = 99 }
    val reader = aacp.observeReceivedControlCommands { seen.add(it) }
    try {
        val packet = controlReport()
        aacp.receivePacket(packet)
        packet[7] = 77
        expect(seen.size == 1, "Both registrations remain live")
        expectBytes(byteArrayOf(2), seen.single().value, "Observers own normalized payload copies")
        expectBytes(byteArrayOf(2), aacp.getControlCommandStatus(Identifier.LISTENING_MODE)!!.value, "Observer cannot corrupt cache")
        mutator.close()
        mutator.close()
        aacp.receivePacket(controlReport(value = 3))
        expect(seen.size == 2 && seen.last().value[0] == 3.toByte(), "Closing another registration preserves observer")
        reader.close()
        aacp.receivePacket(controlReport(value = 4))
        expect(seen.size == 2, "Closed observer receives no later reports")
    } finally { mutator.close(); reader.close() }
}

private fun invalidAndRetiredPacketsCannotPublishReports() {
    val aacp = manager()
    val reports = mutableListOf<AACPManager.ReceivedControlCommand>()
    aacp.observeReceivedControlCommands { reports.add(it) }.use {
        val before = aacp.captureReceivedControlSequence()
        aacp.receivePacket(controlReport().copyOf(10))
        aacp.receivePacket(controlReport().apply { this[0] = 3 })
        aacp.receivePacket(controlReport().apply { this[6] = 0x7f })
        aacp.receivePacket(controlReport(), canReceive = { false })
        expect(reports.isEmpty(), "Malformed or retired frames must not publish reports")
        expect(aacp.captureReceivedControlSequence() == before, "Rejected receive frames cannot move watermark")
        val retired = aacp.captureDeviceSession()
        aacp.receivePacket(controlReport())
        val first = reports.single()
        aacp.beginDeviceSession()
        expect(aacp.getControlCommandStatusSnapshot().isEmpty(), "Reset clears old cache")
        aacp.receivePacket(controlReport(), retired)
        expect(reports.size == 1, "Retired generation cannot publish after reset")
        aacp.receivePacket(controlReport(value = 3))
        expect(reports.size == 2, "Owned observer survives a device-session reset")
        expect(reports.last().sequence > first.sequence, "Receive sequence must remain monotonic across reset")
        expect(reports.last().deviceSession != first.deviceSession, "New session must be identified distinctly")
    }
}

private fun reportStampPrecedesOrdinaryListenerReentrantSend() {
    val socket = WireSocket()
    val aacp = manager(socket)
    val firstListenerCall = AtomicBoolean(true)
    var dispatchBaseline = -1L
    val reports = mutableListOf<AACPManager.ReceivedControlCommand>()
    aacp.observeReceivedControlCommands { reports.add(it) }.use {
        aacp.registerControlCommandListener(Identifier.LISTENING_MODE, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                if (firstListenerCall.compareAndSet(true, false)) {
                    expect(aacp.sendPacketObserved(controlReport(), socket, {
                        dispatchBaseline = aacp.captureReceivedControlSequence()
                    }), "Reentrant local write must finish")
                }
            }
        })
        aacp.receivePacket(controlReport())
        expect(dispatchBaseline > 0, "Ordinary listener performed a new dispatch")
        expect(reports.size == 1, "Reentrant outgoing write must not create a second incoming report")
        expect(reports.single().sequence <= dispatchBaseline, "A pre-dispatch report delivered afterward retains its earlier stamp")
    }
}

private fun receiveWatermarkDoesNotAcquireManagerMonitor() {
    val aacp = manager()
    withWorker { worker ->
        synchronized(aacp) {
            val capture = worker.submit<Long> { aacp.captureReceivedControlSequence() }
            expect(capture.get(5, TimeUnit.SECONDS) == 0L, "Watermark read must not wait for AACP monitor while output lock is held")
        }
    }
}

private fun streamLockWaitPrecedesDispatchBaseline() {
    val wire = ByteArrayOutputStream()
    val socket = WireSocket(wire)
    val resolved = CountDownLatch(1)
    socket.onResolve = { resolved.countDown() }
    val aacp = manager(socket)
    val dispatches = AtomicInteger()
    var baseline = -1L
    withWorker { worker ->
        val sent = synchronized(wire) {
            val future = worker.submit<Boolean> {
                aacp.sendPacketObserved(controlReport(), socket, {
                    expect(Thread.holdsLock(wire), "Dispatch runs while actual output stream monitor is held")
                    baseline = aacp.captureReceivedControlSequence()
                    dispatches.incrementAndGet()
                })
            }
            resolved.reached("writer captured original stream")
            expect(dispatches.get() == 0, "Queued writer must not dispatch before acquiring stream")
            aacp.receivePacket(controlReport())
            future
        }
        expect(sent.get(5, TimeUnit.SECONDS), "Writer completes after releasing output monitor")
    }
    expect(dispatches.get() == 1, "Exactly one dispatch per write")
    expect(baseline == aacp.captureReceivedControlSequence() && baseline > 0, "Report while waiting must precede dispatch watermark")
}

private fun retirementWhileWaitingForStreamPreventsDispatch(retire: (AACPManager, WireSocket, AtomicBoolean) -> Unit) {
    val wire = ByteArrayOutputStream()
    val socket = WireSocket(wire)
    val resolved = CountDownLatch(1)
    socket.onResolve = { resolved.countDown() }
    val aacp = manager(socket)
    val allowed = AtomicBoolean(true)
    val dispatches = AtomicInteger()
    withWorker { worker ->
        val sent = synchronized(wire) {
            val future = worker.submit<Boolean> {
                aacp.sendPacketObserved(controlReport(), socket, { dispatches.incrementAndGet() }, allowed::get)
            }
            resolved.reached("writer captured old socket stream")
            retire(aacp, socket, allowed)
            future
        }
        expect(!sent.get(5, TimeUnit.SECONDS), "Retired write must report failure")
    }
    expect(dispatches.get() == 0, "Retired writer cannot publish dispatch")
    expect(wire.size() == 0, "Retired writer cannot touch captured stream")
    expect(aacp.getControlCommandStatusSnapshot().isEmpty(), "Retired writer cannot populate cache")
    expect(aacp.captureReceivedControlSequence() == 0L, "Retired writer cannot publish device feedback")
}

private fun writeAndFlushFailuresNeverPopulateCache() {
    for (failOnFlush in listOf(false, true)) {
        var wrote = false
        val wire = object : OutputStream() {
            override fun write(value: Int) {
                if (!failOnFlush) throw IOException("controlled write failure")
                wrote = true
            }
            override fun flush() { throw IOException("controlled flush failure") }
        }
        val socket = WireSocket(wire)
        val aacp = manager(socket)
        var dispatches = 0
        expect(!aacp.sendPacketObserved(controlReport(), socket, { dispatches++ }), "Transport failure must return false")
        expect(dispatches == 1, "Dispatch alone is not write success")
        expect(wrote == failOnFlush, "Exercise intended transport failure point")
        expect(aacp.getControlCommandStatusSnapshot().isEmpty(), "Failed write/flush cannot populate optimistic cache")
        expect(aacp.captureReceivedControlSequence() == 0L, "Failed output cannot publish incoming feedback")
    }
}

private fun aacpIntegrationCases(): List<IntegrationCase> = listOf(
    "outgoing_cache_and_listeners_never_become_reports" to ::outgoingCacheAndListenersNeverBecomeReports,
    "observer_values_and_registrations_are_independent" to ::observerValuesAndRegistrationsAreIndependent,
    "invalid_and_retired_packets_cannot_publish_reports" to ::invalidAndRetiredPacketsCannotPublishReports,
    "report_stamp_precedes_reentrant_send" to ::reportStampPrecedesOrdinaryListenerReentrantSend,
    "receive_watermark_does_not_acquire_manager_monitor" to ::receiveWatermarkDoesNotAcquireManagerMonitor,
    "stream_lock_wait_precedes_dispatch_baseline" to ::streamLockWaitPrecedesDispatchBaseline,
    "socket_replacement_while_waiting_prevents_dispatch" to {
        val replacement = WireSocket()
        retirementWhileWaitingForStreamPreventsDispatch { _, _, _ -> BluetoothConnectionManager.aacpSocket = replacement }
        expect((replacement.wire as ByteArrayOutputStream).size() == 0, "Old command cannot reach replacement socket")
    },
    "session_reset_while_waiting_prevents_dispatch" to {
        retirementWhileWaitingForStreamPreventsDispatch { aacp, _, _ -> aacp.beginDeviceSession() }
    },
    "cancel_while_waiting_prevents_dispatch" to {
        retirementWhileWaitingForStreamPreventsDispatch { _, _, allowed -> allowed.set(false) }
    },
    "disconnect_while_waiting_prevents_dispatch" to {
        retirementWhileWaitingForStreamPreventsDispatch { _, socket, _ -> socket.close() }
    },
    "write_and_flush_failures_never_populate_cache" to ::writeAndFlushFailuresNeverPopulateCache,
)

fun main(args: Array<String>) {
    val all = aacpIntegrationCases() + sceneIntegrationCases()
    val selected = if (args.isEmpty()) all else all.filter { it.first == args.single() }
    expect(selected.isNotEmpty(), "Unknown integration case: ${args.toList()}")
    var failed = 0
    selected.forEach { (name, run) ->
        try {
            run()
            println("PASS $name")
        } catch (failure: Throwable) {
            failed++
            System.err.println("FAIL $name: ${failure.message}")
            failure.printStackTrace()
        } finally { BluetoothConnectionManager.aacpSocket = null }
    }
    println("Scene feedback integration: ${selected.size - failed}/${selected.size} passed")
    if (failed != 0) throw AssertionError("$failed integration cases failed")
}
