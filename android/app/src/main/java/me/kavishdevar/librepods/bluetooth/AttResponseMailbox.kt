package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Holds only the reply to the one ATT request currently in flight. */
internal class AttResponseMailbox {
    internal class PendingResponse internal constructor(val opcode: Byte) {
        private val reply = ArrayBlockingQueue<ByteArray>(1)

        @Volatile
        private var cancelled = false

        fun await(timeoutMillis: Long): ByteArray? {
            val value = reply.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            return value?.takeUnless { cancelled || it.isEmpty() }
        }

        internal fun offer(data: ByteArray) {
            if (!cancelled) reply.offer(data)
        }

        internal fun cancel() {
            cancelled = true
            reply.clear()
            // Wake an in-flight request on disconnect instead of waiting for its timeout.
            reply.offer(ByteArray(0))
        }
    }

    private val pending = AtomicReference<PendingResponse?>()

    fun expect(opcode: Byte): PendingResponse {
        val response = PendingResponse(opcode)
        check(pending.compareAndSet(null, response)) { "An ATT request is already pending" }
        return response
    }

    fun offer(data: ByteArray) {
        val response = pending.get() ?: return
        if (data.isNotEmpty() && data[0] == response.opcode) response.offer(data)
    }

    fun finish(response: PendingResponse) {
        pending.compareAndSet(response, null)
    }

    fun clear() {
        pending.getAndSet(null)?.cancel()
    }
}
