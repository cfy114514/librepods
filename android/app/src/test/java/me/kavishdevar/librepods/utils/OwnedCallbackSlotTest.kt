package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class OwnedCallbackSlotTest {
    @Test fun closingOldRegistrationCannotClearTheReplacementCallback() {
        val slot = OwnedCallbackSlot<() -> String>()
        val old = slot.register { "old" }
        val fresh = slot.register { "fresh" }
        old.close(); old.close()
        val received = mutableListOf<String>()
        slot.dispatch { received.add(it()) }
        assertEquals(listOf("fresh"), received)
        fresh.close()
        assertNull(slot.get())
    }

    @Test fun aCapturedOldRegistrationCannotDeliverQueuedWorkAfterReplacement() {
        val slot = OwnedCallbackSlot<() -> Unit>()
        var oldCalls = 0
        var freshCalls = 0
        val old = slot.register { oldCalls++ }
        val queued = { old.dispatch { it() } }
        val fresh = slot.register { freshCalls++ }
        queued(); slot.dispatch { it() }
        assertEquals(0, oldCalls)
        assertEquals(1, freshCalls)
        fresh.close()
    }

    @Test fun repeatedReplacingAndLateCleanupRetainOneCurrentListener() {
        val slot = OwnedCallbackSlot<() -> Int>()
        val retired = (0..10_000).map { value -> slot.register { value } }
        retired.dropLast(1).reversed().forEach { it.close() }
        var heard = -1
        slot.dispatch { heard = it() }
        assertEquals(10_000, heard)
        retired.last().close()
    }

    @Test fun registeringTheSameFunctionTwiceStillUsesDifferentOwnershipTokens() {
        val slot = OwnedCallbackSlot<() -> Unit>()
        var calls = 0
        val callback: () -> Unit = { calls++ }
        val old = slot.register(callback)
        val fresh = slot.register(callback)
        old.close(); slot.dispatch { it() }
        assertEquals(1, calls)
        fresh.close()
    }

    @Test fun explicitClearInvalidatesCapturedCallbacksAndAllowsAnewRegistration() {
        val slot = OwnedCallbackSlot<() -> String>()
        val retired = slot.register { "retired" }
        slot.clear()
        var heard = ""
        retired.dispatch { heard = it() }
        assertEquals("", heard)
        val fresh = slot.register { "fresh" }
        retired.close(); slot.dispatch { heard = it() }
        assertEquals("fresh", heard)
        fresh.close()
    }
}
