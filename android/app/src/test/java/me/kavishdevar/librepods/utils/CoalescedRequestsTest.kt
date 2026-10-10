package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class CoalescedRequestsTest {
    private fun queue() = CoalescedRequests<Int, String>(setOf(1, 2))
    @Test fun activeCompletionRemainsDistinctFromPendingVersionAcrossLongOverflow() {
        val q = queue()
        q.offer(1, "before overflow")
        // Reach the numeric boundary without issuing Long.MAX_VALUE requests.
        val slotsField = q.javaClass.getDeclaredField("slots").apply { isAccessible = true }
        val slot = (slotsField.get(q) as Map<*, *>)[1]!!
        slot.javaClass.getDeclaredField("version").apply { isAccessible = true }.setLong(slot, Long.MAX_VALUE)
        val old = q.take(1)!!
        q.offer(1, "after overflow")
        assertFalse(q.isCurrent(old))
        assertNull(q.take(1))
        assertFalse(q.finished(old))
        val fresh = q.take(1)!!
        assertEquals(Long.MIN_VALUE, fresh.version)
        assertEquals("after overflow", fresh.value)
        assertFalse(q.finished(old))
        assertTrue(q.finished(fresh))
    }

    @Test fun aHundredThousandRequestsReuseOneBatchAndKeepLatestValue() {
        val q = queue(); val first = q.offer(1, "first")
        repeat(100_000) { val next = q.offer(1, "latest-$it"); assertFalse(next.created); assertEquals(first.ticket, next.ticket) }
        val work = q.take(1)!!; assertEquals("latest-99999", work.value); assertTrue(q.isCurrent(work)); assertTrue(q.finished(work))
    }
    @Test fun requestsDuringActiveWorkDoNotCreateAnotherActiveDelivery() {
        val q = queue(); q.offer(1, "old"); val old = q.take(1)!!
        repeat(500) { assertFalse(q.offer(1, "new-$it").created) }
        assertNull(q.take(1)); assertFalse(q.isCurrent(old)); assertFalse(q.finished(old))
        val latest = q.take(1)!!; assertEquals("new-499", latest.value); assertTrue(q.finished(latest))
    }
    @Test fun oldDuplicateCompletionCannotFinishTheNewActiveVersion() {
        val q = queue(); q.offer(1, "old"); val old = q.take(1)!!; q.offer(1, "new"); assertFalse(q.finished(old))
        val next = q.take(1)!!; assertFalse(q.finished(old)); assertTrue(q.isCurrent(next)); assertTrue(q.finished(next))
    }
    @Test fun expiryInvalidatesEnteredWorkAndCreatesANewResourceTicket() {
        val q = queue(); val first = q.offer(1, "old"); val old = q.take(1)!!
        assertTrue(q.expire(first.ticket)); assertFalse(q.isCurrent(old)); val next = q.offer(1, "new")
        assertTrue(next.created); assertNotEquals(first.ticket, next.ticket); assertFalse(q.finished(old)); assertFalse(q.expire(first.ticket))
        assertEquals("new", q.take(1)!!.value)
    }
    @Test fun independentKnownKeysRetainTheirOwnLatestRequest() {
        val q = queue(); q.offer(1, "battery"); q.offer(2, "noise"); val a = q.take(1)!!
        q.offer(2, "latest-noise"); assertTrue(q.isCurrent(a)); assertEquals("latest-noise", q.take(2)!!.value)
    }
    @Test fun finishingAnIdleBatchAllowsTheNextBroadcastToAcquireItsOwnResource() {
        val q = queue(); val first = q.offer(1, "old"); val work = q.take(1)!!; assertTrue(q.finished(work)); assertFalse(q.finished(work))
        val next = q.offer(1, "new"); assertTrue(next.created); assertNotEquals(first.ticket, next.ticket)
    }
    @Test fun duplicateTakeAndExpiredPendingWorkAreIgnored() {
        val q = queue(); q.offer(1, "old"); val work = q.take(1)!!; assertNull(q.take(1)); assertTrue(q.finished(work)); assertNull(q.take(1))
        val pending = q.offer(2, "pending"); assertTrue(q.expire(pending.ticket)); assertNull(q.take(2))
    }
    @Test fun unknownKeysCannotGrowTheBatchRegistry() {
        val q = queue(); try { q.offer(3, "invalid"); fail("Unknown key accepted") } catch (_: IllegalArgumentException) {}
        assertNull(q.take(3)); assertTrue(q.offer(1, "valid").created)
    }
}
