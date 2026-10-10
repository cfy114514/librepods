package me.kavishdevar.librepods.utils

/** Wait for root approval before rechecking camera ownership and allowing a key. */
internal class CameraRemoteCommand(
    private val launch: () -> Process = {
        ProcessBuilder("su", "-c", "echo LibrePodsCameraReady; read request && [ \"${'$'}request\" = shoot ] && input keyevent 27")
            .redirectErrorStream(true).start()
    },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idle: () -> Unit = { Thread.sleep(10) }
) {
    fun capture(allowed: () -> Boolean): Boolean {
        if (!allowed()) return false
        val process = launch()
        try {
            val buffer = ByteArray(256)
            val line = StringBuilder()
            val deadline = nowMillis() + 3000
            var ready = false
            while (!ready && allowed() && !Thread.currentThread().isInterrupted && nowMillis() < deadline) {
                val available = process.inputStream.available()
                if (available > 0) {
                    val count = process.inputStream.read(buffer, 0, minOf(available, buffer.size))
                    if (count < 0) return false
                    for (index in 0 until count) {
                        val value = buffer[index].toInt().toChar()
                        if (value == '\n') {
                            if (line.toString().trim() == "LibrePodsCameraReady") ready = true
                            line.setLength(0)
                        } else {
                            if (line.length == 256) line.setLength(0)
                            line.append(value)
                        }
                    }
                } else if (!process.isAlive) return false
                else idle()
            }
            if (!ready || !allowed() || Thread.currentThread().isInterrupted) return false
            process.outputStream.use { it.write("shoot\n".toByteArray(Charsets.US_ASCII)); it.flush() }
            val completionDeadline = nowMillis() + 2000
            while (process.isAlive && allowed() && !Thread.currentThread().isInterrupted && nowMillis() < completionDeadline) idle()
            return !process.isAlive && process.exitValue() == 0
        } finally {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            process.destroy()
        }
    }
}
