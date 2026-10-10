package me.kavishdevar.librepods.audio

import kotlin.math.roundToInt

internal enum class LiveListenError {
    NO_HEADPHONES, NO_MICROPHONE, MICROPHONE_BUSY, PERMISSION, FOCUS_LOST,
    ROUTE_CHANGED, INITIALIZATION, READ_FAILED, WRITE_FAILED
}

internal class AudioRelayException(val reason: LiveListenError) : Exception(reason.name)

/** One reusable PCM buffer; partial writes cannot lose samples or create a backlog. */
internal class AudioRelay(
    private val read: (ShortArray) -> Int,
    private val write: (ShortArray, Int, Int) -> Int,
    private val active: () -> Boolean,
    private val routeSafe: () -> Boolean,
    private val gain: () -> Float,
    private val idle: () -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val chunkSamples: Int = 480,
    private val stallMillis: Long = 1500
) {
    init { require(chunkSamples > 0 && stallMillis > 0) }

    fun run() {
        val pcm = ShortArray(chunkSamples)
        var lastReadAt = nowMillis()
        while (active()) {
            if (!routeSafe()) throw AudioRelayException(LiveListenError.ROUTE_CHANGED)
            val count = read(pcm)
            if (!active()) return
            if (count < 0 || count > pcm.size) throw AudioRelayException(LiveListenError.READ_FAILED)
            if (count == 0) {
                if (nowMillis() - lastReadAt >= stallMillis) throw AudioRelayException(LiveListenError.READ_FAILED)
                idle()
                continue
            }
            lastReadAt = nowMillis()
            val amplification = gain().takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f
            if (amplification != 1f) {
                for (i in 0 until count) {
                    pcm[i] = (pcm[i] * amplification).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            var offset = 0
            var lastWriteAt = nowMillis()
            while (offset < count && active()) {
                if (!routeSafe()) throw AudioRelayException(LiveListenError.ROUTE_CHANGED)
                val written = write(pcm, offset, count - offset)
                if (written < 0 || written > count - offset) throw AudioRelayException(LiveListenError.WRITE_FAILED)
                if (written == 0) {
                    if (nowMillis() - lastWriteAt >= stallMillis) throw AudioRelayException(LiveListenError.WRITE_FAILED)
                    idle()
                } else {
                    offset += written
                    lastWriteAt = nowMillis()
                }
            }
        }
    }
}
