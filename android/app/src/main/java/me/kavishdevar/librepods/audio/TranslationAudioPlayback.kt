package me.kavishdevar.librepods.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import me.kavishdevar.librepods.translation.TranslationError
import me.kavishdevar.librepods.translation.TranslationException
import me.kavishdevar.librepods.translation.TranslationOutput
import java.io.InputStream

/** Plays one completed local synthesis to the explicitly selected output, then releases it. */
internal class TranslationAudioPlayback(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun selectDevice(output: TranslationOutput): AudioDeviceInfo {
        val manager = context.getSystemService(AudioManager::class.java)
        val address = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("mac_address", "") ?: ""
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            when (output) {
                TranslationOutput.AIRPODS -> address.isNotEmpty() && it.address.equals(address, true) &&
                    it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)
                TranslationOutput.SPEAKER -> it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                TranslationOutput.CAPTIONS -> false
            }
        } ?: throw TranslationException(if (output == TranslationOutput.AIRPODS) TranslationError.NO_HEADPHONES else TranslationError.AUDIO)
        return device
    }

    @SuppressLint("MissingPermission")
    fun play(input: InputStream, device: AudioDeviceInfo, active: () -> Boolean) {
        val manager = context.getSystemService(AudioManager::class.java)
        if (manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.id == device.id && it.type == device.type && it.address == device.address })
            throw TranslationException(TranslationError.ROUTE_CHANGED)
        WavePcmReader(input).use { reader ->
                val channelMask = if (reader.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minimum = AudioTrack.getMinBufferSize(reader.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) throw TranslationException(TranslationError.AUDIO)
                val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(reader.sampleRate).setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 4096)).setTransferMode(AudioTrack.MODE_STREAM).build()
                try {
                    if (!active()) return
                    if (track.state != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(device))
                        throw TranslationException(TranslationError.AUDIO)
                    fun safe(): Boolean = if (Build.VERSION.SDK_INT >= 36) {
                        val routes = track.routedDevices
                        routes.isNotEmpty() && routes.all { it.id == device.id }
                    } else track.routedDevice?.id == device.id
                    track.setStartThresholdInFrames(256)
                    track.play()
                    val silence = ShortArray(256 * reader.channels)
                    var queuedSamples = 0L
                    val deadline = SystemClock.elapsedRealtime() + 1500
                    while (active() && !safe()) {
                        if (SystemClock.elapsedRealtime() >= deadline) throw TranslationException(TranslationError.ROUTE_CHANGED)
                        val written = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) throw TranslationException(TranslationError.AUDIO)
                        queuedSamples += written
                        Thread.sleep(4)
                    }
                    var ended = false
                    AudioRelay(read = { buffer -> reader.read(buffer).also { if (it == 0) ended = true } },
                        write = { buffer, offset, count ->
                            track.write(buffer, offset, count, AudioTrack.WRITE_NON_BLOCKING).also {
                                if (it > 0) queuedSamples += it
                            }
                        }, active = { active() && !ended }, routeSafe = ::safe, gain = { 1f },
                        idle = { Thread.sleep(4) }, chunkSamples = 1024,
                        nowMillis = SystemClock::elapsedRealtime).run()
                    // Do not discard the tail still queued in AudioTrack at WAV EOF.
                    val drainUntil = SystemClock.elapsedRealtime() + 3000
                    val frames = queuedSamples / reader.channels
                    while (active() && (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < frames) {
                        if (!safe()) throw TranslationException(TranslationError.ROUTE_CHANGED)
                        if (SystemClock.elapsedRealtime() >= drainUntil) throw TranslationException(TranslationError.AUDIO)
                        Thread.sleep(4)
                    }
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.flush() }
                    track.release()
                }
        }
    }
}
