package me.kavishdevar.librepods.presentation.screens

import me.kavishdevar.librepods.data.HearingAidSettings
import me.kavishdevar.librepods.data.encodeHearingAidSettings
import me.kavishdevar.librepods.data.parseHearingAidSettingsResponse
import org.junit.Assert.*
import org.junit.Test

class DeviceSettingsEditorTest {
    private class Fixture {
        var now = 0L
        val writes = mutableListOf<Int>()
        val scheduled = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        val editor = DeviceSettingsEditor<Int>(
            schedule = { delay, action ->
                delays += delay
                scheduled += action
                // Keep canceled callbacks to also simulate a callback already queued.
                val cancel: () -> Unit = {}
                cancel
            },
            write = { writes += it },
            nowMillis = { now }
        )
    }

    @Test fun repeatedDeviceNotificationsAndUneditedPageExitNeverWrite() {
        val f = Fixture()
        var displayed = 0
        repeat(100) { value ->
            assertTrue(f.editor.applyDeviceUpdate { displayed = value })
        }
        f.editor.flush()
        assertEquals(99, displayed)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.scheduled.isEmpty())
    }

    @Test fun continuousDraggingOnlySendsLatestValueAfterOriginalDebounce() {
        val f = Fixture()
        repeat(100) { f.editor.userEdited(it) }
        assertTrue(f.writes.isEmpty())
        assertTrue(f.delays.all { it == 100L })
        f.scheduled.forEach { it() }
        assertEquals(listOf(99), f.writes)
    }

    @Test fun leavingPageFlushesFinalEditExactlyOnce() {
        val f = Fixture()
        f.editor.userEdited(1)
        f.editor.userEdited(2)
        f.editor.flush()
        f.scheduled.forEach { it() }
        f.editor.flush()
        assertEquals(listOf(2), f.writes)
    }

    @Test fun delayedOrNormalizedEchoCannotReplaceNewerUserDraft() {
        val f = Fixture()
        var displayed = 2
        f.editor.userEdited(1)
        f.now = 2000
        f.editor.userEdited(2)
        f.now = 3000
        assertFalse(f.editor.applyDeviceUpdate { displayed = 1 })
        assertFalse(f.editor.applyDeviceUpdate { displayed = 3 })
        assertEquals(2, displayed)
        f.editor.flush()
        assertEquals(listOf(2), f.writes)
    }

    @Test fun notificationsDoNotExtendSettlingWindowAfterFailedWrite() {
        val f = Fixture()
        f.editor.userEdited(2)
        f.editor.flush()
        f.now = 2499
        assertFalse(f.editor.applyDeviceUpdate {})
        f.now = 2500
        assertTrue(f.editor.applyDeviceUpdate {})
        assertEquals(listOf(2), f.writes)
    }

    @Test fun normalizedGainAndQuantizedAsymmetricEqEchoCannotChangePendingPacket() {
        val callbacks = mutableListOf<() -> Unit>()
        val packets = mutableListOf<ByteArray>()
        val source = ByteArray(104)
        val original = HearingAidSettings(
            FloatArray(8) { 0.25f }, FloatArray(8) { -0.5f },
            0.4f, 0.8f, 0f, 0f, false, false, 0f, 0f, 0.4f, 0.4f, 0f
        )
        val latest = original.copy(leftEQ = FloatArray(8) { 0.33333f })
        var displayed = latest
        val editor = DeviceSettingsEditor<HearingAidSettings>(
            schedule = { _, action ->
                callbacks += action
                val cancel: () -> Unit = {}
                cancel
            },
            write = { packets += encodeHearingAidSettings(source, it)!! },
            nowMillis = { 0L }
        )
        editor.userEdited(original)
        editor.userEdited(latest)
        val echo = parseHearingAidSettingsResponse(
            encodeHearingAidSettings(source, original.copy(leftEQ = FloatArray(8) { 0.33f }))!!
        )!!
        // The protocol derives average gain from the two sides, changing 0.4 to 0.6.
        assertEquals(0.6f, echo.netAmplification, 0.00001f)
        assertFalse(editor.applyDeviceUpdate { displayed = echo })
        callbacks.forEach { it() }
        assertEquals(latest, displayed)
        assertEquals(1, packets.size)
        val sent = parseHearingAidSettingsResponse(packets.single())!!
        assertArrayEquals(latest.leftEQ, sent.leftEQ, 0f)
        assertArrayEquals(latest.rightEQ, sent.rightEQ, 0f)
        assertEquals(latest.leftAmplification, sent.leftAmplification, 0f)
        assertEquals(latest.rightAmplification, sent.rightAmplification, 0f)
    }
}
