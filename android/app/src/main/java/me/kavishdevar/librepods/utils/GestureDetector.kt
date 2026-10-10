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

import java.io.Closeable
import me.kavishdevar.librepods.services.HeadTrackingSession
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.BuildConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GestureDetector(
    private val airPodsService: AirPodsService
) {
    companion object {
        private const val TAG = "GestureDetector"

        private const val IMMEDIATE_FEEDBACK_THRESHOLD = 600
        private const val DIRECTION_CHANGE_SENSITIVITY = 150

        private const val FAST_MOVEMENT_THRESHOLD = 300.0
        private const val MIN_REQUIRED_EXTREMES = 3
        private const val MAX_REQUIRED_EXTREMES = 4

        private const val MAX_VALID_ORIENTATION_VALUE = 6000
    }

    private val audioResource = lazy { GestureFeedback(airPodsService.applicationContext) }
    private val audio get() = audioResource.value
    private data class DirectionalFeedback(val vertical: Boolean, val value: Double)
    private var feedbackSession: ConflatedCallbackSession<DirectionalFeedback>? = null
    private var activeSessionToken: Any? = null
    private var headTrackingLease: Closeable? = null
    private var released = false

    private val horizontalBuffer = GestureSampleWindow(100)
    private val verticalBuffer = GestureSampleWindow(100)

    private val horizontalAvgBuffer = GestureSampleWindow(3)
    private val verticalAvgBuffer = GestureSampleWindow(3)

    private var prevHorizontal: Double = 0.0
    private var prevVertical: Double = 0.0

    // Scoring only uses the latest three or four extrema. Keep them in arrival order,
    // independent of the sample buffer's index, which stops increasing after 100 samples.
    private val horizontalExtremes = RecentGestureExtremes(MAX_REQUIRED_EXTREMES)
    private val verticalExtremes = RecentGestureExtremes(MAX_REQUIRED_EXTREMES)

    private var lastPeakTime: Long = 0
    private val peakIntervals = GestureSampleWindow(5)

    private val movementSpeedIntervals = GestureSampleWindow(5)

    private val peakThreshold = 400
    private val rhythmConsistencyThreshold = 0.5

    private var horizontalIncreasing: Boolean? = null
    private var verticalIncreasing: Boolean? = null

    private val minConfidenceThreshold = 0.7

    @Volatile private var isRunning = false
    private var detectionJob: Job? = null
    private var detectionTasks: Channel<Pair<Long, () -> Unit>>? = null
    private var detectionSchedule: SampleDetectionSchedule? = null
    private var resultPending = false
    private var gestureDetectedCallback: ((Boolean) -> Unit)? = null
    private var detectionStoppedCallback: (() -> Unit)? = null

    init {
        while (horizontalAvgBuffer.size < 3) horizontalAvgBuffer.add(0.0)
        while (verticalAvgBuffer.size < 3) verticalAvgBuffer.add(0.0)
    }

    @Synchronized
    fun startDetection(onGestureDetected: (Boolean) -> Unit): Closeable? {
        return startDetection({}, onGestureDetected)
    }

    @Synchronized
    fun startDetection(onStopped: () -> Unit, onGestureDetected: (Boolean) -> Unit): Closeable? {
        if (isRunning || released) return null
        val lease = airPodsService.acquireHeadTracking(HeadTrackingSession.Consumer.GESTURE) ?: return null
        headTrackingLease = lease
        Log.d(TAG, "Starting gesture detection...")
        isRunning = true
        gestureDetectedCallback = onGestureDetected
        detectionStoppedCallback = onStopped
        val sessionToken = Any()
        activeSessionToken = sessionToken
        try {
            audioResource.value // Load sounds only when head gestures are actually used.
            val session = ConflatedCallbackSession<DirectionalFeedback>(Dispatchers.Main) { feedback ->
                synchronized(this) {
                    if (activeSessionToken === sessionToken && isRunning) {
                        audio.playDirectional(feedback.vertical, feedback.value)
                    }
                }
            }
            feedbackSession = session

            clearData()

            prevHorizontal = 0.0
            prevVertical = 0.0

            resultPending = false
            val tasks = Channel<Pair<Long, () -> Unit>>(Channel.CONFLATED)
            detectionTasks = tasks
            // One worker waits between bursts; continuous samples do not create per-check jobs.
            detectionJob = session.scope.launch(Dispatchers.Default) {
                for ((milliseconds, action) in tasks) {
                    delay(milliseconds)
                    synchronized(this@GestureDetector) {
                        if (activeSessionToken === sessionToken && isRunning) action()
                    }
                }
            }
            detectionSchedule = SampleDetectionSchedule(50,
                schedule = { milliseconds, action ->
                    check(tasks.trySend(milliseconds to action).isSuccess)
                    val cancel: () -> Unit = { tasks.cancel() }
                    cancel
                }, consume = {
                    val gesture = detectGestures()
                    if (gesture != null && !resultPending) {
                        resultPending = true
                        session.scope.launch(Dispatchers.Main) {
                            synchronized(this@GestureDetector) {
                                if (activeSessionToken === sessionToken && isRunning) {
                                    try {
                                        audio.playConfirmation(gesture)
                                        gestureDetectedCallback?.invoke(gesture)
                                    } catch (error: Exception) { Log.w(TAG, "Head gesture action failed", error) }
                                    finally { if (activeSessionToken === sessionToken) stopDetection() }
                                }
                            }
                        }
                    }
                })
            return Closeable {
                synchronized(this) { if (activeSessionToken === sessionToken) stopDetection() }
            }
        } catch (error: Exception) {
            stopDetection()
            Log.w(TAG, "Could not start gesture detection", error)
            return null
        }
    }
    @Synchronized
    fun stopDetection() {
        if (!isRunning) return
        val stopped = detectionStoppedCallback
        detectionStoppedCallback = null

        Log.d(TAG, "Stopping gesture detection")
        isRunning = false
        activeSessionToken = null
        detectionSchedule?.close()
        detectionSchedule = null
        detectionTasks?.cancel()
        detectionTasks = null
        resultPending = false
        feedbackSession?.close()
        feedbackSession = null
        if (audioResource.isInitialized()) audio.stopDirectional()

        headTrackingLease?.close()
        headTrackingLease = null

        detectionJob?.cancel()
        detectionJob = null
        gestureDetectedCallback = null
        try { stopped?.invoke() }
        catch (error: Exception) { Log.w(TAG, "Head gesture stop callback failed", error) }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        stopDetection()
        if (audioResource.isInitialized()) audio.release()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Synchronized
    fun processHeadOrientation(horizontal: Int, vertical: Int) {
        if (!isRunning) return

        if (abs(horizontal) > MAX_VALID_ORIENTATION_VALUE || abs(vertical) > MAX_VALID_ORIENTATION_VALUE) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring likely calibration data: h=$horizontal, v=$vertical")
            return
        }

        val horizontalDelta = horizontal - prevHorizontal
        val verticalDelta = vertical - prevVertical

        val significantHorizontal = abs(horizontalDelta) > IMMEDIATE_FEEDBACK_THRESHOLD
        val significantVertical = abs(verticalDelta) > IMMEDIATE_FEEDBACK_THRESHOLD

        if (significantHorizontal && (!significantVertical || abs(horizontalDelta) > abs(verticalDelta))) {
            feedbackSession?.offer(DirectionalFeedback(vertical = false, value = horizontalDelta))
            if (BuildConfig.DEBUG) Log.d(TAG, "Significant HORIZONTAL movement: $horizontalDelta")
        }
        else if (significantVertical) {
            feedbackSession?.offer(DirectionalFeedback(vertical = true, value = verticalDelta))
            if (BuildConfig.DEBUG) Log.d(TAG, "Significant VERTICAL movement: $verticalDelta")
        }

        prevHorizontal = horizontal.toDouble()
        prevVertical = vertical.toDouble()

        val smoothHorizontal = applySmoothing(horizontal.toDouble(), horizontalAvgBuffer)
        val smoothVertical = applySmoothing(vertical.toDouble(), verticalAvgBuffer)

        horizontalBuffer.add(smoothHorizontal)

        verticalBuffer.add(smoothVertical)

        detectPeaksAndTroughs()
        if (!resultPending) detectionSchedule?.offer()
    }

    private fun applySmoothing(newValue: Double, buffer: GestureSampleWindow): Double {
        buffer.add(newValue)
        return buffer.averageLast()
    }

    private fun detectPeaksAndTroughs() {
        if (horizontalBuffer.size < 4 || verticalBuffer.size < 4) return

        val hVariance = horizontalBuffer.varianceLast(4)
        val vVariance = verticalBuffer.varianceLast(4)

        processDirectionChanges(
            horizontalBuffer,
            horizontalIncreasing,
            hVariance,
            horizontalExtremes
        )?.let { horizontalIncreasing = it }

        processDirectionChanges(
            verticalBuffer,
            verticalIncreasing,
            vVariance,
            verticalExtremes
        )?.let { verticalIncreasing = it }
    }

    private fun processDirectionChanges(
        buffer: GestureSampleWindow,
        isIncreasing: Boolean?,
        variance: Double,
        extremes: RecentGestureExtremes
    ): Boolean? {
        if (buffer.size < 2) return isIncreasing

        val current = buffer[buffer.size - 1]
        val prev = buffer[buffer.size - 2]
        var increasing = isIncreasing ?: (current > prev)

        val dynamicThreshold = max(50.0, min(DIRECTION_CHANGE_SENSITIVITY.toDouble(), variance / 3))

        val now = SystemClock.elapsedRealtime()

        val directionChanged = if (increasing) current < prev - dynamicThreshold
            else current > prev + dynamicThreshold
        if (directionChanged) {
            if (abs(prev) > peakThreshold) {
                extremes.add(prev)
                if (lastPeakTime > 0) {
                    val timeDiff = now - lastPeakTime
                    peakIntervals.add(timeDiff / 1000.0)
                    movementSpeedIntervals.add(timeDiff.toDouble())
                }
                lastPeakTime = now
            }
            increasing = !increasing
        }

        return increasing
    }

    private fun calculateRhythmConsistency(): Double {
        if (peakIntervals.size < 2) return 0.0

        val meanInterval = peakIntervals.averageLast()
        if (meanInterval == 0.0) return 0.0

        val consistency = 1.0 - min(1.0, peakIntervals.normalizedVariance(meanInterval) / rhythmConsistencyThreshold)
        return max(0.0, consistency)
    }


    private fun calculateConfidenceScore(recent: List<Double>, isVertical: Boolean): Double {
        val avgAmplitude = recent.sumOf { abs(it) } / recent.size
        val amplitudeFactor = min(1.0, avgAmplitude / 600)

        val rhythmFactor = calculateRhythmConsistency()

        val alternating = (1 until recent.size).all { (recent[it] > 0) != (recent[it - 1] > 0) }
        val alternationFactor = if (alternating) 1.0 else 0.5

        val otherAxis = if (isVertical) horizontalBuffer else verticalBuffer
        val otherAmplitude = otherAxis.absoluteAverageLast(recent.size * 2)
        val isolationFactor = min(1.0, avgAmplitude / (otherAmplitude + 0.1) * 1.2)

        return (
            amplitudeFactor * 0.4 +
            rhythmFactor * 0.2 +
            alternationFactor * 0.2 +
            isolationFactor * 0.2
        )
    }

    private fun getRequiredExtremes(): Int {
        if (movementSpeedIntervals.isEmpty()) return MIN_REQUIRED_EXTREMES

        val avgInterval = movementSpeedIntervals.averageLast()
        if (BuildConfig.DEBUG) Log.d(TAG, "Average movement interval: $avgInterval ms")

        return if (avgInterval < FAST_MOVEMENT_THRESHOLD) {
            MAX_REQUIRED_EXTREMES
        } else {
            MIN_REQUIRED_EXTREMES
        }
    }

    @Synchronized
    private fun detectGestures(): Boolean? {
        val requiredExtremes = getRequiredExtremes()
        if (BuildConfig.DEBUG) Log.d(TAG, "Current required extremes: $requiredExtremes")

        return when {
            matchesGesture(verticalExtremes, requiredExtremes, isVertical = true) -> true
            matchesGesture(horizontalExtremes, requiredExtremes, isVertical = false) -> false
            else -> null
        }
    }

    private fun matchesGesture(extremes: RecentGestureExtremes, required: Int, isVertical: Boolean): Boolean {
        if (extremes.size < required) return false
        val recent = extremes.recent(required)
        val confidence = calculateConfidenceScore(recent, isVertical)
        if (BuildConfig.DEBUG) {
            val axis = if (isVertical) "Vertical" else "Horizontal"
            Log.d(TAG, "$axis motion confidence: $confidence (need $minConfidenceThreshold)")
        }
        if (!(confidence >= minConfidenceThreshold)) return false
        Log.d(TAG, "\"${if (isVertical) "Yes" else "No"}\" Gesture Detected (confidence: $confidence, extremes: ${recent.size}/$required)")
        return true
    }

    @Synchronized
    private fun clearData() {
        horizontalBuffer.clear()
        verticalBuffer.clear()
        horizontalAvgBuffer.clear()
        verticalAvgBuffer.clear()
        repeat(3) {
            horizontalAvgBuffer.add(0.0)
            verticalAvgBuffer.add(0.0)
        }
        horizontalExtremes.clear()
        verticalExtremes.clear()
        peakIntervals.clear()
        movementSpeedIntervals.clear()
        horizontalIncreasing = null
        verticalIncreasing = null
        lastPeakTime = 0
    }

}
