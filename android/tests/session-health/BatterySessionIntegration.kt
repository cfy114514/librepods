package me.kavishdevar.librepods.integration

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.BatterySessionRepository
import me.kavishdevar.librepods.data.BatterySessionEstimateState
import me.kavishdevar.librepods.data.batterySessionPresentation
import me.kavishdevar.librepods.data.recordBatterySessionPacket

/**
 * Owns only test transport/clock inputs. The parser, packet adapter, repository,
 * battery cache and published state are production implementations.
 */
private class BatteryFixture : Closeable {
    val clock = Clock()
    val repository = BatterySessionRepository { clock.now }
    var lease = repository.claim()
    var connection = requireNotNull(lease.connect(PEER_A))
    val socket = WireSocket()
    val aacp = AACPManager()
    val cache = AirPodsNotifications.BatteryNotification()
    val callbacks = AtomicInteger()
    val recorded = AtomicInteger()
    @Volatile var beforeRecord: (ByteArray) -> Unit = {}
    private data class Read(val connection: BatterySessionRepository.Connection, val at: Long)
    private val incoming = ThreadLocal<Read>()

    init {
        BluetoothConnectionManager.aacpSocket = socket
        aacp.beginDeviceSession()
        aacp.setPacketCallback(BatteryCallback { packet ->
            callbacks.incrementAndGet()
            val read = requireNotNull(incoming.get())
            beforeRecord(packet)
            cache.setBattery(packet)
            if (recordBatterySessionPacket(read.connection, packet, read.at)) recorded.incrementAndGet()
        })
    }

    fun receive(
        packet: ByteArray = batteryFrame(), at: Long = clock.now,
        token: BatterySessionRepository.Connection = connection,
        generation: Long = aacp.captureDeviceSession()
    ) {
        incoming.set(Read(token, at))
        try { aacp.receivePacket(packet, generation) } finally { incoming.remove() }
    }

    fun reconnect(address: String = PEER_A) {
        connection.close()
        connection = requireNotNull(lease.connect(address))
        aacp.beginDeviceSession()
    }

    fun snapshot() = repository.snapshots.value

    override fun close() {
        connection.close()
        lease.close()
        socket.close()
        if (BluetoothConnectionManager.aacpSocket === socket) BluetoothConnectionManager.aacpSocket = null
    }
}

private fun cachesWritesAndCollectorRestartsDoNotCreateObservations() = runBlocking {
    BatteryFixture().use { f ->
        val empty = f.snapshot()
        f.cache.setBatteryDirect(90, false, 80, false, 70, true)
        f.cache.setBattery(batteryFrame())
        expect(f.aacp.sendPacket(batteryFrame(), f.socket), "Exercise real outgoing socket writer")
        repeat(20) {
            val seen = mutableListOf<Any>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                f.repository.snapshots.collect { seen.add(it) }
            }
            expectEquals(1, seen.size, "Fresh collector sees one existing snapshot")
            expectEquals(empty, seen.single(), "Collection reads the same state")
            repeat(20) { f.cache.getBattery() }
            f.clock.advance(1_000)
            collector.cancel()
            collector.join()
        }
        expectEquals(empty, f.snapshot(), "Cached UI refresh, outgoing write and elapsed time never add samples")
        expectEquals(0, f.callbacks.get(), "Outgoing packet never invokes incoming battery callback")
        f.receive()
        expectEquals(1, f.callbacks.get(), "Actual incoming frame invokes callback")
        expectEquals(1, f.recorded.get(), "Actual incoming frame is accepted by production recorder")
        expect(f.snapshot() != empty, "Only the real received frame changes battery session")
        val observed = f.snapshot()
        repeat(100) { f.clock.advance(1_000); f.cache.getBattery() }
        expectEquals(observed, f.snapshot(), "No timer invents later observations from one real sample")
    }
}

private fun malformedFramesAndAbsentConnectionCannotRecord() {
    BatteryFixture().use { f ->
        val baseline = f.snapshot()
        for (size in 0 until 22) f.receive(batteryFrame().copyOf(size))
        f.receive(batteryFrame().apply { this[0] = 3 })
        expectEquals(0, f.callbacks.get(), "Truncated and wrong-header frames are rejected by actual AACP parser")
        expect(!recordBatterySessionPacket(null, batteryFrame(), f.clock.now), "No connection cannot record")
        for (packet in listOf(
            batteryFrame().copyOf(21), batteryFrame().copyOf(23),
            batteryFrame().apply { this[0] = 3 }, batteryFrame().apply { this[4] = 9 },
            batteryFrame().apply { this[5] = 1 }, batteryFrame().apply { this[6] = 2 }
        )) {
            expect(!recordBatterySessionPacket(f.connection, packet, f.clock.now), "Recorder independently rejects malformed battery frame")
        }
        expectEquals(baseline, f.snapshot(), "Malformed input cannot alter live time series")
    }
}

