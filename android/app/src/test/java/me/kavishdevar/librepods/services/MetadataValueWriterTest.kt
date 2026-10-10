package me.kavishdevar.librepods.services

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MetadataValueWriterTest {
    @Test fun failedFieldDoesNotSkipIndependentFieldsAndOnlyFailureRetries() {
        val writer = MetadataValueWriter<String>(setOf(1, 2, 3))
        val values = linkedMapOf(1 to "icon", 2 to "model", 3 to "threshold")
        val calls = mutableListOf<Int>()
        assertFalse(writer.write("peer", values, { true }) { k, _ -> calls += k; k != 1 })
        assertEquals(listOf(1, 2, 3), calls)
        calls.clear()
        assertTrue(writer.write("peer", values, { true }) { k, _ -> calls += k; true })
        assertEquals(listOf(1), calls)
    }
    @Test fun repeatedBatteryAndInformationWritesShareOnlyAcceptedValues() {
        val writer = MetadataValueWriter<String>((1..17).toSet())
        var calls = 0
        val information = (1..11).associateWith { "information$it" }
        val battery = (12..17).associateWith { "battery$it" }
        repeat(10_000) {
            writer.write("peer", information, { true }) { _, _ -> calls++; true }
            writer.write("peer", battery, { true }) { _, _ -> calls++; true }
        }
        assertEquals(17, calls)
        writer.write("peer", mapOf(12 to "changed"), { true }) { _, _ -> calls++; true }
        assertEquals(18, calls)
    }
    @Test fun peerOrSessionChangeRewritesValuesAndReturningPeerDoesNotReviveCache() {
        val writer = MetadataValueWriter<String>(setOf(1))
        var calls = 0
        for (scope in listOf("peerA:1", "peerA:2", "peerB:1", "peerA:1"))
            writer.write(scope, mapOf(1 to "same"), { true }) { _, _ -> calls++; true }
        assertEquals(4, calls)
    }
    @Test fun invalidationDuringCallStopsRemainingFieldsAndDoesNotAcceptLateSuccess() {
        val writer = MetadataValueWriter<String>(setOf(1, 2))
        var current = true
        val calls = mutableListOf<Int>()
        assertFalse(writer.write("peer", linkedMapOf(1 to "first", 2 to "second"), { current }) { k, _ -> calls += k; current = false; true })
        assertEquals(listOf(1), calls)
        current = true
        writer.write("peer", mapOf(1 to "first"), { current }) { k, _ -> calls += k; true }
        assertEquals(listOf(1, 1), calls)
    }
    @Test fun exceptionsDoNotAcceptFieldOrPreventFollowingFields() {
        val writer = MetadataValueWriter<String>(setOf(1, 2))
        val calls = mutableListOf<Int>()
        var errors = 0
        assertFalse(writer.write("peer", linkedMapOf(1 to "first", 2 to "second"), { true }, { errors++ }) { k, _ -> calls += k; if (k == 1) error("denied"); true })
        assertEquals(listOf(1, 2), calls); assertEquals(1, errors)
    }
    @Test fun invalidOrCancelledRequestDoesNotPopulateAcceptedCache() {
        val writer = MetadataValueWriter<String>(setOf(1))
        var calls = 0
        assertFalse(writer.write("peer", mapOf(1 to "value"), { false }) { _, _ -> calls++; true })
        assertEquals(0, calls)
        try { writer.write("peer", mapOf(1 to "value"), { true }) { _, _ -> throw CancellationException() }; fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        writer.write("peer", mapOf(1 to "value"), { true }) { _, _ -> calls++; true }
        assertEquals(1, calls)
    }
    @Test fun unknownKeyCannotGrowAcceptedState() {
        val writer = MetadataValueWriter<String>(setOf(1))
        try { writer.write("peer", mapOf(2 to "unexpected"), { true }) { _, _ -> true }; fail("Unknown key accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
