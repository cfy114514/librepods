package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class CommandCompletionBatchTest {
    @Test fun aBurstSharesOneBatchAndOnlyLatestCompletionReleasesIt() {
        val batches = CommandCompletionBatch(); val first = batches.offer(); var latest = first
        repeat(10000) { latest = batches.offer(); assertFalse(latest.created); assertEquals(first.ticket.batch, latest.ticket.batch) }
        assertFalse(batches.isCurrent(first.ticket)); assertFalse(batches.complete(first.ticket))
        assertTrue(batches.isCurrent(latest.ticket)); assertTrue(batches.complete(latest.ticket))
        assertFalse(batches.complete(latest.ticket)); assertTrue(batches.offer().created)
    }
    @Test fun expiredBatchCannotCompleteOrExpireItsSuccessor() {
        val batches = CommandCompletionBatch(); val old = batches.offer().ticket
        assertTrue(batches.expire(old.batch)); assertFalse(batches.isCurrent(old))
        val fresh = batches.offer(); assertTrue(fresh.created); assertNotEquals(old.batch, fresh.ticket.batch)
        assertFalse(batches.complete(old)); assertFalse(batches.expire(old.batch)); assertTrue(batches.isCurrent(fresh.ticket))
        assertTrue(batches.complete(fresh.ticket))
    }
    @Test fun expiryInvalidatesAllCommandsInAnActiveBurst() {
        val batches = CommandCompletionBatch(); val old = batches.offer().ticket; val latest = batches.offer().ticket
        assertTrue(batches.expire(old.batch)); assertFalse(batches.isCurrent(latest)); assertFalse(batches.complete(latest))
    }
}
