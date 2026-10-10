package me.kavishdevar.librepods.bluetooth

import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.data.CustomEq
import org.junit.Assert.*
import org.junit.Test

class AacpSessionTest {
    private val id = ControlCommandIdentifiers.LISTENING_MODE
    private fun packet(manager: AACPManager, value: Byte) = manager.createDataPacket(
        manager.createControlCommandPacket(id.value, byteArrayOf(value)))

    @Test fun oldSessionCannotPopulateTheReplacementCacheOrNotifyItsObservers() {
        val manager = AACPManager()
        val old = manager.beginDeviceSession()
        val observed = mutableListOf<Byte>()
        manager.registerControlCommandListener(id, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                observed.add(controlCommand.value[0])
            }
        })
        manager.parseAndStoreControlCommand(packet(manager, 1)) { manager.isCurrentDeviceSession(old) }
        val fresh = manager.beginDeviceSession()
        manager.parseAndStoreControlCommand(packet(manager, 2)) { manager.isCurrentDeviceSession(fresh) }
        assertNull(manager.parseAndStoreControlCommand(packet(manager, 4)) { manager.isCurrentDeviceSession(old) })
        assertArrayEquals(byteArrayOf(2), manager.getControlCommandStatus(id)!!.value)
        assertEquals(listOf<Byte>(1, 2), observed)
    }

    @Test fun resetWhileAnUpdateWaitsForTheStateLockRejectsTheLateUpdate() {
        val manager = AACPManager()
        val old = manager.beginDeviceSession()
        val ready = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = synchronized(manager) {
                val task = executor.submit<Boolean> {
                    ready.countDown()
                    manager.setControlCommandStatusValue(id, byteArrayOf(4)) { manager.isCurrentDeviceSession(old) }
                }
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                manager.resetDeviceState()
                task
            }
            assertFalse(result.get(2, TimeUnit.SECONDS))
            assertNull(manager.getControlCommandStatus(id))
        } finally { executor.shutdownNow() }
    }

    @Test fun bothEqStoresRejectRetiredGenerationWithoutReplacingFreshValues() {
        val manager = AACPManager()
        val old = manager.beginDeviceSession()
        val fresh = manager.beginDeviceSession()
        val freshEq = CustomEq(2, 10, 20, 30)
        manager.setCustomEqState(freshEq) { manager.isCurrentDeviceSession(fresh) }
        manager.setHeadphoneEqState(FloatArray(8) { 0.2f }, false, true) { manager.isCurrentDeviceSession(fresh) }
        assertFalse(manager.setCustomEqState(CustomEq(2, 90, 80, 70)) { manager.isCurrentDeviceSession(old) })
        assertFalse(manager.setHeadphoneEqState(FloatArray(8) { 0.9f }, true, false) { manager.isCurrentDeviceSession(old) })
        assertEquals(freshEq, manager.customEq)
        assertArrayEquals(FloatArray(8) { 0.2f }, manager.eqData, 0f)
        assertFalse(manager.eqOnPhone)
        assertTrue(manager.eqOnMedia)
    }

    @Test fun aResetDuringNotificationStopsRemainingOldSessionObservers() {
        val manager = AACPManager()
        val session = manager.beginDeviceSession()
        var first = 0
        var second = 0
        manager.registerControlCommandListener(id, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                first++
                manager.resetDeviceState()
            }
        })
        manager.registerControlCommandListener(id, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) { second++ }
        })
        manager.parseAndStoreControlCommand(packet(manager, 4)) { manager.isCurrentDeviceSession(session) }
        assertEquals(1, first)
        assertEquals(0, second)
        assertNull(manager.getControlCommandStatus(id))
    }

    @Test fun fullPacketEntryRejectsRetiredGenerationBeforeDispatchOrLogging() {
        val manager = AACPManager()
        val old = manager.beginDeviceSession()
        manager.resetDeviceState()
        val calls = AtomicInteger()
        manager.setPacketCallback(Proxy.newProxyInstance(AACPManager.PacketCallback::class.java.classLoader,
            arrayOf(AACPManager.PacketCallback::class.java)) { _, _, _ -> calls.incrementAndGet(); null } as AACPManager.PacketCallback)
        for (opcode in listOf(4, 9, 6, 0x19, 0x4B, 0x31, 0x0E, 0x2E, 0x11, 0x1D, 0x63, 0x17, 0x53)) {
            val packet = manager.createDataPacket(byteArrayOf(opcode.toByte(), 0) + ByteArray(140))
            manager.receivePacket(packet, old)
        }
        assertEquals(0, calls.get())
        assertTrue(manager.getControlCommandStatusSnapshot().isEmpty())
    }

    @Test fun selectedDeviceGuardRejectsStateEvenWhenGenerationIsStillCurrent() {
        val manager = AACPManager()
        val current = manager.beginDeviceSession()
        assertTrue(manager.isCurrentDeviceSession(current))
        assertNull(manager.parseAndStoreControlCommand(packet(manager, 4)) { false })
        assertFalse(manager.setCustomEqState(CustomEq(2, 70, 50, 50)) { false })
        assertEquals(CustomEq(1, 50, 50, 50), manager.customEq)
    }

    @Test fun oneObserverCannotMutateTheNextObserversCommandOrCache() {
        val manager = AACPManager()
        manager.registerControlCommandListener(id, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) { controlCommand.value[0] = 99 }
        })
        var next: Byte? = null
        manager.registerControlCommandListener(id, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) { next = controlCommand.value[0] }
        })
        manager.setControlCommandStatusValue(id, byteArrayOf(2))
        assertEquals(2.toByte(), next)
        assertArrayEquals(byteArrayOf(2), manager.getControlCommandStatus(id)!!.value)
    }
}
