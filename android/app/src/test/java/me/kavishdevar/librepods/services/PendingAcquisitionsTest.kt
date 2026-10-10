package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class PendingAcquisitionsTest {
    private fun queue() = PendingAcquisitions<Int, String>(setOf(1, 2))

    @Test fun serviceReplacementReusesUndeliveredRegistration() {
        val q = queue()
        val first = q.offer(1, "old") { true }!!
        assertTrue(first.acquire)
        assertTrue(q.start(first.ticket) { true }.ready)
        repeat(10_000) {
            val next = q.offer(1, "new-$it") { true }!!
            assertFalse(next.acquire)
            assertEquals(first.ticket, next.ticket)
        }
        assertEquals("new-9999", q.take(first.ticket))
        assertNull(q.finished(first.ticket))
    }

    @Test fun stoppedCallerCannotReplaceAValidSuccessor() {
        val q = queue()
        val first = q.offer(1, "live") { true }!!
        assertNull(q.offer(1, "stopped") { false })
        assertEquals("live", q.take(first.ticket))
    }

    @Test fun nextAcquisitionWaitsForThePreviousResourceToClose() {
        val q = queue()
        val first = q.offer(1, "old") { true }!!
        assertEquals("old", q.take(first.ticket))
        repeat(500) { assertFalse(q.offer(1, "pending-$it") { true }!!.acquire) }
        assertNull(q.take(first.ticket))
        assertNull(q.rejected(first.ticket))
        val next = q.finished(first.ticket)!!
        assertTrue(next.acquire)
        assertNotEquals(first.ticket, next.ticket)
        assertEquals("pending-499", q.take(next.ticket))
    }

    @Test fun rejectionTargetsTheLatestWaitingOwnerAndAllowsANewRequest() {
        val q = queue()
        val old = q.offer(1, "old") { true }!!
        val replacement = q.offer(1, "replacement") { true }!!
        assertEquals("old", replacement.previous)
        assertEquals("replacement", q.rejected(old.ticket))
        val next = q.offer(1, "retry") { true }!!
        assertNotEquals(old.ticket, next.ticket)
        assertNull(q.rejected(old.ticket))
        assertEquals("retry", q.take(next.ticket))
    }

    @Test fun anInvalidOwnerIsRetiredBeforePlatformRegistration() {
        val q = queue()
        val old = q.offer(1, "invalid") { true }!!
        val start = q.start(old.ticket) { false }
        assertFalse(start.ready)
        assertEquals("invalid", start.rejected)
        assertNull(q.take(old.ticket))
        assertTrue(q.offer(1, "valid") { true }!!.acquire)
    }

    @Test fun duplicateStartAndCompletionCannotAffectAnotherGeneration() {
        val q = queue()
        val old = q.offer(1, "old") { true }!!
        assertTrue(q.start(old.ticket) { true }.ready)
        assertFalse(q.start(old.ticket) { true }.ready)
        assertEquals("old", q.take(old.ticket))
        q.offer(1, "new") { true }
        val next = q.finished(old.ticket)!!
        assertNull(q.finished(old.ticket))
        assertNull(q.rejected(old.ticket))
        assertEquals("new", q.take(next.ticket))
    }

    @Test fun independentProfilesDoNotBlockEachOther() {
        val q = queue()
        val a = q.offer(1, "a") { true }!!
        val b = q.offer(2, "b") { true }!!
        assertEquals("a", q.take(a.ticket))
        assertEquals("b", q.take(b.ticket))
        assertNull(q.finished(b.ticket))
        q.offer(1, "later") { true }
        assertNotNull(q.finished(a.ticket))
    }

    @Test fun aCallbackBeforeRegistrationReturnsOwnsItsResource() {
        val q = queue()
        val a = q.offer(1, "a") { true }!!
        assertTrue(q.start(a.ticket) { true }.ready)
        assertEquals("a", q.take(a.ticket))
        assertNull(q.rejected(a.ticket))
        assertNull(q.take(a.ticket))
        assertNull(q.finished(a.ticket))
        assertTrue(q.offer(1, "next") { true }!!.acquire)
    }
}
