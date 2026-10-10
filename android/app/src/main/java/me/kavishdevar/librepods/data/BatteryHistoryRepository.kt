package me.kavishdevar.librepods.data

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.utils.KeyedWorkSession

/** Shared display-only state and one pending disk write; no disk operation owns the state lock. */
internal class BatteryHistoryRepository(
    initial: BatteryHistorySnapshot, dispatcher: CoroutineDispatcher,
    private val save: (BatteryHistorySnapshot) -> Unit, private val elapsed: () -> Long,
    private val wallTime: () -> Long, private val onError: (Exception) -> Unit = {},
    private val load: (() -> BatteryHistorySnapshot)? = null
) : Closeable {
    private class LoadAttempt { val done = CountDownLatch(1) }
    private data class Write(val owner: Any, val snapshot: BatteryHistorySnapshot, val force: Boolean)
    private val lock = Any()
    private var latest = initial
    private val state = MutableStateFlow(initial)
    val snapshots: StateFlow<BatteryHistorySnapshot> = state.asStateFlow()
    private var loaded = load == null
    private var loading: LoadAttempt? = null
    private var closed = false
    private var canMergeInitial = true
    private val loadScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var owner: Any? = null
    private var desired: Write? = null
    private var forceRequired = false
    private val persistence = BatteryHistoryPersistence()
    private val worker = KeyedWorkSession<Unit, Write>(setOf(Unit), dispatcher, onError) { request ->
        val needed = synchronized(lock) {
            if (owner !== request.owner || desired !== request) return@KeyedWorkSession
            persistence.shouldSave(request.snapshot, elapsed(), request.force)
        }
        if (needed) {
            save(request.snapshot)
            synchronized(lock) {
                // A successful old call describes the actual file; the serial successor then
                // compares against it and overwrites it when its accepted state differs.
                persistence.saved(request.snapshot, elapsed())
                if (owner === request.owner && desired?.snapshot == request.snapshot) forceRequired = false
            }
        } else synchronized(lock) {
            if (desired === request && request.force) forceRequired = false
        }
    }

    init {
        if (loaded && initial.address.isNotBlank()) persistence.saved(initial, elapsed())
        requestLoad()
    }

    /** Only one load attempt; a later explicit consumer/report can retry a failed read. */
    private fun requestLoad(): LoadAttempt? {
        val attempt = synchronized(lock) {
            if (loaded || closed) return null
            loading?.let { return it }
            LoadAttempt().also { loading = it }
        }
        loadScope.launch {
            try {
                val disk = requireNotNull(load).invoke()
                synchronized(lock) {
                    if (closed) return@launch
                    // Keep the disk snapshot as the successful baseline, then overlay only
                    // reports from its still-current peer. A pre-load peer change discards it.
                    if (disk.address.isNotBlank()) persistence.saved(disk, elapsed())
                    latest = when {
                        latest.address.isBlank() -> disk
                        canMergeInitial && latest.address == disk.address ->
                            latest.copy(readings = disk.readings + latest.readings)
                        else -> latest
                    }
                    loaded = true
                    state.value = latest
                    owner?.let { if (!persistence.isSaved(latest)) enqueue(it) }
                }
            } catch (error: Exception) {
                // No write may replace an unread file. Keep accepted reports in memory
                // and retry only on the next explicit read, claim, report or flush.
                onError(error)
            }
        }.invokeOnCompletion {
            synchronized(lock) { if (loading === attempt) loading = null }
            attempt.done.countDown()
        }
        return attempt
    }

    /** For IO publishers that need the initial file before rendering, never a UI read. */
    fun loadedSnapshot(): BatteryHistorySnapshot {
        requestLoad()?.done?.await()
        return snapshot()
    }

    fun requestInitialLoad() { requestLoad() }

    internal class Lease(private val parent: BatteryHistoryRepository, private val token: Any) : Closeable {
        private var closed = false
        @Synchronized fun record(address: String, batteries: List<Battery>): Boolean = !closed && parent.record(token, address, batteries)
        @Synchronized fun flush(): Boolean = !closed && parent.flush(token)
        @Synchronized override fun close() { closed = true }
    }

    fun snapshot(): BatteryHistorySnapshot = synchronized(lock) { latest }

    fun claim(): Lease {
        val lease = synchronized(lock) {
            val token = Any()
            owner = token
            forceRequired = true
            if (!persistence.isSaved(latest)) enqueue(token)
            Lease(this, token)
        }
        requestLoad()
        return lease
    }

    private fun record(token: Any, address: String, batteries: List<Battery>): Boolean {
        val accepted = synchronized(lock) {
            if (owner !== token || closed) return false
            val updated = latest.merge(address, batteries, wallTime())
            if (updated == latest) return false
            if (!loaded && latest.address.isNotBlank() && latest.address != updated.address) canMergeInitial = false
            latest = updated
            state.value = updated
            enqueue(token)
            true
        }
        requestLoad()
        return accepted
    }

    private fun flush(token: Any): Boolean {
        val accepted = synchronized(lock) {
            if (owner !== token || closed) return false
            forceRequired = true
            enqueue(token)
        }
        requestLoad()
        return accepted
    }

    private fun enqueue(token: Any): Boolean {
        if (!loaded) return true
        if (latest.address.isBlank()) return false
        val request = Write(token, latest, forceRequired)
        desired = request
        return worker.offer(Unit, request)
    }

    // Closing a service lease prevents further offers, but its accepted final flush survives.
    override fun close() {
        val attempt = synchronized(lock) { closed = true; loading }
        worker.close()
        loadScope.cancel()
        attempt?.done?.countDown()
    }
}
