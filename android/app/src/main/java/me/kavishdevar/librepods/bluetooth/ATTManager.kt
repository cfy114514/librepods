/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.io.Closeable
import java.util.EnumSet
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.utils.OwnedCallbackSlot

private const val TAG = "ATTManager"

enum class ATTHandles(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A)
}

enum class ATTCCCDHandles(val value: Int) {
    TRANSPARENCY(ATTHandles.TRANSPARENCY.value + 1),
    //    LOUD_SOUND_REDUCTION(ATTHandles.LOUD_SOUND_REDUCTION.value + 1), // doesn't work
    HEARING_AID(ATTHandles.HEARING_AID.value + 1)
}

class ATTManagerv2 {
    // Values belong to a reader, so a retired connection cannot repopulate
    // the cache used by a replacement device/socket.
    val characteristicList: Map<ATTHandles, ByteArray>
        get() = activeReader?.characteristics?.mapValues { it.value.copyOf() } ?: emptyMap()

    private val requestLock = Any()
    private val readerLock = Any()

    private class ReaderSession(val socket: BluetoothSocket) {
        val responses = AttResponseMailbox()
        val characteristics = ConcurrentHashMap<ATTHandles, ByteArray>()
        val cacheLock = Any()
        val notificationVersions = LongArray(ATTHandles.entries.size)
        val enabledNotifications = EnumSet.noneOf(ATTCCCDHandles::class.java)
        var thread: Thread? = null
    }

    internal class ReaderLease internal constructor(val socket: BluetoothSocket, internal val identity: Any)

    internal fun captureReader(expectedSocket: BluetoothSocket): ReaderLease? {
        val session = activeReader ?: return null
        return if (session.socket === expectedSocket && isCurrent(session)) ReaderLease(expectedSocket, session) else null
    }

    internal fun isCurrentReader(lease: ReaderLease): Boolean =
        (lease.identity as? ReaderSession)?.let(::isCurrent) == true

    private fun isCurrent(session: ReaderSession): Boolean = activeReader === session &&
        session.socket === BluetoothConnectionManager.attSocket && session.socket.isConnected

    @Volatile
    private var activeReader: ReaderSession? = null

    private val notificationCallbacks = OwnedCallbackSlot<(ReaderLease, Byte, ByteArray) -> Unit>()

    @JvmOverloads
    fun startReader(expectedSocket: BluetoothSocket? = BluetoothConnectionManager.attSocket): Boolean {
        val previous = synchronized(readerLock) {
            val socket = expectedSocket ?: return false
            if (socket !== BluetoothConnectionManager.attSocket || !socket.isConnected) return false
            if (activeReader?.socket === socket) return true
            // A reconnect can install the next socket before the old blocking
            // reader has exited. Retire that reader before starting this one.
            val retired = activeReader
            retired?.responses?.clear()
            val session = ReaderSession(socket)
            activeReader = session
            session.thread = Thread {
                try {
                    runReaderLoop(session)
                } catch (t: Throwable) {
                    Log.e(TAG, "reader thread crashed: ${t.message}", t)
                } finally {
                    synchronized(readerLock) {
                        if (activeReader === session) activeReader = null
                        // EOF and read failures release requests too. An old reader
                        // only clears its own mailbox when a new session has started.
                        session.responses.clear()
                    }
                    Log.d(TAG, "reader thread stopped")
                }
            }.also { it.name = "ATT-Reader"; it.isDaemon = true; it.start() }
            Log.d(TAG, "reader started")
            retired
        }
        // Do not hold the reader-publication lock through a native socket close.
        // Service retirement and a successor's callbacks must be able to revoke
        // this newly published session while the older transport is still closing.
        previous?.let { it.thread?.interrupt(); closeSessionSocket(it) }
        return true
    }

    fun stopReader(socket: BluetoothSocket? = null) {
        val session = synchronized(readerLock) {
            val reader = activeReader ?: return
            if (socket != null && reader.socket !== socket) return
            reader
        }
        retire(session)
    }

    /** Revoke callbacks and release requests now; the caller owns closing this socket. */
    internal fun detachReader(socket: BluetoothSocket) {
        val session = synchronized(readerLock) {
            activeReader?.takeIf { it.socket === socket }
        } ?: return
        detach(session)
    }

    internal fun detachReader(): BluetoothSocket? {
        val session = synchronized(readerLock) { activeReader } ?: return null
        return session.socket.takeIf { detach(session) }
    }

    private fun detach(session: ReaderSession): Boolean {
        synchronized(readerLock) {
            // A late timeout/cancellation belongs to this reader, even if a successor reuses its socket.
            if (activeReader !== session) return false
            activeReader = null
            session.responses.clear()
        }
        session.thread?.interrupt()
        return true
    }

    private fun retire(session: ReaderSession) {
        if (!detach(session)) return
        closeSessionSocket(session)
    }

    private fun closeSessionSocket(session: ReaderSession) {
        // Bluetooth reads may not respond to interruption; closing this session's
        // socket unblocks them without touching a subsequently connected socket.
        try {
            session.socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing reader socket: ${e.message}")
        }
    }

