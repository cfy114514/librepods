package me.kavishdevar.librepods.utils

/** Serialize whole key pairs without holding the controller's state/lifecycle monitor. */
internal class MediaCommandGate {
    private val lock = Any()
    fun <T> run(valid: () -> Boolean, command: () -> T): T? = synchronized(lock) {
        if (valid()) command() else null
    }
}

/** A DOWN already entered cannot be recalled; always attempt its captured manager's UP. */
internal fun dispatchMediaKeyPair(dispatch: (Boolean) -> Unit) {
    var failure: Throwable? = null
    try { dispatch(true) }
    catch (error: Throwable) { failure = error; throw error }
    finally {
        try { dispatch(false) }
        catch (error: Throwable) {
            val first = failure
            if (first == null) throw error
            if (first !== error) first.addSuppressed(error)
        }
    }
}

/** Preserve a pause attempt if dispatch changes playback and then reports an error. */
internal fun captureMediaPause(onError: (Exception) -> Unit,
    dispatch: (() -> Unit) -> Unit): Boolean {
    var captured = false
    try { dispatch { captured = true } }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) {
        if (!captured) throw error
        onError(error)
    }
    return captured
}
