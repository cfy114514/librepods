package me.kavishdevar.librepods.utils

import java.io.BufferedReader
import java.io.Writer

/** Streams complete diagnostics without retaining the session in memory. */
internal fun streamDiagnosticLog(
    reader: BufferedReader,
    output: Writer,
    shouldContinue: () -> Boolean,
    connectionDetected: () -> Unit
) {
    var notified = false
    while (shouldContinue()) {
        val line = reader.readLine() ?: break
        val marker = line.contains("<LogCollector:")
        if (marker) output.write("\n=============\n")
        output.write(line)
        output.write("\n")
        if (marker) output.write("=============\n\n")
        if (!notified && (
                line.contains("<LogCollector:Complete:Success>") ||
                    line.contains("<LogCollector:Complete:Failed>") ||
                    (line.contains("AirPodsService") && (
                        line.contains("Connected to device") || line.contains("Connection failed")
                    )) ||
                    (line.contains("BluetoothService") && line.contains("CONNECTION_STATE_CONNECTED"))
                )) {
            notified = true
            connectionDetected()
        }
    }
}
