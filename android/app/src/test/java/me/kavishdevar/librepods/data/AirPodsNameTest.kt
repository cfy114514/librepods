package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class AirPodsNameTest {
    @Test fun emptyDraftsAreNotNames() {
        assertEquals(AirPodsNameProblem.EMPTY, airPodsNameProblem(""))
        assertEquals(AirPodsNameProblem.EMPTY, airPodsNameProblem(" \t"))
    }
    @Test fun asciiLengthCannotWrapTheWireByte() {
        assertNull(airPodsNameProblem("a".repeat(255)))
        assertEquals(AirPodsNameProblem.TOO_LONG, airPodsNameProblem("a".repeat(256)))
    }
    @Test fun chineseLimitUsesEncodedBytes() {
        assertNull(airPodsNameProblem("名".repeat(85)))
        assertEquals(AirPodsNameProblem.TOO_LONG, airPodsNameProblem("名".repeat(86)))
    }
    @Test fun validSurrogatePairsCountAsUtf8WithoutSplittingEmoji() {
        assertNull(airPodsNameProblem("🎧".repeat(63)))
        assertEquals(AirPodsNameProblem.TOO_LONG, airPodsNameProblem("🎧".repeat(64)))
        assertNull(airPodsNameProblem("我的 AirPods 🎧"))
    }
    @Test fun malformedUnicodeAndNulCannotBecomeDifferentWireNames() {
        for (name in listOf("x\u0000", "x\uD800", "x\uDC00", "\uD800x"))
            assertEquals(AirPodsNameProblem.INVALID, airPodsNameProblem(name))
    }
}