private fun retiredProtocolSessionNeverCallsBatteryConsumer() {
    BatteryFixture().use { f ->
        val generation = f.aacp.captureDeviceSession()
        f.aacp.beginDeviceSession()
        val baseline = f.snapshot()
        f.receive(generation = generation)
        expectEquals(0, f.callbacks.get(), "Old AACP generation is rejected before battery callback")
        expectEquals(baseline, f.snapshot(), "Retired protocol frame cannot add a sample")
        f.receive()
        expectEquals(1, f.recorded.get(), "Fresh AACP generation still records")
    }
}

private fun callbackCapturedBeforeSamePeerReconnectCannotRecord() {
    BatteryFixture().use { f ->
        f.receive()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.beforeRecord = { entered.countDown(); release.reached("release old receive callback") }
        withWorker { worker ->
            val pending = worker.submit { f.receive(batteryFrame(left = 79), at = f.clock.advance()) }
            entered.reached("old receive callback entered")
            f.reconnect()
            val replacement = f.snapshot()
            val acceptedBeforeRelease = f.recorded.get()
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            expectEquals(acceptedBeforeRelease, f.recorded.get(), "Captured old token cannot record after same-address reconnect")
            expectEquals(replacement, f.snapshot(), "Old in-flight callback cannot pollute replacement connection")
        }
        f.beforeRecord = {}
        f.receive(batteryFrame(left = 65), at = f.clock.advance())
        expectEquals(2, f.recorded.get(), "Replacement connection accepts its own fresh observation")
    }
}

private fun callbackCapturedBeforeServiceLeaseReplacementCannotRecord() {
    BatteryFixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.beforeRecord = { entered.countDown(); release.reached("release retired service callback") }
        withWorker { worker ->
            val pending = worker.submit { f.receive() }
            entered.reached("old service callback entered")
            f.lease.close()
            f.lease = f.repository.claim()
            f.connection = requireNotNull(f.lease.connect(PEER_A))
            val replacement = f.snapshot()
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            expectEquals(0, f.recorded.get(), "Retired service lease cannot publish after recreation")
            expectEquals(replacement, f.snapshot(), "Service recreation fences a callback already admitted by protocol parser")
        }
        f.beforeRecord = {}
        f.receive(at = f.clock.advance())
        expectEquals(1, f.recorded.get(), "Recreated service accepts new observations")
    }
}

private fun delayedOlderCallbackCannotOverwriteNewerRawReport() {
    BatteryFixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.beforeRecord = { packet ->
            if ((packet[9].toInt() and 0xff) == 80) {
                entered.countDown()
                release.reached("release older observation")
            }
        }
        withWorker { worker ->
            val pending = worker.submit { f.receive(batteryFrame(left = 80), at = f.clock.now) }
            entered.reached("older observation entered")
            f.receive(batteryFrame(left = 79), at = f.clock.advance(1_000))
            val newer = f.snapshot()
            expectEquals(1, f.recorded.get(), "Newer incoming report records while old callback waits")
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            expectEquals(1, f.recorded.get(), "Delayed older receipt is not accepted as a new sample")
            expectEquals(newer, f.snapshot(), "Receipt ordering survives callback delivery reordering")
        }
    }
}

private fun retiredPeerAndClosedTokenCannotReviveSession() {
    BatteryFixture().use { f ->
        f.receive()
        val old = f.connection
        f.reconnect(PEER_B)
        val replacement = f.snapshot()
        expect(!recordBatterySessionPacket(old, batteryFrame(left = 10), f.clock.advance()), "Old peer token is rejected")
        expectEquals(replacement, f.snapshot(), "Peer change isolates all old observations")
        f.connection.close()
        val closed = f.snapshot()
        expect(!recordBatterySessionPacket(f.connection, batteryFrame(), f.clock.advance()), "Closed connection cannot revive")
        expectEquals(closed, f.snapshot(), "Late frame cannot recreate disconnected session")
    }
}

