package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.data.CustomEq
import org.junit.Assert.*
import org.junit.Test

class EqStateTest {
    @Test fun eqBuffersCannotBeChangedByTheirCallerOrReader() {
        val manager = AACPManager()
        val source = FloatArray(8) { it.toFloat() }
        manager.setHeadphoneEqState(source, phone = true, media = false)
        source.fill(99f)
        manager.eqData.fill(42f)
        assertArrayEquals(FloatArray(8) { it.toFloat() }, manager.eqData, 0f)
        assertTrue(manager.eqOnPhone)
        assertFalse(manager.eqOnMedia)
    }

    @Test fun disconnectClearsBothEqFamiliesWithoutRetiringTheObserver() {
        val manager = AACPManager()
        val received = mutableListOf<CustomEq>()
        val observer = manager.registerCustomEqCallback { received.add(it) }
        val first = CustomEq(2, 65, 40, 70)
        manager.setHeadphoneEqState(FloatArray(8) { 0.5f }, phone = true, media = true)
        manager.setCustomEqState(first)
        manager.resetDeviceState()
        assertArrayEquals(FloatArray(8), manager.eqData, 0f)
        assertFalse(manager.eqOnPhone)
        assertFalse(manager.eqOnMedia)
        assertEquals(CustomEq(1, 50, 50, 50), manager.customEq)
        assertEquals(listOf(first), received)

        val next = CustomEq(2, 10, 20, 30)
        manager.setCustomEqState(next)
        assertEquals(listOf(first, next), received)
        observer.close()
        manager.setCustomEqState(first)
        assertEquals(listOf(first, next), received)
    }

    @Test fun aRetiredWriteCannotReplaceTheResetOrCurrentConnectionEq() {
        val manager = AACPManager()
        val old = FloatArray(8) { 0.8f }
        manager.setHeadphoneEqState(old, phone = true, media = true)
        manager.resetDeviceState()
        assertFalse(manager.setHeadphoneEqState(old, true, true) { false })
        assertArrayEquals(FloatArray(8), manager.eqData, 0f)
        val current = FloatArray(8) { 0.2f }
        assertTrue(manager.setHeadphoneEqState(current, false, true) { true })
        assertFalse(manager.setHeadphoneEqState(old, true, false) { false })
        assertArrayEquals(current, manager.eqData, 0f)
        assertFalse(manager.eqOnPhone)
        assertTrue(manager.eqOnMedia)
    }

    @Test fun disconnectedEqEditsDoNotChangeTheDeviceCache() {
        val manager = AACPManager()
        val previous = FloatArray(8) { 0.3f }
        manager.setHeadphoneEqState(previous, phone = false, media = true)
        val previousSocket = BluetoothConnectionManager.aacpSocket
        try {
            BluetoothConnectionManager.aacpSocket = null
            manager.sendPhoneMediaEQ(FloatArray(8) { 0.9f }, 1, 2)
            assertArrayEquals(previous, manager.eqData, 0f)
            assertFalse(manager.eqOnPhone)
            assertTrue(manager.eqOnMedia)
        } finally {
            BluetoothConnectionManager.aacpSocket = previousSocket
        }
    }
}
