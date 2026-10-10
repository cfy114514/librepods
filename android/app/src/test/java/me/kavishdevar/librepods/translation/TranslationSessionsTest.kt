package me.kavishdevar.librepods.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationSessionsTest {
    private val pair = TranslationPair("en-US", "zh-CN")
    @Test fun closingSessionKeepsOwnershipUntilItsResourcesHaveFinished() {
        val sessions = TranslationSessions()
        val lease = sessions.begin(pair, TranslationOutput.AIRPODS)!!
        sessions.listening(lease)
        sessions.stop(lease, TranslationError.ROUTE_CHANGED)
        assertFalse(lease.active)
        assertNull(sessions.begin(pair, TranslationOutput.CAPTIONS))
        sessions.finish(lease)
        assertEquals(TranslationError.ROUTE_CHANGED, sessions.state.value.error)
        assertNotNull(sessions.begin(pair, TranslationOutput.CAPTIONS))
    }
    @Test fun lateModelAndSpeechCallbacksCannotChangeOrStopReplacementSession() {
        val sessions = TranslationSessions()
        val old = sessions.begin(pair, TranslationOutput.AIRPODS)!!
        sessions.stop(old)
        sessions.finish(old)
        val current = sessions.begin(pair, TranslationOutput.CAPTIONS)!!
        sessions.listening(current)
        sessions.addLine(current, TranslationLine(1, "hello"))
        sessions.listening(old)
        sessions.partial(old, "old speech")
        sessions.addLine(old, TranslationLine(2, "old"))
        sessions.translated(old, 1, "wrong result")
        sessions.stop(old, TranslationError.SERVICE)
        sessions.finish(old)
        assertEquals(TranslationPhase.LISTENING, sessions.state.value.phase)
        assertEquals(listOf(TranslationLine(1, "hello")), sessions.state.value.lines)
        assertTrue(current.active)
    }
    @Test fun transcriptMemoryAndPreviewRemainBoundedDuringLongSessions() {
        val sessions = TranslationSessions()
        val lease = sessions.begin(pair, TranslationOutput.CAPTIONS)!!
        repeat(10_000) { sessions.addLine(lease, TranslationLine(it.toLong(), "hello")) }
        assertEquals(12, sessions.state.value.lines.size)
        assertEquals(9988L, sessions.state.value.lines.first().id)
        sessions.partial(lease, "x".repeat(2000))
        assertEquals(1000, sessions.state.value.partial.length)
        sessions.translated(lease, 9999, "translation")
        assertEquals("translation", sessions.state.value.lines.last().translated)
        sessions.stop(lease)
        assertEquals("", sessions.state.value.partial)
    }
    @Test fun slowModelQueueDropsOldPendingWorkAndNeverTruncatesSentencesForTranslation() {
        val queue = TranslationSentenceQueue(2, 10)
        assertNull(queue.offer(" ").accepted)
        assertTrue(queue.offer("x".repeat(11)).skipped)
        assertNull(queue.take())
        val first = queue.offer(" one ").accepted!!
        val second = queue.offer("two").accepted!!
        val third = queue.offer("three")
        assertTrue(third.skipped)
        assertEquals(second, queue.take())
        assertEquals(third.accepted, queue.take())
        assertNull(queue.take())
        assertTrue(first.id < second.id)
        queue.offer("four")
        queue.clear()
        assertNull(queue.take())
    }
}
