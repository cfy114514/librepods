package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class AudioRelayTest {
    @Test fun longRunningRelayReusesBufferAndHandlesPartialWritesWithoutLosingSamples() {
        var active = true
        var reads = 0
        var writes = 0
        var buffer: ShortArray? = null
        AudioRelay(read = {
            if (buffer == null) buffer = it else assertSame(buffer, it)
            repeat(it.size) { i -> it[i] = i.toShort() }
            reads++
            it.size
        }, write = { data, offset, count ->
            assertSame(buffer, data)
            val n = minOf(7, count)
            repeat(n) { assertEquals((offset + it).toShort(), data[offset + it]) }
            writes++
            if (reads == 100_000 && offset + n == data.size) active = false
            n
        }, active = { active }, routeSafe = { true }, gain = { 1f }, idle = { fail("unexpected stall") }, chunkSamples = 17).run()
        assertEquals(100_000, reads)
        assertEquals(300_000, writes)
    }

    @Test fun stopDuringReadDropsCapturedAudio() {
        var active = true
        AudioRelay(read = { active = false; 1 }, write = { _, _, _ -> fail("must not play after stop"); 0 },
            active = { active }, routeSafe = { true }, gain = { 1f }, idle = {}).run()
    }

    @Test fun routeChangeDuringPartialWriteStopsBeforeRemainingAudio() {
        var safe = true
        var writes = 0
        val error = failure {
            AudioRelay(read = { 8 }, write = { _, _, _ -> writes++; safe = false; 2 },
                active = { true }, routeSafe = { safe }, gain = { 1f }, idle = {}).run()
        }
        assertEquals(LiveListenError.ROUTE_CHANGED, error)
        assertEquals(1, writes)
    }

    @Test fun amplificationSaturatesAndInvalidGainUsesUnity() {
        fun amplify(gain: Float): List<Short> {
            var active = true
            var result = emptyList<Short>()
            AudioRelay(read = { it[0] = 25_000; it[1] = -25_000; 2 }, write = { data, offset, count ->
                result = data.slice(offset until offset + count); active = false; count
            }, active = { active }, routeSafe = { true }, gain = { gain }, idle = {}, chunkSamples = 2).run()
            return result
        }
        assertEquals(listOf(Short.MAX_VALUE, Short.MIN_VALUE), amplify(2f))
        assertEquals(listOf(25_000.toShort(), (-25_000).toShort()), amplify(Float.NaN))
        assertEquals(listOf(0.toShort(), 0.toShort()), amplify(-1f))
    }

    @Test fun repeatedZeroReadsYieldAndTimeout() {
        var now = 0L
        var yields = 0
        val error = failure {
            AudioRelay(read = { 0 }, write = { _, _, _ -> fail("no audio"); 0 }, active = { true },
                routeSafe = { true }, gain = { 1f }, idle = { yields++; now += 10 },
                nowMillis = { now }, stallMillis = 30).run()
        }
        assertEquals(LiveListenError.READ_FAILED, error)
        assertEquals(3, yields)
    }

    @Test fun repeatedZeroWritesYieldAndTimeoutWithoutReadingMore() {
        var now = 0L
        var reads = 0
        var yields = 0
        val error = failure {
            AudioRelay(read = { reads++; 2 }, write = { _, _, _ -> 0 }, active = { true },
                routeSafe = { true }, gain = { 1f }, idle = { yields++; now += 10 },
                nowMillis = { now }, stallMillis = 30).run()
        }
        assertEquals(LiveListenError.WRITE_FAILED, error)
        assertEquals(1, reads)
        assertEquals(3, yields)
    }

    @Test fun negativeOrImpossibleReadCountsFail() {
        for (count in listOf(-3, 481)) {
            assertEquals(LiveListenError.READ_FAILED, failure {
                AudioRelay(read = { count }, write = { _, _, _ -> fail("invalid audio"); 0 },
                    active = { true }, routeSafe = { true }, gain = { 1f }, idle = {}).run()
            })
        }
    }

    @Test fun negativeOrImpossibleWriteCountsFail() {
        for (count in listOf(-6, 3)) {
            assertEquals(LiveListenError.WRITE_FAILED, failure {
                AudioRelay(read = { 2 }, write = { _, _, _ -> count }, active = { true },
                    routeSafe = { true }, gain = { 1f }, idle = {}).run()
            })
        }
    }

    private fun failure(block: () -> Unit): LiveListenError {
        try { block() } catch (e: AudioRelayException) { return e.reason }
        throw AssertionError("Expected audio failure")
    }
}
