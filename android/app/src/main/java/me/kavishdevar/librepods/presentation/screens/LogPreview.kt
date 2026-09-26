package me.kavishdevar.librepods.presentation.screens

import java.io.File
import java.io.RandomAccessFile

internal data class LogPreview(
    val lines: List<String> = emptyList(),
    val isTruncated: Boolean = false
)

// The full log remains on disk for saving and sharing. Bound both the amount read
// and the size of individual text layouts, even for logs with very long lines.
internal fun readLogPreview(file: File): LogPreview {
    val maxBytes = 256 * 1024
    val maxLines = 2000
    val maxLineLength = 4096

    return RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        val start = (length - maxBytes).coerceAtLeast(0)
        val startsAtLineBoundary = if (start == 0L) {
            true
        } else {
            input.seek(start - 1)
            input.read() == '\n'.code
        }
        input.seek(start)
        val bytes = ByteArray((length - start).toInt())
        input.readFully(bytes)
        val text = bytes.toString(Charsets.UTF_8)
        val firstNewline = text.indexOf('\n')
        val content = if (!startsAtLineBoundary && firstNewline >= 0) {
            text.substring(firstNewline + 1)
        } else {
            text
        }

        var truncated = start > 0
        val lines = ArrayDeque<String>()
        content.lineSequence().forEach { line ->
            if (lines.size == maxLines) {
                lines.removeFirst()
                truncated = true
            }
            lines.addLast(if (line.length > maxLineLength) {
                truncated = true
                line.take(maxLineLength) + "…"
            } else {
                line
            })
        }
        LogPreview(lines.toList(), truncated)
    }
}
