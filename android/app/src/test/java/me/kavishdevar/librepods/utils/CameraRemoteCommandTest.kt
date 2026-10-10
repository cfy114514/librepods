package me.kavishdevar.librepods.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class CameraRemoteCommandTest {
    private class FakeProcess(output: String) : Process() {
        val commands = ByteArrayOutputStream()
        private val responses = ByteArrayInputStream(output.toByteArray())
        var alive = true
        var destroyed = false
        override fun getOutputStream(): OutputStream = commands
        override fun getInputStream(): InputStream = responses
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { alive = false; return 0 }
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { alive = false; destroyed = true }
        override fun isAlive() = alive
    }

    @Test fun closedCameraDoesNotLaunchRoot() {
        assertFalse(CameraRemoteCommand(launch = { throw AssertionError("must not start") }).capture { false })
    }

    @Test fun cameraClosedWhilePermissionIsPendingNeverSendsKey() {
        val process = FakeProcess("")
        var allowed = true
        var now = 0L
        val capture = CameraRemoteCommand(launch = { process }, nowMillis = { now }, idle = { now += 10; allowed = false })
        assertFalse(capture.capture { allowed })
        assertEquals(0, process.commands.size())
        assertTrue(process.destroyed)
    }

    @Test fun rootApprovalTimeoutClosesProcessAndNeverSendsKey() {
        val process = FakeProcess("unrelated status\n")
        var now = 0L
        assertFalse(CameraRemoteCommand(launch = { process }, nowMillis = { now }, idle = { now += 100 }).capture { true })
        assertEquals(3000L, now)
        assertEquals(0, process.commands.size())
        assertTrue(process.destroyed)
    }

    @Test fun confirmedForegroundCameraSendsExactlyOneKeyRequestAndClosesProcess() {
        val process = FakeProcess("unrelated status\nLibrePodsCameraReady\n")
        val capture = CameraRemoteCommand(launch = { process }, idle = { process.alive = false })
        assertTrue(capture.capture { true })
        assertEquals("shoot\n", process.commands.toString("US-ASCII"))
        assertTrue(process.destroyed)
    }

    @Test fun stuckKeyCommandIsBounded() {
        val process = FakeProcess("LibrePodsCameraReady\n")
        var now = 0L
        assertFalse(CameraRemoteCommand(launch = { process }, nowMillis = { now }, idle = { now += 100 }).capture { true })
        assertEquals(2000L, now)
        assertEquals("shoot\n", process.commands.toString("US-ASCII"))
        assertTrue(process.destroyed)
    }
}
