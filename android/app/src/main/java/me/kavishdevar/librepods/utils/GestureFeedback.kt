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
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

class GestureFeedback(context: Context) {

    private companion object {
        const val TAG = "GestureFeedback"
        const val MIN_TIME_BETWEEN_SOUNDS = 150L
        const val MIN_TIME_BETWEEN_DIRECTION = 200L
    }

    private val soundsLoaded = AtomicBoolean(false)
    private var released = false
    private val loadedSoundIds = mutableSetOf<Int>()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
        )
        .build()


    private var soundId = 0
    private var confirmYesId = 0
    private var confirmNoId = 0

    private class AxisFeedback {
        var lastSoundTime = 0L
        var lastPositiveTime = 0L
        var lastNegativeTime = 0L
        var streamId = 0
    }

    private val horizontal = AxisFeedback()
    private val vertical = AxisFeedback()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(this) {
                if (!released && status == 0) {
                    loadedSoundIds.add(sampleId)
                    if (loadedSoundIds.size == 3 && soundsLoaded.compareAndSet(false, true)) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Sounds loaded")
                        soundPool.play(soundId, 0.0f, 0.0f, 1, 0, 1.0f)
                    }
                }
            }
        }
        soundId = soundPool.load(context, R.raw.blip_no, 1)
        confirmYesId = soundPool.load(context, R.raw.confirm_yes, 1)
        confirmNoId = soundPool.load(context, R.raw.confirm_no, 1)
    }

    @Synchronized
    fun playDirectional(isVertical: Boolean, value: Double) {
        if (released || !soundsLoaded.get()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Sounds not yet loaded, skipping playback")
            return
        }

        val now = SystemClock.uptimeMillis()

        val axis = if (isVertical) vertical else horizontal
        val positive = value > 0
        if (now - axis.lastSoundTime < MIN_TIME_BETWEEN_SOUNDS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping ${if (isVertical) "vertical" else "horizontal"} sound due to general debounce")
            return
        }
        val lastDirectionTime = if (positive) axis.lastPositiveTime else axis.lastNegativeTime
        if (now - lastDirectionTime < MIN_TIME_BETWEEN_DIRECTION) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping ${directionName(isVertical, positive)} sound due to direction debounce")
            return
        }

        if (axis.streamId > 0) soundPool.stop(axis.streamId)
        val leftVolume = if (isVertical || !positive) 1.0f else 0.0f
        val rightVolume = if (isVertical || positive) 1.0f else 0.0f
        axis.streamId = soundPool.play(soundId, leftVolume, rightVolume, 1, 0, 1.0f)
        if (BuildConfig.DEBUG) Log.d(TAG, "Playing ${if (isVertical) "VERTICAL" else "HORIZONTAL"} sound: ${directionName(isVertical, positive)} - streamID=${axis.streamId}")
        axis.lastSoundTime = now
        if (positive) axis.lastPositiveTime = now else axis.lastNegativeTime = now
    }

    private fun directionName(isVertical: Boolean, positive: Boolean): String =
        if (isVertical) { if (positive) "UP" else "DOWN" }
        else { if (positive) "RIGHT" else "LEFT" }

    private fun stopAxis(axis: AxisFeedback) {
        if (axis.streamId > 0) {
            soundPool.stop(axis.streamId)
            axis.streamId = 0
        }
    }

    @Synchronized
    fun stopDirectional() {
        if (released) return
        stopAxis(horizontal)
        stopAxis(vertical)
    }

    @Synchronized
    fun playConfirmation(isYes: Boolean) {
        if (released) return
        stopDirectional()

        val soundId = if (isYes) confirmYesId else confirmNoId
        if (soundId != 0 && soundsLoaded.get()) {
            val streamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
            if (BuildConfig.DEBUG) Log.d(TAG, "Playing ${if (isYes) "YES" else "NO"} confirmation - streamID=$streamId")
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        soundsLoaded.set(false)
        soundPool.setOnLoadCompleteListener(null)
        soundPool.release()
        loadedSoundIds.clear()
        horizontal.streamId = 0
        vertical.streamId = 0
    }
}
