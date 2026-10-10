package me.kavishdevar.librepods.data

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.utils.KeyedWorkSession

internal enum class AppSetting(val nameInPreferences: String, val default: Any) {
    PHONE_BATTERY("show_phone_battery_in_widget", false),
    PAUSE_CONVERSATION("conversational_awareness_pause_music", false),
    RELATIVE_VOLUME("relative_conversational_awareness_volume", true),
    DISCONNECT_UNWORN("disconnect_when_not_wearing", false),
    TAKEOVER_DISCONNECTED("takeover_when_disconnected", false),
    TAKEOVER_IDLE("takeover_when_idle", false),
    TAKEOVER_MUSIC("takeover_when_music", false),
    TAKEOVER_CALL("takeover_when_call", false),
    TAKEOVER_RINGING("takeover_when_ringing_call", false),
    TAKEOVER_MEDIA("takeover_when_media_start", false),
    ALTERNATE_HEAD("use_alternate_head_tracking_packets", true),
    VOLUME("conversational_awareness_volume", 43),
    CAMERA_PACKAGE("custom_camera_package", ""),
    CONNECTION("connection_successful", false),
    BOTTOM_POPUP("show_bottom_sheet_popup", true),
    ISLAND_POPUP("show_island_popup", true),
    MATERIAL("m3e_enabled", true),
    PREMIUM_EXPIRY("premium_expiry_time", 0L),
    FOSS_UPGRADED("foss_upgraded", false);

    companion object {
        fun snapshot(raw: Map<String, *>): Map<AppSetting, Any> = entries.associateWith { key ->
            val value = raw[key.nameInPreferences] ?: key.default
            require(key.default.javaClass.isInstance(value)) { "Wrong preference type: ${key.nameInPreferences}" }
            if (key == VOLUME) (value as Int).coerceIn(0, 100) else value
        }
    }
}

internal interface AppSettingsPreferenceBackend {
    fun read(): Map<AppSetting, Any>
    fun write(changes: Map<AppSetting, Any?>): Boolean
    fun register(listener: () -> Unit)
    fun unregister(listener: () -> Unit)
}

internal data class AppSettingsPreferenceState(
    val values: Map<AppSetting, Any>? = null, val readVersion: Long = 0,
    val readFailed: Boolean = false, val failedWrites: Set<Int> = emptySet()
) { val failed: Boolean get() = readFailed || failedWrites.isNotEmpty() }

