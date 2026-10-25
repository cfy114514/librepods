package me.kavishdevar.librepods.data

import java.io.Closeable
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One service lease and one opaque connection own the receive-only series. All mutations and
 * publications share one lock. The supplied monotonic clock must be nonblocking; there is no IO,
 * worker, timer, persistence or application callback. Closing an old handle cannot affect its heir.
 */
internal class BatterySessionRepository(private val elapsedRealtime: () -> Long) {
    private class Side(val component: Int) {
        val observations = ArrayDeque<BatterySessionObservation>()
        var state = BatterySessionPartState.WAITING
        var segment = 0L
        var interruption = BatterySessionInterruption.CONNECTION
        var lastReceivedAt: Long? = null
        var level: Int? = null

        fun interrupt(reason: BatterySessionInterruption) {
            segment++
            interruption = reason
        }

        fun snapshot() = BatterySessionSeries(component, observations.toList(), state, segment,
            interruption, lastReceivedAt, level)

        fun prune(now: Long) {
            while (observations.isNotEmpty() &&
                (observations.size > BATTERY_SESSION_MAX_OBSERVATIONS ||
                    now - observations.first().receivedAtElapsedMs > BATTERY_SESSION_WINDOW_MS)) {
                observations.removeFirst()
            }
        }

        fun record(battery: Battery?, received: Long) {
            val previousTime = lastReceivedAt
            if (previousTime != null && received <= previousTime) return
            lastReceivedAt = received
            if (battery == null || !battery.isAvailableReading()) {
                interrupt(BatterySessionInterruption.UNAVAILABLE)
                state = BatterySessionPartState.UNAVAILABLE
                level = null
                return
            }
            val charging = battery.status == BatteryStatus.CHARGING ||
                battery.status == BatteryStatus.OPTIMIZED_CHARGING
            val previousLevel = level
            when {
                charging && state != BatterySessionPartState.CHARGING -> interrupt(BatterySessionInterruption.CHARGING)
                !charging && state != BatterySessionPartState.DISCHARGING -> interrupt(interruption)
                previousTime != null && received - previousTime > BATTERY_SESSION_MAX_GAP_MS -> interrupt(BatterySessionInterruption.GAP)
                !charging && previousLevel != null &&
                    (battery.level > previousLevel || abs(battery.level - previousLevel) > 10) ->
                    interrupt(BatterySessionInterruption.LEVEL_CHANGE)
            }
            state = if (charging) BatterySessionPartState.CHARGING else BatterySessionPartState.DISCHARGING
            level = battery.level
            val last = observations.lastOrNull()
            if (last == null || last.segment != segment || last.level != battery.level ||
                received - last.receivedAtElapsedMs >= BATTERY_SESSION_SAMPLE_INTERVAL_MS) {
                observations.addLast(BatterySessionObservation(received, battery.level, charging, segment))
            }
        }
    }

    private val lock = Any()
    private val state = MutableStateFlow(BatterySessionSnapshot())
    val snapshots: StateFlow<BatterySessionSnapshot> = state.asStateFlow()
    private var owner: Any? = null
    private var connection: Any? = null
    private var address = ""
    private var startedAt = 0L
    private var lastClock: Long? = null
    private var lastPacketAt: Long? = null
    private var left = Side(BatteryComponent.LEFT)
    private var right = Side(BatteryComponent.RIGHT)

    internal class Lease internal constructor(private val repository: BatterySessionRepository, private val owner: Any) : Closeable {
        fun connect(address: String): Connection? = repository.connect(owner, address)
        override fun close() { repository.release(owner) }
    }

    internal class Connection internal constructor(
        private val repository: BatterySessionRepository, private val owner: Any, private val token: Any
    ) : Closeable {
        /** Call only for fresh raw receive-path readings from this handle's original connection. */
        fun recordReceived(batteries: List<Battery>, receivedAtElapsedMs: Long): Boolean =
            repository.record(owner, token, batteries, receivedAtElapsedMs)
        override fun close() { repository.disconnect(owner, token) }
    }

    fun claim(): Lease = synchronized(lock) {
        val token = Any()
        owner = token
        clear()
        publish()
        Lease(this, token)
    }

    private fun connect(expectedOwner: Any, peer: String): Connection? = synchronized(lock) {
        if (owner !== expectedOwner) return null
        clear()
        val identity = batteryHistoryIdentity(peer)
        val now = elapsedRealtime()
        if (identity == null || now < 0) {
            publish()
            return null
        }
        address = identity
        startedAt = now
        lastClock = now
        val token = Any()
        connection = token
        publish()
        Connection(this, expectedOwner, token)
    }

    private fun record(expectedOwner: Any, token: Any, batteries: List<Battery>, received: Long): Boolean = synchronized(lock) {
        if (owner !== expectedOwner || connection !== token) return false
        val now = elapsedRealtime()
        val previousClock = lastClock
        if (now < 0 || (previousClock != null && now < previousClock)) {
            // An elapsed-clock discontinuity cannot share a regression window with old points.
            left = Side(BatteryComponent.LEFT).apply { interruption = BatterySessionInterruption.CLOCK }
            right = Side(BatteryComponent.RIGHT).apply { interruption = BatterySessionInterruption.CLOCK }
            startedAt = now.coerceAtLeast(0)
            lastClock = now
            lastPacketAt = null
            publish()
            return false
        }
        lastClock = now
        if (received < startedAt || received > now || now - received > BATTERY_SESSION_MAX_DELIVERY_AGE_MS ||
            lastPacketAt?.let { received < it } == true) return false

        var accepted = false
        for (side in listOf(left, right)) {
            val reports = batteries.filter { it.component == side.component }
            // Omitted parts do not acquire a report time. Contradictory duplicates invalidate the
            // affected part; never pick whichever duplicate happens to be first or last.
            if (reports.isNotEmpty() && side.lastReceivedAt?.let { received <= it } != true) {
                side.record(reports.singleOrNull(), received)
                accepted = true
            }
            side.prune(now)
        }
        if (accepted) lastPacketAt = received
        publish()
        accepted
    }

    private fun release(expectedOwner: Any): Unit = synchronized(lock) {
        if (owner !== expectedOwner) return
        owner = null
        connection = null
        publish()
    }

    private fun disconnect(expectedOwner: Any, token: Any): Unit = synchronized(lock) {
        if (owner !== expectedOwner || connection !== token) return
        connection = null
        publish()
    }

    private fun clear() {
        connection = null
        address = ""
        lastClock = null
        lastPacketAt = null
        left = Side(BatteryComponent.LEFT)
        right = Side(BatteryComponent.RIGHT)
    }

    private fun publish() {
        state.value = BatterySessionSnapshot(address, connection != null, left.snapshot(), right.snapshot())
    }
}