private fun rawComponentIdentityAndInterruptionsKeepBudsIndependent() {
    BatteryFixture().use { f ->
        val start = f.clock.now
        val orders = listOf(listOf(8, 2, 4), listOf(2, 4, 8), listOf(4, 8, 2), listOf(8, 4, 2))
        repeat(4) { step ->
            if (step > 0) f.clock.advance(5 * MINUTE)
            f.receive(batteryFrame(left = 80 - step * 4, right = 70 - step * 3, case = step,
                order = orders[step]))
        }
        val observed = f.snapshot()
        expectEquals(listOf(80, 76, 72, 68), observed.left.observations.map { it.level }, "Left readings follow component identity across wire-order changes")
        expectEquals(listOf(70, 67, 64, 61), observed.right.observations.map { it.level }, "Right readings never borrow left or case percentage")
        expectEquals(List(4) { start + it * 5 * MINUTE }, observed.left.observations.map { it.receivedAtElapsedMs }, "History timestamps are actual raw receive times")
        var shown = batterySessionPresentation(PEER_A, observed, f.clock.now)
        expectEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, shown.left.state, "Sustained real left drain has enough evidence")
        expectEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, shown.right.state, "Sustained real right drain has enough evidence")
        val leftMinutes = requireNotNull(shown.left.roughRemainingMinutes)
        val rightMinutes = requireNotNull(shown.right.roughRemainingMinutes)
        // Unbuffered extrapolation of 12 points over 15 minutes at 68% is 85 minutes;
        // 9 points over 15 minutes at 61% is about 102. Conservative results stay below both.
        expect(leftMinutes in 1..85 && rightMinutes in 1..102, "Estimates stay finite, positive and below raw constant-drain projection")
        expect(leftMinutes < rightMinutes, "Faster-draining left bud has shorter remaining time in this fixture")
        val rightSegment = observed.right.segment

        f.clock.advance(5 * MINUTE)
        f.receive(batteryFrame(left = 69, right = 58, leftStatus = 1, case = 100, order = listOf(8, 4, 2)))
        shown = batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now)
        expectEquals(BatterySessionEstimateState.CHARGING, shown.left.state, "Charging left cannot keep a drain estimate")
        expect(shown.left.roughRemainingMinutes == null, "Charging left estimate is withdrawn")
        expectEquals(rightSegment, f.snapshot().right.segment, "Left charging does not reset right segment")
        expectEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, shown.right.state, "Right estimate survives independent left charging")

        f.clock.advance(5 * MINUTE)
        f.receive(batteryFrame(left = 70, right = 55, order = listOf(2, 8, 4)))
        shown = batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now)
        expectEquals(BatterySessionEstimateState.COLLECTING, shown.left.state, "Left resume starts a fresh discharge window")
        expectEquals(1, shown.left.points.size, "Left resume does not borrow pre-charge points")
        expectEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, shown.right.state, "Right remains independently estimable")

        f.clock.advance(5 * MINUTE)
        f.receive(batteryFrame(left = 255, right = 52))
        shown = batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now)
        expectEquals(70, f.cache.getBattery().first().level, "Display cache retains prior percentage for unavailable raw level")
        expect(f.snapshot().left.level == null, "Raw sentinel cannot be replaced with cached percentage in time series")
        expectEquals(BatterySessionEstimateState.UNAVAILABLE, shown.left.state, "Sentinel invalidates only its own bud")
        expectEquals(BatterySessionEstimateState.ROUGH_ESTIMATE, shown.right.state, "Healthy right evidence remains available")
        val right = f.snapshot().right
        f.clock.advance(MINUTE)
        f.receive(batteryFrame(left = 49, order = listOf(4, 4, 8)))
        expectEquals(right, f.snapshot().right, "Omitted right bud does not receive a fake timestamp")
        expectEquals(BatterySessionEstimateState.UNAVAILABLE,
            batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now).left.state,
            "Duplicate component reports cannot create an arbitrary valid left sample")
    }
}