/** Shared bounded IO work; accepted writes survive the last UI subscriber leaving. */
internal class AppSettingsPreferences(
    dispatcher: CoroutineDispatcher, backend: () -> AppSettingsPreferenceBackend
) : Closeable {
    private sealed interface Work {
        data class Read(val version: Long) : Work
        class Write(val bucket: Int, val changes: Map<AppSetting, Any?>) : Work
        data object Maintain : Work
    }
    private val lock = Any()
    private val backend by lazy(backend)
    private val observers = mutableSetOf<Any>()
    private val desired = mutableMapOf<Int, Work.Write>()
    private var readVersion = 0L
    private var closed = false
    // Registration and SDK operations are only accessed by this serial worker.
    private var registered = false
    private val mutableState = MutableStateFlow(AppSettingsPreferenceState())
    val state = mutableState.asStateFlow()
    private val listener: () -> Unit = { refresh() }
    private val worker = KeyedWorkSession<Int, Work>(WORK_KEYS, dispatcher) { work ->
        when (work) {
            is Work.Read -> read(work.version)
            is Work.Write -> write(work)
            Work.Maintain -> maintain()
        }
    }

    internal class Subscription internal constructor(
        private val parent: AppSettingsPreferences, private val token: Any, val minimumReadVersion: Long
    ) : Closeable {
        private val closed = AtomicBoolean()
        fun refresh() { if (!closed.get()) parent.refresh() }
        fun retry() { if (!closed.get()) parent.retry() }
        override fun close() { if (closed.compareAndSet(false, true)) parent.unsubscribe(token) }
    }

    fun subscribe(): Subscription {
        val token = Any()
        val version = synchronized(lock) { check(!closed); observers.add(token); ++readVersion }
        offerRead(version)
        return Subscription(this, token, version)
    }

    fun set(key: AppSetting, value: Any?): Boolean {
        require(key in USER_KEYS)
        validate(key, value)
        return offerWrite(key.ordinal, mapOf(key to value))
    }

    /** Premium migration/cleanup keeps its original atomic two-key edit. */
    fun setPremium(changes: Map<AppSetting, Any?>): Boolean {
        require(changes.isNotEmpty() && changes.keys.all { it in PREMIUM_KEYS })
        changes.forEach(::validate)
        return offerWrite(PREMIUM_BUCKET, changes)
    }

    private fun offerWrite(bucket: Int, changes: Map<AppSetting, Any?>, force: Boolean = false, expected: Work.Write? = null): Boolean {
        val request = synchronized(lock) {
            if (closed) return false
            if (expected != null && desired[bucket] !== expected) return false
            val current = desired[bucket]
            if (!force && current?.changes == changes && bucket !in mutableState.value.failedWrites) return true
            if (!force && current == null && bucket !in mutableState.value.failedWrites &&
                mutableState.value.values?.let { values -> changes.all { (key, value) -> values[key] == (value ?: key.default) } } == true) return true
            Work.Write(bucket, changes.toMap()).also {
                desired[bucket] = it
                mutableState.value = mutableState.value.copy(values = mutableState.value.values?.let(::overlay))
            }
        }
        return worker.offerIf(bucket, request) { synchronized(lock) { !closed && desired[bucket] === request } }
    }

    private fun refresh() {
        val version = synchronized(lock) { if (closed || observers.isEmpty()) return; ++readVersion }
        offerRead(version)
    }
    private fun offerRead(version: Long) {
        worker.offerIf(READ_BUCKET, Work.Read(version)) {
            synchronized(lock) { !closed && observers.isNotEmpty() && readVersion == version }
        }
    }
    private fun retry() {
        val writes = synchronized(lock) { if (closed) return; desired.values.toList() }
        writes.forEach { offerWrite(it.bucket, it.changes, force = true, expected = it) }
        refresh()
    }
    private fun unsubscribe(token: Any) {
        val empty = synchronized(lock) { observers.remove(token); observers.isEmpty() }
        if (empty) worker.offer(MAINTAIN_BUCKET, Work.Maintain)
    }

    private fun ensureRegistration(): Boolean {
        if (synchronized(lock) { closed || observers.isEmpty() }) return false
        if (!registered) {
            try { backend.register(listener); registered = true }
            catch (error: Exception) { runCatching { backend.unregister(listener) }; throw error }
        }
        if (synchronized(lock) { closed || observers.isEmpty() }) { maintain(); return false }
        return true
    }
    private fun read(version: Long) {
        try {
            if (!ensureRegistration()) return
            val values = backend.read()
            synchronized(lock) {
                if (!closed && observers.isNotEmpty() && version == readVersion)
                    mutableState.value = mutableState.value.copy(values = overlay(values), readVersion = version, readFailed = false)
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && observers.isNotEmpty() && version == readVersion)
                    mutableState.value = mutableState.value.copy(readFailed = true)
            }
        }
    }
    private fun write(request: Work.Write) {
        if (synchronized(lock) { closed || desired[request.bucket] !== request }) return
        try {
            check(backend.write(request.changes)) { "Preference commit failed" }
            val version = synchronized(lock) { readVersion }
            val values = backend.read()
            synchronized(lock) {
                if (closed) return
                if (desired[request.bucket] === request) desired.remove(request.bucket)
                mutableState.value = mutableState.value.copy(
                    values = if (version == readVersion) overlay(values) else mutableState.value.values?.let(::overlay),
                    failedWrites = if (desired[request.bucket] == null) mutableState.value.failedWrites - request.bucket else mutableState.value.failedWrites)
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && desired[request.bucket] === request)
                    mutableState.value = mutableState.value.copy(failedWrites = mutableState.value.failedWrites + request.bucket)
            }
        }
    }
    private fun overlay(values: Map<AppSetting, Any>): Map<AppSetting, Any> = values.toMutableMap().apply {
        desired.values.forEach { request -> request.changes.forEach { (key, value) -> this[key] = value ?: key.default } }
    }.toMap()
    private fun maintain() {
        if (synchronized(lock) { closed || observers.isEmpty() } && registered) {
            backend.unregister(listener)
            registered = false
        }
        if (synchronized(lock) { closed }) worker.close()
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; observers.clear(); desired.clear() }
        worker.offer(MAINTAIN_BUCKET, Work.Maintain)
    }

    companion object {
        private val PREMIUM_KEYS = setOf(AppSetting.PREMIUM_EXPIRY, AppSetting.FOSS_UPGRADED)
        private val USER_KEYS = AppSetting.entries.toSet() - PREMIUM_KEYS - AppSetting.CONNECTION
        private val PREMIUM_BUCKET = AppSetting.entries.size
        private val READ_BUCKET = PREMIUM_BUCKET + 1
        private val MAINTAIN_BUCKET = READ_BUCKET + 1
        private val WORK_KEYS = USER_KEYS.map { it.ordinal }.toSet() + setOf(PREMIUM_BUCKET, READ_BUCKET, MAINTAIN_BUCKET)
        private fun validate(key: AppSetting, value: Any?) {
            require(value == null || key.default.javaClass.isInstance(value))
            if (key == AppSetting.VOLUME && value != null) require(value as Int in 0..100)
        }
    }
}
