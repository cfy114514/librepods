package me.kavishdevar.librepods.billing

import org.junit.Assert.*
import org.junit.Test

class FossEntitlementStateTest {
    @Test fun unknownIsNotConfirmedDenialAndSuccessfulReadBecomesReady() {
        val state = FossEntitlementState()
        assertEquals(BillingEntitlement(), state.state.value)
        assertTrue(state.acceptRead(state.beginRead()!!, false))
        assertEquals(BillingEntitlement(ready = true), state.state.value)
        assertTrue(state.acceptRead(state.beginRead()!!, true))
        assertEquals(BillingEntitlement(ready = true, premium = true), state.state.value)
    }
    @Test fun heldOldDenialCannotOverwriteExplicitRestore() {
        val state = FossEntitlementState()
        val read = state.beginRead()!!
        val unlock = state.requestUnlock()
        assertNull(state.beginRead())
        assertFalse(state.acceptRead(read, false))
        state.failRead(read)
        assertEquals(BillingEntitlement(ready = true, premium = true), state.state.value)
        state.completeUnlock(unlock)
        assertFalse(state.acceptRead(read, false))
        assertNull(state.pendingUnlockTicket())
        assertTrue(state.acceptRead(state.beginRead()!!, true))
    }
    @Test fun failedFirstReadRemainsUnknownAndRetryCanRecover() {
        val state = FossEntitlementState()
        state.failRead(state.beginRead()!!)
        assertEquals(BillingEntitlement(failed = true), state.state.value)
        state.acceptRead(state.beginRead()!!, true)
        assertEquals(BillingEntitlement(ready = true, premium = true), state.state.value)
    }
    @Test fun failedRefreshRetainsLastKnownEntitlement() {
        val state = FossEntitlementState()
        state.acceptRead(state.beginRead()!!, true)
        state.failRead(state.beginRead()!!)
        assertEquals(BillingEntitlement(ready = true, premium = true, failed = true), state.state.value)
    }
    @Test fun failedPersistenceStaysPendingAndCanRetryWithoutDowngrade() {
        val state = FossEntitlementState()
        val ticket = state.requestUnlock()
        state.failUnlock(ticket)
        assertEquals(ticket, state.pendingUnlockTicket())
        assertNull(state.beginRead())
        assertEquals(BillingEntitlement(ready = true, premium = true, failed = true), state.state.value)
        state.completeUnlock(ticket)
        assertNull(state.pendingUnlockTicket())
        assertEquals(BillingEntitlement(ready = true, premium = true), state.state.value)
    }
    @Test fun oldWriteCompletionCannotClearNewerRestoreOrItsError() {
        val state = FossEntitlementState()
        val old = state.requestUnlock()
        val current = state.requestUnlock()
        state.failUnlock(current)
        state.completeUnlock(old)
        assertEquals(current, state.pendingUnlockTicket())
        assertTrue(state.state.value.failed)
        state.failUnlock(old)
        state.completeUnlock(current)
        assertNull(state.pendingUnlockTicket())
        assertFalse(state.state.value.failed)
    }
    @Test fun compatibilityObserverDoesNotReceiveUnknownOrLateDenial() {
        val values = mutableListOf<Boolean>()
        val state = FossEntitlementState { if (it.ready) values += it.premium }
        val read = state.beginRead()!!
        state.failRead(read)
        val unlock = state.requestUnlock()
        state.acceptRead(read, false)
        state.completeUnlock(unlock)
        assertEquals(listOf(true, true), values)
    }
}
