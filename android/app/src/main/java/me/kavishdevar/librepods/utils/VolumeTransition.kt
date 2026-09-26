package me.kavishdevar.librepods.utils

/** One volume ramp at a time, including when a cancelled callback has already been dispatched. */
internal class VolumeTransition(
    private val setVolume: (Int) -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val stepDelayMs: Long = 50L
) {
    private var activeTransition: Runnable? = null

    @Synchronized
    fun cancel() {
        activeTransition?.let(unschedule)
        activeTransition = null
    }

    @Synchronized
    fun start(fromVolume: Int, toVolume: Int) {
        cancel()
        if (fromVolume == toVolume) return

        var currentVolume = fromVolume
        val step = if (fromVolume < toVolume) 1 else -1
        val transition = object : Runnable {
            override fun run() {
                synchronized(this@VolumeTransition) {
                    if (activeTransition !== this) return
                    currentVolume += step
                    setVolume(currentVolume)
                    if (currentVolume == toVolume) {
                        activeTransition = null
                    } else {
                        schedule(this, stepDelayMs)
                    }
                }
            }
        }
        activeTransition = transition
        schedule(transition, 0L)
    }
}
