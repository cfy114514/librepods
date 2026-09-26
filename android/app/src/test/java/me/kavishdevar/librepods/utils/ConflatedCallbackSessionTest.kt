package me.kavishdevar.librepods.utils

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedCallbackSessionTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        val queuedTasks: Int get() = pending.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }

        fun drain() {
            var limit = 100
            while (pending.isNotEmpty()) {
                check(limit-- > 0) { "Callback queue did not quiesce" }
                pending.removeFirst().run()
            }
        }
    }

    @Test
    fun sensorBurstProducesOneQueuedTaskAndKeepsTheLatestDirection() {
        val dispatcher = QueuedDispatcher()
        val heard = mutableListOf<Int>()
        val session = ConflatedCallbackSession<Int>(dispatcher, heard::add)
        repeat(10_000) { assertTrue(session.offer(it)) }
        assertEquals(1, dispatcher.queuedTasks)
        dispatcher.drain()
        assertEquals(listOf(9_999), heard)
        session.close()
        dispatcher.drain()
    }

    @Test
    fun stoppingSessionSuppressesAlreadyDispatchedSoundAndDetectionTasks() {
        val dispatcher = QueuedDispatcher()
        val heard = mutableListOf<Int>()
        var detectionRan = false
        val session = ConflatedCallbackSession<Int>(dispatcher, heard::add)
        session.offer(1)
        session.scope.launch { detectionRan = true }
        session.close()
        dispatcher.drain()
        assertTrue(heard.isEmpty())
        assertFalse(detectionRan)
        assertFalse(session.offer(2))
    }

    @Test
    fun restartingDoesNotPlayOldFeedbackOrCancelNewSession() {
        val dispatcher = QueuedDispatcher()
        val heard = mutableListOf<String>()
        val oldSession = ConflatedCallbackSession<String>(dispatcher, heard::add)
        oldSession.offer("old")
        oldSession.close()
        val newSession = ConflatedCallbackSession<String>(dispatcher, heard::add)
        newSession.offer("new")
        oldSession.close()
        dispatcher.drain()
        assertEquals(listOf("new"), heard)
        newSession.close()
        dispatcher.drain()
    }
}
