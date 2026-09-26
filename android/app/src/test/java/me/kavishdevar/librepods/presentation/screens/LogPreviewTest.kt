package me.kavishdevar.librepods.presentation.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogPreviewTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun smallUtf8LogPreservesLines() {
        val file = temporaryFolder.newFile()
        file.writeText("连接成功\r\nBattery: 80%\r\n")

        val preview = readLogPreview(file)

        assertEquals(listOf("连接成功", "Battery: 80%", ""), preview.lines)
        assertFalse(preview.isTruncated)
    }

    @Test fun largeLogKeepsRecentCompleteLinesWithoutChangingFullFile() {
        val file = temporaryFolder.newFile()
        val original = (1..20_000).joinToString("\n") { "事件 $it: connected" }
        file.writeText(original)

        val preview = readLogPreview(file)

        assertTrue(preview.isTruncated)
        assertEquals(2000, preview.lines.size)
        assertEquals("事件 20000: connected", preview.lines.last())
        assertTrue(preview.lines.all { it.startsWith("事件 ") })
        assertEquals(original, file.readText())
    }

    @Test fun excessiveLineCountIsBoundedEvenForSmallFiles() {
        val file = temporaryFolder.newFile()
        file.writeText((1..3000).joinToString("\n"))

        val preview = readLogPreview(file)

        assertEquals(2000, preview.lines.size)
        assertEquals("1001", preview.lines.first())
        assertEquals("3000", preview.lines.last())
        assertTrue(preview.isTruncated)
    }

    @Test fun singleHugeLineCannotCreateUnboundedTextLayout() {
        val file = temporaryFolder.newFile()
        file.writeText("x".repeat(1024 * 1024))

        val preview = readLogPreview(file)

        assertEquals(1, preview.lines.size)
        assertEquals(4097, preview.lines.single().length)
        assertTrue(preview.isTruncated)
        assertEquals(1024 * 1024L, file.length())
    }

    @Test fun emptyLogCanBePreviewed() {
        val preview = readLogPreview(temporaryFolder.newFile())

        assertEquals(listOf(""), preview.lines)
        assertFalse(preview.isTruncated)
    }
}
