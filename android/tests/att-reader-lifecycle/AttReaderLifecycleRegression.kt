package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val DEADLINE_SECONDS = 3L

private fun CountDownLatch.awaitRequired(description: String) {
    check(await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "Timed out: $description" }
}

/** Bluetooth I/O can ignore Java thread interruption until its socket closes. */
private fun CountDownLatch.awaitUninterruptibly() {
    var interrupted = false
    while (true) {
        try { await(); break } catch (_: InterruptedException) { interrupted = true }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private class ReadControl(val reader: Thread) {
    private val release = CountDownLatch(1)
    @Volatile private var failure: IOException? = null

    fun eof() { release.countDown() }
    fun fail() { failure = IOException("synthetic ATT read failure"); release.countDown() }
    fun read(): Int {
        release.awaitUninterruptibly()
        failure?.let { throw it }
        return -1
    }
    fun join() {
        reader.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS))
        check(!reader.isAlive) { "ATT reader did not finish its finally block" }
    }
}

private class FakeSocket : BluetoothSocket() {
    private val connected = AtomicBoolean(true)
    val closeCount = AtomicInteger()
    val closeEntered = CountDownLatch(1)
    val closeRelease = CountDownLatch(1)
    val packetWritten = CountDownLatch(1)
    val readEntries = LinkedBlockingQueue<ReadControl>()
    val readers = ConcurrentHashMap<Thread, ReadControl>()
    @Volatile var blockClose = false
    @Volatile var releaseReadsOnClose = true
    override val isConnected: Boolean get() = connected.get()
    override val inputStream = object : InputStream() {
        override fun read(): Int {
            val thread = Thread.currentThread()
            val control = readers[thread] ?: ReadControl(thread).also {
                readers[thread] = it
                readEntries.add(it)
            }
            return control.read()
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read()
    }
    override val outputStream = object : OutputStream() {
        override fun write(value: Int) { packetWritten.countDown() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { packetWritten.countDown() }
    }
    override fun close() {
        closeCount.incrementAndGet()
        connected.set(false)
        closeEntered.countDown()
        if (blockClose) closeRelease.awaitUninterruptibly()
        if (releaseReadsOnClose) readers.values.forEach(ReadControl::eof)
    }
    fun enteredReader(): ReadControl = checkNotNull(readEntries.poll(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
        "ATT reader did not enter InputStream.read"
    }
}

private class Fixture : AutoCloseable {
    val manager = ATTManagerv2()
    val sockets = CopyOnWriteArrayList<FakeSocket>()
    val workers = Executors.newCachedThreadPool { Thread(it, "ATT-test-worker").apply { isDaemon = true } }
    fun socket(): FakeSocket = FakeSocket().also(sockets::add)
    fun start(socket: FakeSocket): ReadControl {
        BluetoothConnectionManager.attSocket = socket
        check(manager.startReader(socket)) { "Reader did not start" }
        return socket.enteredReader()
    }
    override fun close() {
        sockets.forEach { it.closeRelease.countDown(); it.readers.values.forEach(ReadControl::eof) }
        manager.stopReader()
        sockets.forEach { socket -> socket.readers.values.forEach(ReadControl::join) }
        workers.shutdownNow()
        BluetoothConnectionManager.attSocket = null
    }
}

private fun idleTerminationClosesSocket(failure: Boolean) = Fixture().use { f ->
    val socket = f.socket()
    val reader = f.start(socket)
    val lease = checkNotNull(f.manager.captureReader(socket))
    if (failure) reader.fail() else reader.eof()
    reader.join()
    check(!f.manager.isCurrentReader(lease)) { "Terminated reader is still current" }
    check(socket.closeCount.get() == 1) { "Expected one socket close after reader termination, got ${socket.closeCount.get()}" }
    f.manager.stopReader(socket)
    check(socket.closeCount.get() == 1) { "stopReader closed an already retired socket again" }
}

private fun eofReleasesPendingRequest() = Fixture().use { f ->
    val socket = f.socket()
    val reader = f.start(socket)
    val lease = checkNotNull(f.manager.captureReader(socket))
    val request = f.workers.submit<ByteArray?> { f.manager.readCharacteristic(ATTHandles.TRANSPARENCY, 30_000) }
    socket.packetWritten.awaitRequired("ATT request write")
    reader.eof()
    check(request.get(DEADLINE_SECONDS, TimeUnit.SECONDS) == null) { "Terminated request returned a value" }
    reader.join()
    check(!f.manager.isCurrentReader(lease)) { "Reader lease survived EOF" }
    check(socket.closeCount.get() == 1) { "Pending request was released, but expected one socket close; got ${socket.closeCount.get()}" }
}

private fun stopIsIdempotent() = Fixture().use { f ->
    val socket = f.socket()
    val reader = f.start(socket)
    val lease = checkNotNull(f.manager.captureReader(socket))
    f.manager.stopReader(socket)
    reader.join()
    f.manager.stopReader(socket)
    f.manager.disconnected()
    check(!f.manager.isCurrentReader(lease)) { "Stopped reader lease is still current" }
    check(socket.closeCount.get() == 1) { "Expected exactly one close across stopReader/disconnected, got ${socket.closeCount.get()}" }
}

private fun oldReaderCannotCloseReplacement() = Fixture().use { f ->
    val previous = f.socket().apply { releaseReadsOnClose = false }
    val oldReader = f.start(previous)
    val next = f.socket()
    f.start(next)
    val nextLease = checkNotNull(f.manager.captureReader(next))
    oldReader.eof()
    oldReader.join()
    check(previous.closeCount.get() == 1) { "Old socket close count changed after its delayed reader finally" }
    check(next.closeCount.get() == 0) { "Old reader closed the replacement socket" }
    check(f.manager.isCurrentReader(nextLease)) { "Old reader revoked the replacement lease" }
}

private fun blockingCloseDoesNotPreventReplacement() = Fixture().use { f ->
    val previous = f.socket().apply { blockClose = true }
    val oldReader = f.start(previous)
    oldReader.eof()
    previous.closeEntered.awaitRequired("EOF-triggered socket close")
    val next = f.socket()
    BluetoothConnectionManager.attSocket = next
    val starting = f.workers.submit<Boolean> { f.manager.startReader(next) }
    check(starting.get(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "Replacement reader did not start during old close" }
    next.enteredReader()
    val nextLease = checkNotNull(f.manager.captureReader(next))
    check(f.manager.isCurrentReader(nextLease)) { "Replacement is not current while old close is blocked" }
    previous.closeRelease.countDown()
    oldReader.join()
    check(next.closeCount.get() == 0) { "Finishing old close also closed replacement" }
    check(f.manager.isCurrentReader(nextLease)) { "Finishing old close revoked replacement" }
}

private fun detachedReaderCannotCloseSameSocketSuccessor() = Fixture().use { f ->
    val socket = f.socket()
    val oldReader = f.start(socket)
    val oldLease = checkNotNull(f.manager.captureReader(socket))
    f.manager.detachReader(socket)
    check(!f.manager.isCurrentReader(oldLease)) { "Detached reader is still current" }
    f.start(socket)
    val newLease = checkNotNull(f.manager.captureReader(socket))
    oldReader.eof()
    oldReader.join()
    check(socket.closeCount.get() == 0) { "Detached reader closed the reused socket" }
    check(f.manager.isCurrentReader(newLease)) { "Detached reader revoked the same-socket successor" }
}

fun main() {
    val cases = listOf(
        "EOF without pending request closes socket" to { idleTerminationClosesSocket(false) },
        "IOException without pending request closes socket" to { idleTerminationClosesSocket(true) },
        "EOF releases pending request and closes socket" to ::eofReleasesPendingRequest,
        "stopReader is idempotent" to ::stopIsIdempotent,
        "old reader cannot close replacement socket" to ::oldReaderCannotCloseReplacement,
        "blocked old close permits replacement reader" to ::blockingCloseDoesNotPreventReplacement,
        "detached reader cannot close same-socket successor" to ::detachedReaderCannotCloseSameSocketSuccessor
    )
    var failures = 0
    for ((name, test) in cases) {
        try { test(); println("PASS: $name") } catch (failure: Throwable) {
            failures++
            println("FAIL: $name: ${failure.message}")
            failure.printStackTrace(System.out)
        }
    }
    println("ATT lifecycle regression: ${cases.size - failures}/${cases.size} passed")
    check(failures == 0) { "$failures ATT lifecycle regression(s) failed" }
}
