/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class LogCollector(private val context: Context) {
    private val collectionLock = Any()
    @Volatile private var activeCollection: Any? = null
    private var logProcess: Process? = null

    suspend fun openXposedSettings(context: Context) {
        withContext(Dispatchers.IO) {
            val command = if (android.os.Build.VERSION.SDK_INT >= 29) {
                "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
            } else {
                "am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://5776733 android"
            }

            executeRootCommand(command)
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            executeRootCommand("logcat -c")
        }
    }

    suspend fun killBluetoothService() {
        withContext(Dispatchers.IO) {
            executeRootCommand("killall com.android.bluetooth")
        }
    }

    private suspend fun getBluetoothUID(): String? {
        val pkgs = listOf("com.android.bluetooth", "com.google.android.bluetooth")
        for (pkg in pkgs) {
            val uid = executeRootCommand(
                "dumpsys package $pkg | grep -m 1 \"uid=\" | sed -E 's/.*uid=([0-9]+).*/\\1/'"
            ).trim()
            if (uid.isNotEmpty() && uid.all(Char::isDigit)) return uid
        }
        return null
    }

    private suspend fun getPackageUIDs(): Pair<String?, String?> {
        return withContext(Dispatchers.IO) {
            val btUid = getBluetoothUID()
            val appUid = executeRootCommand("dumpsys package me.kavishdevar.librepods | grep -m 1 \"uid=\" | sed -E 's/.*uid=([0-9]+).*/\\1/'")
                .trim()
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

            Pair(btUid, appUid)
        }
    }

    suspend fun startLogCollection(fileName: String, connectionDetectedCallback: () -> Unit): File {
        return withContext(Dispatchers.IO) {
            val session = Any()
            synchronized(collectionLock) {
                check(activeCollection == null) { "Log collection is already running" }
                activeCollection = session
            }
            var process: Process? = null
            try {
                val (btUid, appUid) = getPackageUIDs()

                val uidFilter = buildString {
                    if (!btUid.isNullOrEmpty() && !appUid.isNullOrEmpty()) {
                        append("$btUid,$appUid")
                    } else if (!btUid.isNullOrEmpty()) {
                        append(btUid)
                    } else if (!appUid.isNullOrEmpty()) {
                        append(appUid)
                    }
                }

                val command = if (uidFilter.isNotEmpty()) {
                    "logcat --uid=$uidFilter -v threadtime"
                } else {
                    "logcat -v threadtime"
                }

                val logsDir = File(context.filesDir, "logs")
                check(logsDir.isDirectory || logsDir.mkdirs()) { "Cannot create log directory" }
                // A stopped reader may still be finishing as a new session starts.
                // Never let two sessions truncate or write the same log file.
                val file = File.createTempFile(fileName.removeSuffix(".txt") + "_", ".txt", logsDir)
                file.bufferedWriter().use { output ->
                    if (activeCollection === session) {
                        val startedProcess = ProcessBuilder("su", "-c", command)
                            .redirectErrorStream(true).start()
                        process = startedProcess
                        synchronized(collectionLock) {
                            if (activeCollection === session) logProcess = startedProcess
                            else startedProcess.destroy()
                        }
                        // Cancellation must close the pipe even while readLine() is blocked.
                        val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                startedProcess.destroy()
                            }
                        }
                        try {
                            startedProcess.inputStream.bufferedReader().use { reader ->
                                streamDiagnosticLog(reader, output, { activeCollection === session }, connectionDetectedCallback)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (activeCollection === session) output.append("Error collecting logs: ${e.message}\n")
                        } finally {
                            cancellationWatcher.cancel()
                            startedProcess.destroy()
                        }
                    }
                }
                file
            } finally {
                process?.destroy()
                synchronized(collectionLock) {
                    if (activeCollection === session) {
                        activeCollection = null
                        logProcess = null
                    }
                }
            }
        }
    }

    fun stopLogCollection() {
        val process = synchronized(collectionLock) {
            activeCollection = null
            logProcess.also { logProcess = null }
        }
        process?.destroy()
    }

    suspend fun addLogMarker(markerType: LogMarkerType, details: String = "") {
        withContext(Dispatchers.IO) {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())

            val marker = when (markerType) {
                LogMarkerType.START -> "<LogCollector:Start> [$timestamp] Beginning connection test"
                LogMarkerType.SUCCESS -> "<LogCollector:Complete:Success> [$timestamp] Connection test completed successfully"
                LogMarkerType.FAILURE -> "<LogCollector:Complete:Failed> [$timestamp] Connection test failed"
                LogMarkerType.CUSTOM -> "<LogCollector:Custom:$details> [$timestamp]"
            }

            val command = "log -t AirPodsService \"$marker\""
            executeRootCommand(command)
        }
    }

    enum class LogMarkerType {
        START,
        SUCCESS,
        FAILURE,
        CUSTOM
    }

    private suspend fun executeRootCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                val startedProcess = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                process = startedProcess
                val deadline = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { delay(15_000) } finally { startedProcess.destroy() }
                }
                try {
                    startedProcess.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    deadline.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            } finally {
                process?.destroy()
            }
        }
    }
}
