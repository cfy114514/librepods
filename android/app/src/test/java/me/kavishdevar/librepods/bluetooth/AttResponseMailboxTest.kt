package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttResponseMailboxTest {
    @Test fun unsolicitedNotificationsAndRepliesAreNotSavedForFutureRequests() {
        val mailbox = AttResponseMailbox()
        repeat(100_000) {
            mailbox.offer(byteArrayOf(0x1B, 0x18, 0, 1))
            mailbox.offer(byteArrayOf(0x0B, 1))
        }
        val pending = mailbox.expect(0x0B)
        assertNull(pending.await(0))
        mailbox.finish(pending)
    }

    @Test fun responseArrivingBeforeAwaitIsRetainedAndNotificationsDoNotConsumeIt() {
        val mailbox = AttResponseMailbox()
        val pending = mailbox.expect(0x0B)
        mailbox.offer(byteArrayOf(0x1B, 0x18, 0, 1))
        mailbox.offer(byteArrayOf(0x13))
        mailbox.offer(byteArrayOf(0x0B, 42))
        assertArrayEquals(byteArrayOf(0x0B, 42), pending.await(0))
        assertNull(pending.await(0))
        mailbox.finish(pending)
    }

    @Test fun duplicateRepliesCannotBuildAnUnboundedBacklog() {
        val mailbox = AttResponseMailbox()
        val pending = mailbox.expect(0x0B)
        mailbox.offer(byteArrayOf(0x0B, 42))
        repeat(100_000) { mailbox.offer(byteArrayOf(0x0B, 43)) }
        assertArrayEquals(byteArrayOf(0x0B, 42), pending.await(0))
        assertNull(pending.await(0))
        mailbox.finish(pending)
    }

    @Test fun finishedRequestDoesNotLeakAReplyToTheNextRequest() {
        val mailbox = AttResponseMailbox()
        val first = mailbox.expect(0x0B)
        mailbox.offer(byteArrayOf(0x0B, 1))
        mailbox.finish(first)
        mailbox.offer(byteArrayOf(0x0B, 2))
        val second = mailbox.expect(0x0B)
        assertNull(second.await(0))
        mailbox.offer(byteArrayOf(0x0B, 3))
        assertArrayEquals(byteArrayOf(0x0B, 3), second.await(0))
        mailbox.finish(second)
    }

    @Test fun disconnectReleasesWaitingRequestAndAllowsANewSession() {
        val mailbox = AttResponseMailbox()
        val pending = mailbox.expect(0x13)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<ByteArray?> { pending.await(30_000) }
            mailbox.clear()
            assertNull(waiting.get(1, TimeUnit.SECONDS))
            val next = mailbox.expect(0x13)
            mailbox.finish(pending)
            mailbox.offer(byteArrayOf(0x13))
            assertArrayEquals(byteArrayOf(0x13), next.await(0))
            mailbox.finish(next)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun overlappingRequestsMustBeSerializedByTheCaller() {
        val mailbox = AttResponseMailbox()
        mailbox.expect(0x0B)
        mailbox.expect(0x13)
    }
}