    fun setOnNotificationReceived(listener: ((handle: Byte, value: ByteArray) -> Unit)?) {
        if (listener == null) notificationCallbacks.clear() else registerOnNotificationReceived(listener)
    }

    fun registerOnNotificationReceived(listener: (Byte, ByteArray) -> Unit): Closeable =
        notificationCallbacks.register { reader, handle, value -> if (isCurrentReader(reader)) listener(handle, value) }

    internal fun registerReaderNotifications(listener: (ReaderLease, Byte, ByteArray) -> Unit): Closeable =
        notificationCallbacks.register(listener)

    fun enableNotification(handle: ATTCCCDHandles): Boolean =
        writeCharacteristic(handle.value.toByte(), byteArrayOf(0x01))

    internal fun enableNotificationIfCurrent(lease: ReaderLease, handle: ATTCCCDHandles,
        canSend: () -> Boolean): Boolean = synchronized(requestLock) {
        val session = lease.identity as? ReaderSession ?: return@synchronized false
        if (!isCurrent(session) || !canSend()) return@synchronized false
        if (synchronized(session.cacheLock) { handle in session.enabledNotifications }) return@synchronized true
        writeCharacteristic(session, handle.value.toByte(), byteArrayOf(0x01), 2000, canSend)
    }

    fun getCharacteristic(handle: ATTHandles): ByteArray? = synchronized(requestLock) {
        val session = activeReader?.takeIf(::isCurrent) ?: return@synchronized null
        getCharacteristic(session, handle) { true }
    }

    internal fun getCharacteristicIfCurrent(lease: ReaderLease, handle: ATTHandles,
        canRead: () -> Boolean): ByteArray? = synchronized(requestLock) {
        val session = lease.identity as? ReaderSession ?: return@synchronized null
        if (!isCurrent(session) || !canRead()) return@synchronized null
        getCharacteristic(session, handle, canRead)
    }

    private fun getCharacteristic(session: ReaderSession, handle: ATTHandles, canRead: () -> Boolean): ByteArray? {
        if (!isCurrent(session) || !canRead()) return null
        val storedValue = synchronized(session.cacheLock) { session.characteristics[handle]?.copyOf() }
        val value = if (storedValue?.isNotEmpty() == true) storedValue else readCharacteristic(session, handle, 2000, canRead)
        return value?.takeIf { isCurrent(session) && canRead() }
    }

    fun readCharacteristic(handle: ATTHandles, timeoutMillis: Long = 2000): ByteArray? = synchronized(requestLock) {
        val session = activeReader?.takeIf(::isCurrent) ?: return@synchronized null
        readCharacteristic(session, handle, timeoutMillis) { true }
    }

    private fun readCharacteristic(session: ReaderSession, handle: ATTHandles, timeoutMillis: Long,
        canRead: () -> Boolean): ByteArray? {
        try {
            val version = synchronized(session.cacheLock) { session.notificationVersions[handle.ordinal] }
            val pdu = byteArrayOf(0x0A, handle.value.toByte(), 0x00)
            val resp = sendRequest(session, pdu, 0x0B, timeoutMillis, canRead) ?: run {
                Log.d(TAG, "ATT read not completed for handle ${handle.value}")
                return null
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "read response: ${resp.joinToString(" ") { String.format("%02X", it) }}")
            val value = resp.copyOfRange(1, resp.size)
            if (!isCurrent(session)) return null
            val currentValue = cacheReply(session, handle, value, version) ?: return null
            return currentValue.takeIf { canRead() }
        } catch (e: Exception) {
            Log.e(TAG, "error reading characteristic: ${e.message}")
            return null
        }
    }

    fun writeCharacteristic(handle: ATTHandles, data: ByteArray, timeoutMillis: Long = 2000): Boolean {
        val session = activeReader?.takeIf(::isCurrent) ?: return false
        return writeCharacteristic(session, handle, data, timeoutMillis) { true }
    }

    internal fun writeCharacteristicIfCurrent(lease: ReaderLease, handle: ATTHandles, data: ByteArray,
        canSend: () -> Boolean, timeoutMillis: Long = 2000): Boolean {
        val session = lease.identity as? ReaderSession ?: return false
        if (!isCurrent(session) || !canSend()) return false
        return writeCharacteristic(session, handle, data, timeoutMillis, canSend)
    }

    private fun writeCharacteristic(session: ReaderSession, handle: ATTHandles, data: ByteArray,
        timeoutMillis: Long, canSend: () -> Boolean): Boolean = synchronized(requestLock) {
        val value = data.copyOf()
        val version = synchronized(session.cacheLock) { session.notificationVersions[handle.ordinal] }
        val success = writeCharacteristic(session, handle.value.toByte(), value, timeoutMillis, canSend)
        if (success) cacheReply(session, handle, value, version)
        return success
    }

    fun writeCharacteristic(handle: Byte, data: ByteArray, timeoutMillis: Long = 2000): Boolean {
        val session = activeReader?.takeIf(::isCurrent) ?: return false
        return writeCharacteristic(session, handle, data.copyOf(), timeoutMillis) { true }
    }

