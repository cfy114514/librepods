package me.kavishdevar.librepods.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HeadTrackingStatus { INACTIVE, STARTING, RECEIVING, UNAVAILABLE }

/** A successful socket write is not evidence that the sensor stream started. */
internal class HeadTrackingStartup<T : Any> {
    private val mutableStatus = MutableStateFlow(HeadTrackingStatus.INACTIVE)
    val status = mutableStatus.asStateFlow()
    private var request: T? = null
    private var waiting = false

    @Synchronized fun begin(value: T) {
        request = value
        waiting = true
        mutableStatus.value = HeadTrackingStatus.STARTING
    }

    @Synchronized fun received(value: T) {
        if (request !== value) return
        waiting = false
        mutableStatus.value = HeadTrackingStatus.RECEIVING
    }

    @Synchronized fun failed() {
        waiting = false
        // Keep the previous binding: late samples from it can still confirm recovery.
        mutableStatus.value = HeadTrackingStatus.UNAVAILABLE
    }

    /** Claim at most one timeout per attempt; old timers cannot retry a new session. */
    @Synchronized fun timedOut(value: T, canRetry: Boolean): Boolean {
        if (request !== value || !waiting) return false
        waiting = false
        if (!canRetry) mutableStatus.value = HeadTrackingStatus.UNAVAILABLE
        return canRetry
    }

    @Synchronized fun clear(status: HeadTrackingStatus = HeadTrackingStatus.INACTIVE) {
        request = null
        waiting = false
        mutableStatus.value = status
    }
}
