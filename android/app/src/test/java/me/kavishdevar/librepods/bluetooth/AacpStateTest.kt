package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AacpStateTest {
    @Test fun changingCommandsKeepOnlyTheLatestValueForEachIdentifier() {
        val manager = AACPManager()
        val identifiers = ControlCommandIdentifiers.entries
        repeat(10_000) { index ->
            val identifier = identifiers[index % identifiers.size]
            val value = byteArrayOf(index.toByte())
            manager.setControlCommandStatusValue(identifier, value)
            assertArrayEquals(value, manager.getControlCommandStatus(identifier)?.value)
            assertTrue(manager.controlCommandStatusList.size <= identifiers.size)
        }
        assertEquals(identifiers.size, manager.controlCommandStatusList.size)
        assertEquals(identifiers.toSet(), manager.controlCommandStatusList.map { it.identifier }.toSet())
    }

    @Test fun replacingStatePreservesNotificationsIncludingRepeatedValues() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.OWNS_CONNECTION
        val received = mutableListOf<Byte>()
        manager.registerControlCommandListener(identifier, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                received.add(controlCommand.value[0])
                assertArrayEquals(controlCommand.value, manager.getControlCommandStatus(identifier)?.value)
            }
        })

        manager.setControlCommandStatusValue(identifier, byteArrayOf(1))
        assertTrue(manager.owns)
        manager.setControlCommandStatusValue(identifier, byteArrayOf(1))
        manager.setControlCommandStatusValue(identifier, byteArrayOf(2))

        assertFalse(manager.owns)
        assertEquals(listOf<Byte>(1, 1, 2), received)
        assertEquals(1, manager.controlCommandStatusList.size)
        assertArrayEquals(byteArrayOf(2), manager.getControlCommandStatus(identifier)?.value)
    }

    @Test fun eachParsedControlPacketNotifiesOnceWithItsStoredNormalizedValue() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.LISTENING_MODE
        val received = mutableListOf<AACPManager.ControlCommand>()
        manager.registerControlCommandListener(identifier, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                received.add(controlCommand)
                assertArrayEquals(controlCommand.value, manager.getControlCommandStatus(identifier)?.value)
            }
        })

        listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(2), byteArrayOf(0)).forEachIndexed { index, value ->
            val packet = manager.createDataPacket(manager.createControlCommandPacket(identifier.value, value))
            val parsed = manager.parseAndStoreControlCommand(packet)
            assertArrayEquals(value, parsed?.value)
            assertEquals(index + 1, received.size)
            assertEquals(parsed, received.last())
            assertEquals(1, manager.controlCommandStatusList.size)
        }

        val unknownPacket = manager.createDataPacket(manager.createControlCommandPacket(0x7F, byteArrayOf(1)))
        assertNull(manager.parseAndStoreControlCommand(unknownPacket))
        assertEquals(4, received.size)
    }

    @Test fun aListenerCanUpdateACommandWithoutLeavingDuplicateState() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.LISTENING_MODE
        val received = mutableListOf<Byte>()
        manager.registerControlCommandListener(identifier, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                received.add(controlCommand.value[0])
                if (controlCommand.value[0] == 1.toByte()) {
                    manager.setControlCommandStatusValue(identifier, byteArrayOf(2))
                }
            }
        })
        manager.setControlCommandStatusValue(identifier, byteArrayOf(1))
        assertEquals(listOf<Byte>(1, 2), received)
        assertEquals(1, manager.controlCommandStatusList.size)
        assertArrayEquals(byteArrayOf(2), manager.getControlCommandStatus(identifier)?.value)
    }

    @Test fun simultaneousSendAndReceiveUpdatesCannotAppendDuplicateStates() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.LISTENING_MODE
        val received = AtomicInteger()
        manager.registerControlCommandListener(identifier, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                received.incrementAndGet()
            }
        })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = List(4) { worker ->
                executor.submit {
                    repeat(2_500) { index ->
                        manager.setControlCommandStatusValue(identifier, byteArrayOf((index + worker).toByte()))
                    }
                }
            }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            manager.setControlCommandStatusValue(identifier, byteArrayOf(42))
            assertEquals(10_001, received.get())
            assertEquals(1, manager.controlCommandStatusList.size)
            assertArrayEquals(byteArrayOf(42), manager.getControlCommandStatus(identifier)?.value)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun reconnectKeepsAnExistingObserverWithoutAddingDuplicateCallbacks() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.OWNS_CONNECTION
        val received = mutableListOf<Byte>()
        val observer = object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                received.add(controlCommand.value[0])
            }
        }
        manager.registerControlCommandListener(identifier, observer)
        fun receive(value: Byte) {
            manager.parseAndStoreControlCommand(
                manager.createDataPacket(manager.createControlCommandPacket(identifier.value, byteArrayOf(value)))
            )
        }

        receive(1)
        assertTrue(manager.owns)
        manager.resetDeviceState()
        assertFalse(manager.owns)
        assertTrue(manager.controlCommandStatusList.isEmpty())
        receive(2)
        assertEquals(listOf<Byte>(1, 2), received)
        assertArrayEquals(byteArrayOf(2), manager.getControlCommandStatus(identifier)?.value)

        manager.unregisterControlCommandListener(identifier, observer)
        receive(1)
        assertEquals(listOf<Byte>(1, 2), received)
    }

    @Test fun observerCanUnregisterDuringNotificationWithoutSkippingOtherObservers() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.LISTENING_MODE
        var firstCount = 0
        var secondCount = 0
        val first = object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                firstCount++
                manager.unregisterControlCommandListener(identifier, this)
            }
        }
        val second = object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                secondCount++
            }
        }
        manager.registerControlCommandListener(identifier, first)
        manager.registerControlCommandListener(identifier, second)
        manager.setControlCommandStatusValue(identifier, byteArrayOf(1))
        manager.setControlCommandStatusValue(identifier, byteArrayOf(2))
        assertEquals(1, firstCount)
        assertEquals(2, secondCount)
    }

    @Test fun headerValidationAcceptsPayloadWithoutRequiringStringConversion() {
        val header = byteArrayOf(4, 0, 4, 0)
        assertTrue(AACPManager.hasExpectedHeader(header))
        assertTrue(AACPManager.hasExpectedHeader(header + ByteArray(10_000) { 0xFF.toByte() }))
        for (length in 0 until header.size) {
            assertFalse(AACPManager.hasExpectedHeader(header.copyOf(length)))
        }
        for (index in header.indices) {
            val corrupted = header.copyOf()
            corrupted[index] = 0x7F
            assertFalse(AACPManager.hasExpectedHeader(corrupted))
        }
    }
}
