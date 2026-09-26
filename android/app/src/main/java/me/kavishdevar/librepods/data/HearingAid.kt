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

package me.kavishdevar.librepods.data

import android.util.Log
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.ATTHandles
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "HearingAidUtils"

data class HearingAidSettings(
    val leftEQ: FloatArray,
    val rightEQ: FloatArray,
    val leftAmplification: Float,
    val rightAmplification: Float,
    val leftTone: Float,
    val rightTone: Float,
    val leftConversationBoost: Boolean,
    val rightConversationBoost: Boolean,
    val leftAmbientNoiseReduction: Float,
    val rightAmbientNoiseReduction: Float,
    val netAmplification: Float,
    val balance: Float,
    val ownVoiceAmplification: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HearingAidSettings

        if (leftAmplification != other.leftAmplification) return false
        if (rightAmplification != other.rightAmplification) return false
        if (leftTone != other.leftTone) return false
        if (rightTone != other.rightTone) return false
        if (leftConversationBoost != other.leftConversationBoost) return false
        if (rightConversationBoost != other.rightConversationBoost) return false
        if (leftAmbientNoiseReduction != other.leftAmbientNoiseReduction) return false
        if (rightAmbientNoiseReduction != other.rightAmbientNoiseReduction) return false
        if (!leftEQ.contentEquals(other.leftEQ)) return false
        if (!rightEQ.contentEquals(other.rightEQ)) return false
        if (ownVoiceAmplification != other.ownVoiceAmplification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = leftAmplification.hashCode()
        result = 31 * result + rightAmplification.hashCode()
        result = 31 * result + leftTone.hashCode()
        result = 31 * result + rightTone.hashCode()
        result = 31 * result + leftConversationBoost.hashCode()
        result = 31 * result + rightConversationBoost.hashCode()
        result = 31 * result + leftAmbientNoiseReduction.hashCode()
        result = 31 * result + rightAmbientNoiseReduction.hashCode()
        result = 31 * result + leftEQ.contentHashCode()
        result = 31 * result + rightEQ.contentHashCode()
        result = 31 * result + ownVoiceAmplification.hashCode()
        return result
    }
}

fun parseHearingAidSettingsResponse(data: ByteArray): HearingAidSettings? {
    if (data.size < 104) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    buffer.get() // skip 0x02
    buffer.get() // skip 0x02
    buffer.getShort() // skip 0x60 0x00

    val leftEQ = FloatArray(8)
    for (i in 0..7) {
        leftEQ[i] = buffer.float
    }
    val leftAmplification = buffer.float
    val leftTone = buffer.float
    val leftConvFloat = buffer.float
    val leftConversationBoost = leftConvFloat > 0.5f
    val leftAmbientNoiseReduction = buffer.float

    val rightEQ = FloatArray(8)
    for (i in 0..7) {
        rightEQ[i] = buffer.float
    }
    val rightAmplification = buffer.float
    val rightTone = buffer.float
    val rightConvFloat = buffer.float
    val rightConversationBoost = rightConvFloat > 0.5f
    val rightAmbientNoiseReduction = buffer.float

    val ownVoiceAmplification = buffer.float

    val avg = (leftAmplification + rightAmplification) / 2
    val amplification = avg.coerceIn(-1f, 1f)
    val diff = rightAmplification - leftAmplification
    val balance = diff.coerceIn(-1f, 1f)

    return HearingAidSettings(
        leftEQ = leftEQ,
        rightEQ = rightEQ,
        leftAmplification = leftAmplification,
        rightAmplification = rightAmplification,
        leftTone = leftTone,
        rightTone = rightTone,
        leftConversationBoost = leftConversationBoost,
        rightConversationBoost = rightConversationBoost,
        leftAmbientNoiseReduction = leftAmbientNoiseReduction,
        rightAmbientNoiseReduction = rightAmbientNoiseReduction,
        netAmplification = amplification,
        balance = balance,
        ownVoiceAmplification = ownVoiceAmplification
    )
}

fun sendHearingAidSettings(
    currentData: ByteArray,
    hearingAidSettings: HearingAidSettings,
    debounceJob: MutableState<Job?>,
    sender: (ATTHandles, ByteArray) -> Unit
) {
    debounceJob.value?.cancel()
    debounceJob.value = CoroutineScope(Dispatchers.IO).launch {
        delay(100.milliseconds)
        try {
            Log.d(TAG, "Current data before update: ${currentData.joinToString(" ") { String.format("%02X", it) }}")
            if (currentData.size < 104) {
                Log.w(TAG, "Current data size ${currentData.size} too small, cannot send settings")
                return@launch
            }
            val data = encodeHearingAidSettings(currentData, hearingAidSettings) ?: return@launch
            Log.d(TAG, "Sending updated settings: ${data.joinToString(" ") { String.format("%02X", it) }}")
            sender(ATTHandles.HEARING_AID, data)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

/** Preserve the device header and any trailing fields without mutating its cached state. */
fun encodeHearingAidSettings(currentData: ByteArray, settings: HearingAidSettings): ByteArray? {
    if (currentData.size < 104) return null
    val data = currentData.copyOf()
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(2, 0x64)
    for (i in 0..7) buffer.putFloat(4 + i * 4, settings.leftEQ[i])
    buffer.putFloat(36, settings.leftAmplification)
    buffer.putFloat(40, settings.leftTone)
    buffer.putFloat(44, if (settings.leftConversationBoost) 1f else 0f)
    buffer.putFloat(48, settings.leftAmbientNoiseReduction)
    for (i in 0..7) buffer.putFloat(52 + i * 4, settings.rightEQ[i])
    buffer.putFloat(84, settings.rightAmplification)
    buffer.putFloat(88, settings.rightTone)
    buffer.putFloat(92, if (settings.rightConversationBoost) 1f else 0f)
    buffer.putFloat(96, settings.rightAmbientNoiseReduction)
    buffer.putFloat(100, settings.ownVoiceAmplification)
    return data
}
