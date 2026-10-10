package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.data.CustomEq
import org.junit.Assert.*
import org.junit.Test

class CustomEqObserverOwnershipTest {
    @Test fun oldViewModelHandleCannotRemoveNewManagerListener() {
        val manager = AACPManager()
        var oldCalls = 0
        var freshCalls = 0
        val old = manager.registerCustomEqCallback { oldCalls++ }
        val fresh = manager.registerCustomEqCallback { freshCalls++ }
        old.close()
        manager.customEqCallback?.invoke(CustomEq(2, 65, 50, 50))
        assertEquals(0, oldCalls)
        assertEquals(1, freshCalls)
        fresh.close()
        assertNull(manager.customEqCallback)
    }

    @Test fun legacyCallbackSetterIsCompatibleWithHandleOwnership() {
        val manager = AACPManager()
        var freshCalls = 0
        val old = manager.registerCustomEqCallback { fail("Retired callback ran") }
        manager.customEqCallback = { freshCalls++ }
        old.close()
        manager.customEqCallback?.invoke(CustomEq(1, 50, 50, 50))
        assertEquals(1, freshCalls)
        manager.customEqCallback = null
        assertNull(manager.customEqCallback)
    }
}
