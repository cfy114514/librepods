package me.kavishdevar.librepods.data

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class AppSettingsPreferencesTest {
    private class Dispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        var running = false
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() {
            var limit = 1000
            running = true
            try { while (queued.isNotEmpty()) { check(limit-- > 0); queued.removeFirst().run() } }
            finally { running = false }
        }
    }
    private class Fixture {
        val dispatcher = Dispatcher()
        val values = AppSetting.snapshot(emptyMap<String, Any>()).toMutableMap()
        var reads = 0; var registers = 0; var removes = 0
        var failRead = false; var failRegister = false; var failWrite = false
        var onRead: (() -> Unit)? = null; var onWrite: (() -> Unit)? = null; var onRegister: (() -> Unit)? = null
        var listener: (() -> Unit)? = null
        val writes = mutableListOf<Map<AppSetting, Any?>>()
        val backend = object : AppSettingsPreferenceBackend {
            override fun read(): Map<AppSetting, Any> {
                check(dispatcher.running); reads++
                if (failRead) error("read")
                val captured = values.toMap(); onRead?.also { onRead = null; it() }
                return captured
            }
            override fun write(changes: Map<AppSetting, Any?>): Boolean {
                check(dispatcher.running); writes.add(changes.toMap())
                changes.forEach { (key, value) -> values[key] = value ?: key.default }
                onWrite?.also { onWrite = null; it() }
                return !failWrite
            }
            override fun register(listener: () -> Unit) {
                check(dispatcher.running); registers++; this@Fixture.listener = listener
                onRegister?.also { onRegister = null; it() }
                if (failRegister) error("register")
            }
            override fun unregister(listener: () -> Unit) { check(dispatcher.running); removes++; this@Fixture.listener = null }
        }
        var opens = 0
        val store = AppSettingsPreferences(dispatcher) { check(dispatcher.running); opens++; backend }
        fun start(): AppSettingsPreferences.Subscription = store.subscribe().also { dispatcher.drain() }
        fun stop() { store.close(); dispatcher.drain(); assertNull(listener) }
    }

    @Test fun constructingAndSubscribingNeverOpenPreferencesUntilTheIoWorkerRuns() {
        val f = Fixture(); val sub = f.store.subscribe()
        assertEquals(0, f.opens); assertNull(f.store.state.value.values)
        f.dispatcher.drain(); assertEquals(1, f.opens); assertEquals(1, f.registers)
        assertEquals(sub.minimumReadVersion, f.store.state.value.readVersion)
        assertEquals(43, f.store.state.value.values!![AppSetting.VOLUME]); f.stop()
    }
    @Test fun initialReadAndPartialRegistrationFailuresCanBothRetry() {
        val f = Fixture(); f.failRegister = true; val sub = f.start()
        assertTrue(f.store.state.value.failed); assertNull(f.store.state.value.values); assertNull(f.listener)
        f.failRegister = false; f.failRead = true; sub.retry(); f.dispatcher.drain()
        assertTrue(f.store.state.value.failed); f.failRead = false; sub.retry(); f.dispatcher.drain()
        assertFalse(f.store.state.value.failed); assertNotNull(f.store.state.value.values); f.stop()
    }
    @Test fun fiveHundredRefreshesWhileReadingRetainOnlyTheLatestSnapshot() {
        val f = Fixture(); f.onRead = {
            f.values[AppSetting.CONNECTION] = true
            repeat(500) { f.listener!!.invoke() }
        }
        f.start(); assertEquals(2, f.reads); assertEquals(true, f.store.state.value.values!![AppSetting.CONNECTION]); f.stop()
    }
    @Test fun fiveHundredSliderChangesWhileCommittingSaveOnlyTheLatestPendingInteger() {
        val f = Fixture(); f.start()
        f.onWrite = { repeat(500) { f.store.set(AppSetting.VOLUME, it % 101) }; f.store.set(AppSetting.VOLUME, 99) }
        f.store.set(AppSetting.VOLUME, 0); f.dispatcher.drain()
        assertEquals(listOf(0, 99), f.writes.map { it[AppSetting.VOLUME] })
        assertEquals(99, f.values[AppSetting.VOLUME]); assertEquals(99, f.store.state.value.values!![AppSetting.VOLUME]); f.stop()
    }
    @Test fun finishingAWriteCannotRemoveALaterRequestWithTheSameValue() {
        val f = Fixture()
        f.start()
        f.onWrite = {
            f.store.set(AppSetting.VOLUME, 80)
            f.store.set(AppSetting.VOLUME, 60)
            assertEquals(60, f.store.state.value.values!![AppSetting.VOLUME])
        }
        f.store.set(AppSetting.VOLUME, 60)
        f.dispatcher.drain()
        assertEquals(listOf(60, 60), f.writes.map { it[AppSetting.VOLUME] })
        assertEquals(60, f.values[AppSetting.VOLUME])
        assertEquals(60, f.store.state.value.values!![AppSetting.VOLUME])
        assertFalse(f.store.state.value.failed)
        f.stop()
    }
    @Test fun failingAWriteCannotMarkALaterRequestWithTheSameValueAsFailed() {
        val f = Fixture()
        f.start()
        f.onWrite = {
            f.store.set(AppSetting.VOLUME, 80)
            f.store.set(AppSetting.VOLUME, 60)
            f.failWrite = true
            f.onWrite = {
                assertTrue(f.store.state.value.failedWrites.isEmpty())
                f.failWrite = false
            }
        }
        f.store.set(AppSetting.VOLUME, 60)
        f.dispatcher.drain()
        assertEquals(listOf(60, 60), f.writes.map { it[AppSetting.VOLUME] })
        assertEquals(60, f.values[AppSetting.VOLUME])
        assertEquals(60, f.store.state.value.values!![AppSetting.VOLUME])
        assertFalse(f.store.state.value.failed)
        f.stop()
    }
    @Test fun consecutiveEquivalentValuesAvoidRepeatedEditsButOtherKeysAndPremiumRemainIndependent() {
        val f = Fixture(); f.start()
        repeat(500) { f.store.set(AppSetting.VOLUME, 80) }
        f.store.set(AppSetting.MATERIAL, false)
        f.store.setPremium(mapOf(AppSetting.PREMIUM_EXPIRY to 1000L, AppSetting.FOSS_UPGRADED to true))
        f.dispatcher.drain(); assertEquals(3, f.writes.size)
        assertEquals(2, f.writes.last().size); assertEquals(false, f.values[AppSetting.MATERIAL]); f.stop()
    }
    @Test fun failedCommitThatUpdatedSdkMemoryStillRetriesTheDurableWrite() {
        val f = Fixture(); val sub = f.start(); f.failWrite = true
        f.store.set(AppSetting.MATERIAL, false); f.dispatcher.drain()
        assertTrue(f.store.state.value.failed); assertEquals(false, f.values[AppSetting.MATERIAL])
        f.failWrite = false; sub.retry(); f.dispatcher.drain()
        assertEquals(2, f.writes.size); assertFalse(f.store.state.value.failed); f.stop()
    }
    @Test fun acceptedEditsPersistAfterTheLastSubscriberCloses() {
        val f = Fixture(); val sub = f.start()
        f.store.set(AppSetting.CAMERA_PACKAGE, "com.example.camera"); sub.close()
        assertEquals(0, f.removes); f.dispatcher.drain()
        assertEquals("com.example.camera", f.values[AppSetting.CAMERA_PACKAGE]); assertEquals(1, f.removes); f.stop()
    }
    @Test fun retiringAnOldSubscriberCannotUnregisterTheReplacement() {
        val f = Fixture(); val old = f.start(); val fresh = f.store.subscribe()
        old.close(); old.close(); f.dispatcher.drain()
        assertEquals(1, f.registers); assertEquals(0, f.removes)
        assertEquals(fresh.minimumReadVersion, f.store.state.value.readVersion)
        fresh.close(); f.dispatcher.drain(); assertEquals(1, f.removes); f.stop()
    }
    @Test fun closingDuringRegistrationCleansTheNewListenerWithoutReadingOrPublishing() {
        val f = Fixture(); val sub = f.store.subscribe(); f.onRegister = sub::close; f.dispatcher.drain()
        assertEquals(0, f.reads); assertNull(f.store.state.value.values); assertEquals(1, f.removes); f.stop()
    }
    @Test fun aWriteReadbackInvalidatedByAnExternalChangeCannotPublishAnOldOtherKey() {
        val f = Fixture(); val sub = f.start()
        f.onRead = { f.values[AppSetting.CONNECTION] = true; f.listener!!.invoke() }
        f.store.set(AppSetting.VOLUME, 70); f.dispatcher.drain()
        assertEquals(true, f.store.state.value.values!![AppSetting.CONNECTION]); assertEquals(70, f.values[AppSetting.VOLUME])
        f.failRead = true; sub.refresh(); f.dispatcher.drain()
        assertTrue(f.store.state.value.failed); assertEquals(true, f.store.state.value.values!![AppSetting.CONNECTION])
        f.failRead = false; sub.retry(); f.dispatcher.drain(); assertFalse(f.store.state.value.failed); f.stop()
    }
    @Test fun malformedPreferenceTypesFailInsteadOfInventingValuesAndVolumeIsBounded() {
        assertEquals(100, AppSetting.snapshot(mapOf("conversational_awareness_volume" to 999))[AppSetting.VOLUME])
        assertEquals(0, AppSetting.snapshot(mapOf("conversational_awareness_volume" to -1))[AppSetting.VOLUME])
        try { AppSetting.snapshot(mapOf("m3e_enabled" to "true")); fail("Wrong type accepted") } catch (_: IllegalArgumentException) {}
        val f = Fixture(); f.start()
        try { f.store.set(AppSetting.VOLUME, 101); fail("Unbounded edit accepted") } catch (_: IllegalArgumentException) {}
        try { f.store.set(AppSetting.CONNECTION, true); fail("Read-only flag writable") } catch (_: IllegalArgumentException) {}
        f.stop(); assertFalse(f.store.set(AppSetting.MATERIAL, true))
    }
}
