package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class HearingSettingsEncodingTest {
    private val left = FloatArray(8) { it + 1f }
    private val right = FloatArray(8) { -it - 1f }
    private val hearing = HearingAidSettings(
        left, right, -0.5f, 0.5f, 0.25f, -0.25f, true, false,
        0.75f, 0.25f, 0f, 1f, 0.125f
    )
    private val transparency = TransparencySettings(
        true, left, right, -0.5f, 0.5f, 0.25f, -0.25f, true, false,
        0.75f, 0.25f, 0f, 1f
    )

    // Fixed little-endian vectors from the original encoder's field order.
    private val leftPayload =
        "0000803f0000004000004040000080400000a0400000c0400000e04000000041" +
        "000000bf0000803e0000803f0000403f"
    private val rightPayload =
        "000080bf000000c0000040c0000080c00000a0c00000c0c00000e0c0000000c1" +
        "0000003f000080be000000000000803e"

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hearingAidMatchesLegacyVectorAndPreservesHeaderTailAndSource() {
        val source = ByteArray(106)
        hex("02026000").copyInto(source)
        hex("aa55").copyInto(source, 104)
        val original = source.copyOf()

        val encoded = encodeHearingAidSettings(source, hearing)

        assertArrayEquals(hex("02026400" + leftPayload + rightPayload + "0000003eaa55"), encoded)
        assertArrayEquals(original, source)
    }

    @Test fun shortHearingAidPacketIsNotWritten() {
        assertNull(encodeHearingAidSettings(ByteArray(103), hearing))
    }

    @Test fun transparencyMatchesLegacyVectorWithoutOptionalField() {
        val encoded = encodeTransparencySettings(transparency)
        assertEquals(100, encoded.size)
        assertArrayEquals(hex("0000803f" + leftPayload + rightPayload), encoded)
    }

    @Test fun transparencyPreservesOptionalOwnVoiceFieldAndDisabledFlag() {
        val encoded = encodeTransparencySettings(transparency.copy(enabled = false, ownVoiceAmplification = 0.125f))
        assertEquals(104, encoded.size)
        assertArrayEquals(hex("00000000" + leftPayload + rightPayload + "0000003e"), encoded)
    }

    @Test fun roundTripRetainsIndependentLeftAndRightEqualizers() {
        val encoded = encodeHearingAidSettings(ByteArray(104), hearing)!!
        val parsed = parseHearingAidSettingsResponse(encoded)!!
        assertArrayEquals(left, parsed.leftEQ, 0f)
        assertArrayEquals(right, parsed.rightEQ, 0f)
        assertEquals(-0.5f, parsed.leftAmplification, 0f)
        assertEquals(0.5f, parsed.rightAmplification, 0f)
        assertEquals(0.125f, parsed.ownVoiceAmplification, 0f)
    }
}