private fun deliveryAgeSilenceGapAndClockRollbackDoNotBridgeEvidence() {
    BatteryFixture().use { f ->
        f.receive()
        val first = f.snapshot()
        val originalReceipt = f.clock.now
        f.clock.advance(16_000)
        f.receive(batteryFrame(left = 79), at = originalReceipt)
        f.receive(batteryFrame(left = 79), at = f.clock.now + 1)
        expectEquals(first, f.snapshot(), "Stale and future receipt stamps do not change series")
        val packet = batteryFrame(left = 79, right = 69)
        f.receive(packet)
        val copied = f.snapshot()
        packet.fill(0)
        expectEquals(copied, f.snapshot(), "Published samples do not alias a mutable wire buffer")
        f.clock.advance(5 * MINUTE + 1)
        var shown = batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now)
        expectEquals(BatterySessionEstimateState.STALE, shown.left.state, "Silence expires display-time evidence")
        expect(shown.left.roughRemainingMinutes == null, "Silence withdraws a runtime estimate")
        expectEquals(copied, f.snapshot(), "Display expiry does not synthesize an ingestion event")
        f.receive(batteryFrame(left = 78, right = 68))
        shown = batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now)
        expectEquals(1, shown.left.points.size, "First post-gap observation starts new segment")
        expectEquals(1, shown.right.points.size, "Gap also isolates the right series")
        f.clock.now -= 1_000
        f.receive(batteryFrame(left = 77, right = 67))
        shown = batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now)
        expectEquals(BatterySessionEstimateState.CLOCK_CHANGED, shown.left.state, "Clock rollback cannot mix old and new elapsed times")
        expect(shown.left.points.isEmpty() && shown.right.points.isEmpty(), "Clock discontinuity clears the regression input")
        f.clock.advance(MINUTE)
        f.receive(batteryFrame(left = 76, right = 66))
        expectEquals(BatterySessionEstimateState.COLLECTING,
            batterySessionPresentation(PEER_A, f.snapshot(), f.clock.now).left.state,
            "Fresh observations recover after clock reset")
    }
}

private fun longRawSessionRetainsBoundedRealObservations() {
    BatteryFixture().use { f ->
        val receipts = mutableSetOf<Long>()
        repeat(600) { step ->
            if (step > 0) f.clock.advance(MINUTE)
            receipts.add(f.clock.now)
            f.receive(batteryFrame(left = 95 - step % 40, right = 85 - step % 30))
            val snapshot = f.snapshot()
            for (side in listOf(snapshot.left, snapshot.right)) {
                expect(side.observations.size <= 240, "Long session retains at most 240 observations per bud")
                expect(side.observations.all { it.receivedAtElapsedMs in receipts }, "Every retained point belongs to an actual raw receive")
                expect(side.observations.all { f.clock.now - it.receivedAtElapsedMs <= 4 * 60 * MINUTE }, "Retention never keeps evidence older than four hours")
            }
            val shown = batterySessionPresentation(PEER_A, snapshot, f.clock.now)
            for (side in listOf(shown.left, shown.right)) {
                expect(side.points.all { it.receivedAtElapsedMs in receipts }, "Presentation points also retain real receipt times")
                expect(side.roughRemainingMinutes == null || side.roughRemainingMinutes in 0..720,
                    "Any emitted runtime is finite and bounded")
            }
        }
        val previous = f.snapshot()
        f.reconnect()
        expect(f.snapshot().left.observations.isEmpty() && f.snapshot().right.observations.isEmpty(), "Same-peer new connection does not carry prior long session")
        f.receive(batteryFrame(left = 50, right = 45), at = f.clock.advance())
        expectEquals(1, f.snapshot().left.observations.size, "New connection starts with exactly one real reading")
        expect(previous.left.observations.size > 1, "Previously published snapshot stays immutable")
    }
}

internal fun batteryCases(): List<IntegrationCase> = listOf(
    "battery_cache_write_and_flow_restart_are_not_observations" to ::cachesWritesAndCollectorRestartsDoNotCreateObservations,
    "battery_malformed_and_absent_connection_do_not_record" to ::malformedFramesAndAbsentConnectionCannotRecord,
    "battery_retired_protocol_session_never_calls_consumer" to ::retiredProtocolSessionNeverCallsBatteryConsumer,
    "battery_inflight_callback_is_fenced_by_same_peer_reconnect" to ::callbackCapturedBeforeSamePeerReconnectCannotRecord,
    "battery_inflight_callback_is_fenced_by_service_recreation" to ::callbackCapturedBeforeServiceLeaseReplacementCannotRecord,
    "battery_older_callback_cannot_overwrite_newer_report" to ::delayedOlderCallbackCannotOverwriteNewerRawReport,
    "battery_retired_peer_and_closed_token_cannot_revive" to ::retiredPeerAndClosedTokenCannotReviveSession,
    "battery_raw_component_identity_and_interruptions_are_independent" to ::rawComponentIdentityAndInterruptionsKeepBudsIndependent,
    "battery_delivery_age_silence_gap_and_clock_do_not_bridge" to ::deliveryAgeSilenceGapAndClockRollbackDoNotBridgeEvidence,
    "battery_long_raw_session_retains_bounded_real_observations" to ::longRawSessionRetainsBoundedRealObservations,
)
