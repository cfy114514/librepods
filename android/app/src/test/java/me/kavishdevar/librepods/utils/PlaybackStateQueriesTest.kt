package me.kavishdevar.librepods.utils

import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class PlaybackStateQueriesTest {
    private class Dispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { var limit = 1000; while (tasks.isNotEmpty()) { check(limit-- > 0); tasks.removeFirst().run() } }
    }
    private class Fixture : Closeable {
        val io = Dispatcher(); val main = Dispatcher(); val errors = mutableListOf<Exception>()
        val queries = PlaybackStateQueries(io, main, errors::add)
        val values = mutableListOf<Boolean>()
        fun offer(lease: PlaybackStateQueries.Lease, epoch: Long, value: Boolean,
                  kind: PlaybackStateQueries.Kind = PlaybackStateQueries.Kind.CALLBACK) =
            lease.offer(kind, epoch, { value }) { next, current -> if (current()) values.add(next) }
        override fun close() { queries.close(); io.drain(); main.drain() }
    }
    @Test fun queryRunsBeforeMainDeliveryAndNeverPublishesDuringIoExecution() = Fixture().use { f ->
        val lease = f.queries.claim { true }; val epoch = lease.advance()!!
        var read = false
        lease.offer(PlaybackStateQueries.Kind.CALLBACK, epoch, { read = true; true }) { value, valid ->
            assertTrue(valid()); f.values.add(value)
        }
        assertFalse(read); f.io.drain(); assertTrue(read); assertTrue(f.values.isEmpty())
        f.main.drain(); assertEquals(listOf(true), f.values)
    }
    @Test fun newRawEventInvalidatesActiveQueryEvenBeforeThrottledSubmission() = Fixture().use { f ->
        val lease = f.queries.claim { true }; val epoch = lease.advance()!!
        lease.offer(PlaybackStateQueries.Kind.CALLBACK, epoch, { lease.advance(); true }) { _, _ -> fail("Stale raw event published") }
        f.io.drain(); f.main.drain(); assertTrue(f.values.isEmpty())
        assertFalse(f.offer(lease, epoch, true))
        val current = lease.advance()!!; f.offer(lease, current, false); f.io.drain(); f.main.drain()
        assertEquals(listOf(false), f.values)
    }
    @Test fun floodDuringActiveReadRetainsOnlyLatestRead() = Fixture().use { f ->
        val lease = f.queries.claim { true }; var reads = 0
        lease.offer(PlaybackStateQueries.Kind.CALLBACK, lease.advance()!!, {
            reads++
            repeat(500) { lease.offer(PlaybackStateQueries.Kind.CALLBACK, lease.advance()!!,
                { reads++; false }) { value, current -> if (current()) f.values.add(value) } }
            true
        }) { _, _ -> fail("Active old value published") }
        f.io.drain(); f.main.drain()
        assertEquals(2, reads); assertEquals(listOf(false), f.values)
    }
    @Test fun delayedMainDeliveryRetainsLatestReplyForEachFixedKind() = Fixture().use { f ->
        val lease = f.queries.claim { true }; val epoch = lease.advance()!!
        repeat(500) { index ->
            f.offer(lease, epoch, index % 2 == 0); f.io.drain()
            f.offer(lease, epoch, true, PlaybackStateQueries.Kind.REFRESH); f.io.drain()
        }
        assertEquals(1, f.main.tasks.size); assertTrue(f.values.isEmpty())
        f.main.drain(); assertEquals(listOf(false, true), f.values)
    }
    @Test fun newEventInvalidatesBothCallbackAndRefreshReplies() = Fixture().use { f ->
        val lease = f.queries.claim { true }; val epoch = lease.advance()!!
        f.offer(lease, epoch, true); f.offer(lease, epoch, true, PlaybackStateQueries.Kind.REFRESH); f.io.drain()
        lease.advance(); f.main.drain(); assertTrue(f.values.isEmpty())
        assertFalse(lease.isCurrent(epoch))
    }
    @Test fun replacingManyOwnersDuringReadDoesNotCreateNewWorkersOrPublishOldValue() = Fixture().use { f ->
        val old = f.queries.claim { true }; lateinit var successor: PlaybackStateQueries.Lease; var reads = 0
        old.offer(PlaybackStateQueries.Kind.CALLBACK, old.advance()!!, {
            reads++
            repeat(500) {
                successor = f.queries.claim { true }
                successor.offer(PlaybackStateQueries.Kind.CALLBACK, successor.advance()!!, { reads++; false }) {
                    value, current -> if (current()) f.values.add(value)
                }
            }
            old.close(); true
        }) { _, _ -> fail("Retired owner published") }
        f.io.drain(); f.main.drain()
        assertEquals(2, reads); assertEquals(listOf(false), f.values)
        assertTrue(f.offer(successor, successor.advance()!!, true)); f.io.drain(); f.main.drain()
        assertEquals(listOf(false, true), f.values)
    }
    @Test fun closeAfterReadBeforeMainDeliveryRejectsQueuedReply() = Fixture().use { f ->
        val lease = f.queries.claim { true }; val epoch = lease.advance()!!
        f.offer(lease, epoch, true); f.io.drain(); lease.close(); f.main.drain()
        assertTrue(f.values.isEmpty()); assertNull(lease.advance()); assertFalse(f.offer(lease, epoch, true))
    }
    @Test fun externalSessionRevocationRejectsPendingReadWithoutInvokingSdk() = Fixture().use { f ->
        var active = true; val lease = f.queries.claim { active }; var reads = 0
        lease.offer(PlaybackStateQueries.Kind.CALLBACK, lease.advance()!!, { reads++; true }) { _, _ -> fail("Revoked query published") }
        active = false; f.io.drain(); f.main.drain(); assertEquals(0, reads)
    }
    @Test fun currentQueryFailureDoesNotPublishFalseAndNextEventCanRecover() = Fixture().use { f ->
        val lease = f.queries.claim { true }
        lease.offer(PlaybackStateQueries.Kind.CALLBACK, lease.advance()!!, { error("SDK failed") }) { _, _ -> fail("Failure published") }
        f.io.drain(); f.main.drain(); assertEquals(1, f.errors.size); assertTrue(f.values.isEmpty())
        f.offer(lease, lease.advance()!!, true); f.io.drain(); f.main.drain(); assertEquals(listOf(true), f.values)
    }
    @Test fun retiredFailureDoesNotReportErrorToReplacement() = Fixture().use { f ->
        val old = f.queries.claim { true }
        old.offer(PlaybackStateQueries.Kind.CALLBACK, old.advance()!!, {
            val next = f.queries.claim { true }; f.offer(next, next.advance()!!, true); error("retired SDK error")
        }) { _, _ -> fail("Retired failure published") }
        f.io.drain(); f.main.drain(); assertTrue(f.errors.isEmpty()); assertEquals(listOf(true), f.values)
    }
    @Test fun publisherCanRecheckGuardAfterAcquiringItsOwnLock() = Fixture().use { f ->
        val lease = f.queries.claim { true }
        lease.offer(PlaybackStateQueries.Kind.CALLBACK, lease.advance()!!, { true }) { _, current ->
            assertTrue(current()); lease.advance(); assertFalse(current())
        }
        f.io.drain(); f.main.drain()
    }
    @Test fun closingProcessQueueDropsAllPendingReadsAndReplies() = Fixture().use { f ->
        val lease = f.queries.claim { true }; val epoch = lease.advance()!!
        f.offer(lease, epoch, true); f.io.drain()
        lease.offer(PlaybackStateQueries.Kind.REFRESH, epoch, { fail("Closed SDK read ran"); false }) { _, _ -> fail("Closed query published") }
        f.queries.close(); f.io.drain(); f.main.drain(); assertTrue(f.values.isEmpty())
    }
}
