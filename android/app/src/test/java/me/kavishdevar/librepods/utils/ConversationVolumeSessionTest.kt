package me.kavishdevar.librepods.utils

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class ConversationVolumeSessionTest {
    @Test fun equalRestoreRequestsDoNotReactivateThePreviousRequestsGuard() {
        val f = Fixture()
        val backend = f.Backend()
        val guards = mutableListOf<() -> Boolean>()
        val lease = f.session.claim(backend, { true }, onEnd = { _, _, current ->
            guards.add(current)
            false // Keep restoration outstanding while the next phrase arrives.
        })
        lease.start(true, 100, false)
        f.drain()
        lease.stop(true)
        f.drain()
        assertTrue(guards.single()())

        lease.start(true, 100, false)
        f.drain()
        assertFalse(guards.single()())
        lease.stop(true)
        f.drain()
        assertEquals(2, guards.size)
        assertFalse(guards.first()())
        assertTrue(guards.last()())
        f.finish()
        assertTrue(guards.none { it() })
    }

    @Test fun pauseAttemptThatMutatesPlaybackThenThrowsStillRestoresOnConversationEnd() {
        val f = Fixture(); val b = f.Backend(); var playing = true; var ups = 0
        val lease = f.session.claim(b, { true }, onBegin = { _, _ ->
            captureMediaPause(f.errors::add) { entered ->
                dispatchMediaKeyPair { down ->
                    if (down) { entered(); playing = false; error("Paused then failed") } else ups++
                }
            }
        }, onEnd = { paused, resume, _ -> if (paused && resume) playing = true; true })
        lease.start(true, 43, true); f.drain(); assertFalse(playing); assertEquals(4, b.value)
        assertEquals(1, ups); assertEquals(1, f.errors.size)
        lease.stop(true); f.drain(); assertTrue(playing); assertEquals(10, b.value); f.finish()
    }
    @Test fun settingsAreReadOnWorkerOncePerAcceptedPhraseAndChangeOnNextPhrase() {
        val f = Fixture(); val b = f.Backend(); var reads = 0
        var settings = ConversationVolumeSettings(true, 43, false)
        val lease = f.session.claim(b, { true }, readSettings = {
            check(f.dispatcher.running); reads++; settings
        })
        assertEquals(0, reads); lease.start(); assertEquals(0, reads); f.drain()
        assertEquals(4, b.value); settings = ConversationVolumeSettings(false, 60, false)
        repeat(500) { lease.start() }; f.drain(); assertEquals(1, reads); assertEquals(4, b.value)
        lease.stop(false); f.drain(); lease.start(); f.drain()
        assertEquals(2, reads); assertEquals(9, b.value); f.finish()
    }
    @Test fun replacementWhileSettingsReadIsHeldDoesNotTouchOldAudioOrReadIntermediateSettings() {
        val f = Fixture(); val old = f.Backend(); val replacements = mutableListOf<Fixture.Backend>()
        var reads = 0
        val lease = f.session.claim(old, { true }, readSettings = {
            repeat(500) { i ->
                val b = f.Backend(name = "settings$i"); replacements.add(b)
                f.session.claim(b, { true }, readSettings = { reads++; ConversationVolumeSettings() }).start()
            }
            ConversationVolumeSettings()
        })
        lease.start(); f.drain()
        assertEquals(0, old.reads); assertTrue(old.values.isEmpty()); assertEquals(1, reads)
        assertTrue(replacements.dropLast(1).all { it.reads == 0 && it.values.isEmpty() })
        assertEquals(4, replacements.last().value); f.finish()
    }
    @Test fun stopOrReleaseDuringSettingsReadCannotPauseOrLowerMusic() {
        for (release in listOf(false, true)) {
            val f = Fixture(); val b = f.Backend(); var begins = 0
            lateinit var lease: ConversationVolumeSession.Lease
            lease = f.session.claim(b, { true }, onBegin = { _, _ -> begins++; true }, readSettings = {
                if (release) lease.close() else lease.stop(true)
                ConversationVolumeSettings(pause = true)
            })
            lease.start(); f.drain(); assertEquals(0, b.reads); assertEquals(0, begins)
            assertTrue(b.values.isEmpty()); f.finish()
        }
    }
    @Test fun malformedSettingsFailBeforeAudioAndSameStartCanRetry() {
        val f = Fixture(); val b = f.Backend(); var values: Map<String, *> = mapOf("conversational_awareness_volume" to "43")
        val lease = f.session.claim(b, { true }, readSettings = { ConversationVolumeSettings.from(values) })
        lease.start(); f.drain(); assertEquals(1, f.errors.size); assertEquals(0, b.reads); assertTrue(b.values.isEmpty())
        values = mapOf("conversational_awareness_volume" to 50); lease.start(); f.drain()
        assertEquals(5, b.value); f.finish()
    }
    @Test fun staleSettingsFailureCannotPoisonTheReplacementOrDuplicateItsRead() {
        val f = Fixture(); val old = f.Backend(); val fresh = f.Backend(); var freshReads = 0
        lateinit var replacement: ConversationVolumeSession.Lease
        f.session.claim(old, { true }, readSettings = {
            replacement = f.session.claim(fresh, { true }, readSettings = { freshReads++; ConversationVolumeSettings() })
            replacement.start(); error("retired preference read")
        }).start()
        f.drain(); replacement.start(); f.drain()
        assertEquals(1, f.errors.size); assertEquals(0, old.reads); assertEquals(1, freshReads); assertEquals(4, fresh.value); f.finish()
    }
    @Test fun changedSettingsDoNotForgetCapturedPauseOnRestore() {
        val f = Fixture(); val b = f.Backend(); var settings = ConversationVolumeSettings(pause = true)
        val ends = mutableListOf<Pair<Boolean, Boolean>>()
        val lease = f.session.claim(b, { true }, onBegin = { pause, _ -> pause },
            onEnd = { paused, resume, _ -> ends.add(paused to resume); true }, readSettings = { settings })
        lease.start(); f.drain(); settings = ConversationVolumeSettings(false, 100, false)
        lease.stop(true); f.drain(); assertEquals(listOf(true to true), ends)
        lease.start(); f.drain(); lease.stop(true); f.drain()
        assertEquals(listOf(true to true, false to true), ends); f.finish()
    }
    @Test fun settingsSnapshotPreservesDefaultsClampsAndRejectsWrongTypes() {
        assertEquals(ConversationVolumeSettings(), ConversationVolumeSettings.from(emptyMap<String, Any>()))
        assertEquals(ConversationVolumeSettings(false, 100, true), ConversationVolumeSettings.from(mapOf(
            "relative_conversational_awareness_volume" to false, "conversational_awareness_volume" to 123,
            "conversational_awareness_pause_music" to true)))
        assertEquals(0, ConversationVolumeSettings.from(mapOf("conversational_awareness_volume" to -5)).percent)
        for (key in listOf("relative_conversational_awareness_volume", "conversational_awareness_volume", "conversational_awareness_pause_music")) {
            try { ConversationVolumeSettings.from(mapOf(key to "wrong")); fail("Incorrect type accepted for $key") }
            catch (_: ClassCastException) { }
        }
    }
    private class Dispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        var running = false
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun drain() { var limit = 1000; running = true
            try { while (queued.isNotEmpty()) { check(limit-- > 0); queued.removeFirst().run() } }
            finally { running = false }
        }
    }
    private class Fixture {
        val dispatcher = Dispatcher(); val ticks = ArrayDeque<Pair<Runnable, Long>>()
        val errors = mutableListOf<Exception>(); val events = mutableListOf<String>()
        val session = ConversationVolumeSession(dispatcher, errors::add,
            schedule = { action, delay -> ticks.addLast(action to delay) },
            unschedule = { action -> ticks.removeAll { it.first === action } })
        inner class Backend(var value: Int = 10, val name: String = "original") : ConversationVolumeBackend {
            var reads = 0; var maximumReads = 0; var onRead: (() -> Unit)? = null; var onSet: (() -> Unit)? = null
            var failSet = false; var failRead = false; val values = mutableListOf<Int>()
            override fun volume(): Int { check(dispatcher.running); reads++; events.add("$name read"); val old = value
                onRead?.also { onRead = null; it() }; if (failRead) error("read"); return old }
            override fun maximum(): Int { check(dispatcher.running); maximumReads++; return 15 }
            override fun set(value: Int) { check(dispatcher.running); this.value = value; values.add(value); events.add("$name set $value")
                onSet?.also { onSet = null; it() }; if (failSet) error("set after mutation") }
        }
        fun drain() {
            dispatcher.drain(); var limit = 1000
            while (ticks.isNotEmpty()) { check(limit-- > 0); session.tick(ticks.removeFirst().first); dispatcher.drain() }
        }
        fun step() { dispatcher.drain(); session.tick(ticks.removeFirst().first); dispatcher.drain() }
        fun finish() { session.close(); dispatcher.drain(); assertTrue(ticks.isEmpty()) }
    }

    @Test fun volumeSdkCallsAreDeferredAndOriginal50MillisecondIntegerRampIsRetained() {
        val f = Fixture(); val b = f.Backend(); val lease = f.session.claim(b, { true })
        lease.start(true, 43, false); assertEquals(0, b.reads); f.dispatcher.drain()
        assertEquals(1, b.reads); assertEquals(1, b.maximumReads); assertEquals(0L, f.ticks.first().second)
        f.step(); assertEquals(50L, f.ticks.first().second); f.drain()
        assertEquals(listOf(9, 8, 7, 6, 5, 4), b.values)
        lease.stop(false); f.drain(); assertEquals(10, b.value); f.finish()
    }
    @Test fun absoluteLimitDoesNotRaiseAlreadyQuietMusicAndSameVolumeDoesNotWakeTheRamp() {
        val f = Fixture(); val b = f.Backend(3); val lease = f.session.claim(b, { true })
        lease.start(false, 43, false); f.drain(); assertTrue(b.values.isEmpty()); assertTrue(f.ticks.isEmpty())
        lease.stop(false); f.drain(); assertTrue(b.values.isEmpty()); f.finish()
    }
    @Test fun releaseRestoresOnIoAndLateDispatchedTickCannotAttenuateAgain() {
        val f = Fixture(); val b = f.Backend(); val lease = f.session.claim(b, { true })
        lease.start(true, 43, false); f.step(); val old = f.ticks.first().first
        lease.close(); assertEquals(9, b.value); f.dispatcher.drain(); assertEquals(10, b.value)
        f.session.tick(old); f.dispatcher.drain(); assertEquals(listOf(9, 10), b.values); f.finish()
    }
    @Test fun oldRestorationRunsBeforeReplacementReadsItsBase() {
        val f = Fixture(); val b = f.Backend(); val old = f.session.claim(b, { true })
        old.start(true, 43, false); f.step()
        val fresh = f.session.claim(b, { true }); fresh.start(true, 43, false); old.close(); f.drain()
        assertEquals(4, b.value); assertEquals(listOf("original set 10", "original read"), f.events.drop(2).take(2))
        fresh.stop(false); f.drain(); assertEquals(10, b.value); f.finish()
    }
    @Test fun fiveHundredReplacementsWhileReadingUseOnlyTheLastBackend() {
        val f = Fixture(); val old = f.Backend(); val replacements = mutableListOf<Fixture.Backend>()
        old.onRead = { repeat(500) { i -> val b = f.Backend(name = "new$i"); replacements.add(b)
            f.session.claim(b, { true }).start(true, 43, false) } }
        f.session.claim(old, { true }).start(true, 43, false); f.drain()
        assertTrue(old.values.isEmpty()); assertTrue(replacements.dropLast(1).all { it.reads == 0 && it.values.isEmpty() })
        assertEquals(1, replacements.last().reads); assertEquals(4, replacements.last().value); f.finish()
    }
    @Test fun retiringWhileSetterIsActiveRestoresAfterThatSetterReturns() {
        val f = Fixture(); val b = f.Backend(); val lease = f.session.claim(b, { true })
        b.onSet = lease::close; lease.start(true, 43, false); f.drain()
        assertEquals(listOf(9, 10), b.values); assertEquals(10, b.value); f.finish()
    }
    @Test fun rapidEndAndNextStartKeepTheOriginalBaseUntilRestorationFinishes() {
        val f = Fixture(); val b = f.Backend(); val lease = f.session.claim(b, { true })
        lease.start(true, 43, false); f.drain(); lease.stop(false); f.step(); assertEquals(5, b.value)
        lease.start(true, 43, false); f.drain(); assertEquals(4, b.value)
        lease.stop(false); f.drain(); assertEquals(10, b.value); f.finish()
    }
    @Test fun duplicateStartsDoNotReadAgainAndOldTickCannotReplaceTheNewPendingTick() {
        val f = Fixture(); val b = f.Backend(); val lease = f.session.claim(b, { true })
        lease.start(true, 43, false); f.dispatcher.drain(); repeat(500) { lease.start(false, 90, true) }; f.dispatcher.drain()
        assertEquals(1, b.reads); val old = f.ticks.first().first
        lease.stop(false); f.dispatcher.drain(); assertTrue(f.ticks.isEmpty())
        lease.start(true, 50, false); f.dispatcher.drain(); val fresh = f.ticks.removeFirst().first
        f.session.tick(fresh); f.session.tick(old); f.dispatcher.drain(); assertEquals(9, b.value)
        f.drain(); assertEquals(5, b.value); f.finish()
    }
    @Test fun pauseCapturedAtStartIsRestoredDespiteChangedPreferenceAndResumeFalseIsHonored() {
        val f = Fixture(); val b = f.Backend(); val resumed = mutableListOf<Pair<Boolean, Boolean>>(); var pauses = 0
        val lease = f.session.claim(b, { true }, onBegin = { pause, current -> assertTrue(current()); if (pause) pauses++; pause },
            onEnd = { paused, resume, current -> assertTrue(current()); resumed.add(paused to resume); true })
        lease.start(true, 43, true); f.drain(); lease.start(true, 43, false); lease.stop(true); f.drain()
        assertEquals(1, pauses); assertEquals(listOf(true to true), resumed)
        lease.start(true, 43, true); f.drain(); lease.stop(false); f.drain()
        assertEquals(listOf(true to true, true to false), resumed); f.finish()
    }
    @Test fun readFailureDoesNotInventABaseAndMutatedSetterFailureStillRestoresIt() {
        val f = Fixture(); val b = f.Backend(); val lease = f.session.claim(b, { true }); b.failRead = true
        lease.start(true, 43, false); f.drain(); assertEquals(1, f.errors.size); assertTrue(b.values.isEmpty())
        b.failRead = false; b.failSet = true; lease.start(true, 43, false); f.drain(); assertEquals(9, b.value)
        b.failSet = false; lease.close(); f.dispatcher.drain(); assertEquals(10, b.value); f.finish()
    }
    @Test fun shutdownDuringAnActiveTickStillRestoresBeforeCancellingTheWorker() {
        val f = Fixture(); val b = f.Backend(); b.onSet = f.session::close
        f.session.claim(b, { true }).start(true, 43, false); f.drain()
        assertEquals(listOf(9, 10), b.values); assertTrue(f.ticks.isEmpty())
    }
    @Test fun oldReadFailureCannotMarkTheReplacementAsFailedOrCauseAnotherBaseRead() {
        val f = Fixture(); val old = f.Backend(); val fresh = f.Backend(name = "replacement")
        lateinit var lease: ConversationVolumeSession.Lease
        old.onRead = { lease = f.session.claim(fresh, { true }); lease.start(true, 43, false); old.failRead = true }
        fresh.onRead = { repeat(500) { lease.start(true, 43, false) } }
        f.session.claim(old, { true }).start(true, 43, false); f.drain()
        assertEquals(1, f.errors.size); assertEquals(1, fresh.reads); assertEquals(0, old.maximumReads)
        assertEquals(4, fresh.value); f.finish()
    }
    @Test fun failedRetirementKeepsTheBaseAndRetriesBeforeTheNewOwnerCanLowerMusic() {
        val f = Fixture(); val old = f.Backend(); val fresh = f.Backend(name = "replacement")
        f.session.claim(old, { true }).start(true, 43, false); f.drain(); old.failSet = true
        val lease = f.session.claim(fresh, { true }); lease.start(true, 43, false); f.dispatcher.drain()
        assertEquals(1, f.errors.size); assertEquals(0, fresh.reads)
        old.failSet = false; lease.start(true, 43, false); f.drain()
        assertEquals(10, old.value); assertEquals(4, fresh.value); f.finish()
    }
    @Test fun cancelledEndCarriesCapturedPauseIntoTheNextPhraseIncludingAnUnchangedVolume() {
        for (percent in listOf(43, 100)) {
            val f = Fixture(); val b = f.Backend(); var began = 0; var interrupted = false
            val deliveredEnds = mutableListOf<Boolean>(); lateinit var lease: ConversationVolumeSession.Lease
            lease = f.session.claim(b, { true }, onBegin = { _, _ -> ++began == 1 },
                onEnd = { paused, _, current ->
                    if (!interrupted) { interrupted = true; lease.start(true, percent, true); assertFalse(current()); false }
                    else { deliveredEnds.add(paused); true }
                })
            lease.start(true, percent, true); f.drain(); lease.stop(true); f.drain()
            lease.stop(true); f.drain()
            assertEquals(listOf(true), deliveredEnds); assertEquals(10, b.value); f.finish()
        }
    }
}