    private fun writeCharacteristic(session: ReaderSession, handle: Byte, data: ByteArray, timeoutMillis: Long,
        canSend: () -> Boolean): Boolean {
        try {
            val pdu = byteArrayOf(0x12, handle, 0x00) + data // 0x00 for LE
            val resp = sendRequest(session, pdu, 0x13, timeoutMillis, canSend) ?: run {
                Log.d(TAG, "ATT write not completed for handle ${handle.toInt() and 0xFF}")
                return false
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "write response: ${resp.joinToString(" ") { String.format("%02X", it) }}")
            ATTCCCDHandles.entries.find { it.value == (handle.toInt() and 0xFF) }?.let { cccd ->
                synchronized(session.cacheLock) {
                    if (isCurrent(session)) {
                        if (data.firstOrNull()?.toInt()?.and(1) == 1) session.enabledNotifications.add(cccd)
                        else session.enabledNotifications.remove(cccd)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "error writing characteristic: ${e.message}")
            return false
        }
    }

    fun disconnected() {
        stopReader()
        Log.d(TAG, "ATT disconnected")
    }

    private fun cacheReply(session: ReaderSession, handle: ATTHandles, value: ByteArray, notificationVersion: Long): ByteArray? =
        synchronized(session.cacheLock) {
            if (!isCurrent(session)) return@synchronized null
            if (session.notificationVersions[handle.ordinal] == notificationVersion) {
                session.characteristics[handle] = value.copyOf()
            }
            // A notification delivered during the request is newer than this reply.
            // Return the same value that the next cached reader will see.
            session.characteristics[handle]?.copyOf()
        }

    private fun runReaderLoop(session: ReaderSession) {
        val input = session.socket.inputStream
        val buffer = ByteArray(512)

        while (activeReader === session) {
            try {
                val len = input.read(buffer)
                if (len == -1) {
                    Log.w(TAG, "ATT input stream ended")
                    break
                }
                if (activeReader !== session) break
                val data = buffer.copyOfRange(0, len)
                if (data.isEmpty()) continue

                val opcode = data[0]
                if (BuildConfig.DEBUG) Log.d(TAG, "pdu received ${data.joinToString(" ") { String.format("%02X", it) }}")

                session.responses.offer(data)

                if (opcode == 0x1B.toByte()) {
                    if (data.size >= 3) {
                        val handle = data[1]
                        val value = if (data.size > 3) data.copyOfRange(3, data.size) else ByteArray(0)
                        // Attribute handles are 16-bit; don't alias an unknown high byte.
                        if (data[2] != 0x00.toByte()) continue
                        ATTHandles.entries.find { it.value == (handle.toInt() and 0xFF) }?.let {
                            synchronized(session.cacheLock) {
                                if (isCurrent(session)) {
                                    session.notificationVersions[it.ordinal]++
                                    session.characteristics[it] = value.copyOf()
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) Log.d(TAG, "notification/indication handle=0x${String.format("%02X", handle)} value=${value.toHexString()}")
                        try {
                            val source = ReaderLease(session.socket, session)
                            notificationCallbacks.dispatch { if (isCurrent(session)) it(source, handle, value) }
                        } catch (t: Throwable) {
                            Log.e(TAG, "onNotificationReceived threw: ${t.message}", t)
                        }
                    } else {
                        Log.w(TAG, "notification PDU too short: ${data.joinToString(" ") { String.format("%02X", it) }}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "error in reader loop: ${e.message}", e)
                break
            }
        }
    }

    private fun sendRequest(session: ReaderSession, pdu: ByteArray, responseOpcode: Byte, timeoutMillis: Long,
        canSend: () -> Boolean = { true }): ByteArray? =
        synchronized(requestLock) {
            if (Thread.currentThread().isInterrupted || !canSend()) return@synchronized null
            val pending = synchronized(readerLock) register@{
                if (!isCurrent(session)) return@register null
                // Register before writing: a fast reply may arrive before await() starts.
                session.responses.expect(responseOpcode, pdu[0])
            } ?: return@synchronized null
            try {
                if (!writeCurrentPacket(pdu, { isCurrent(session) && canSend() }, { session.socket.outputStream })) {
                    return@synchronized null
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "sending request: ${pdu.joinToString(" ") { String.format("%02X", it) }}")
                // Once sent, consume its reply even if the UI changes. ATT has no transaction ID.
                val response = pending.await(timeoutMillis)
                if (response == null) {
                    // ATT replies have no transaction ID. After a timeout we cannot
                    // safely attribute a late reply to another request of this opcode.
                    retire(session)
                    null
                } else if (!isCurrent(session)) {
                    null
                } else if (response[0] == 0x01.toByte()) {
                    Log.w(TAG, "ATT error response: ${response.toHexString()}")
                    null
                } else response
            } catch (e: InterruptedException) {
                retire(session)
                Thread.currentThread().interrupt()
                null
            } catch (e: Exception) {
                retire(session)
                throw e
            } finally {
                session.responses.finish(pending)
            }
        }
}
