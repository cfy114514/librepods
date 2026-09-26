package me.kavishdevar.librepods.bluetooth

/** A restartable scan-maintenance loop with at most one scheduled callback. */
internal class BleMaintenanceTask(
    private val schedule: (Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val maintain: () -> Unit
) {
    private var activeCallback: Runnable? = null

    @Synchronized
    fun start() {
        activeCallback?.let(cancel)
        val callback = object : Runnable {
            override fun run() {
                synchronized(this@BleMaintenanceTask) {
                    // A removed callback may already have been dispatched by Handler.
                    // Its identity must not become valid again when scanning restarts.
                    if (activeCallback !== this) return
                    maintain()
                    if (activeCallback === this) schedule(this)
                }
            }
        }
        activeCallback = callback
        schedule(callback)
    }

    @Synchronized
    fun stop() {
        activeCallback?.let(cancel)
        activeCallback = null
    }
}
