package me.kavishdevar.librepods.services

import kotlinx.coroutines.CancellationException

/** Called by one serial worker. Accepted values are bounded to known keys in one scope. */
internal class MetadataValueWriter<S : Any>(private val allowedKeys: Set<Int>) {
    private var scope: S? = null
    private val accepted = mutableMapOf<Int, String>()

    fun write(scope: S, values: Map<Int, String>, current: () -> Boolean,
              onError: (Exception) -> Unit = {}, apply: (Int, String) -> Boolean): Boolean {
        require(values.keys.all { it in allowedKeys })
        if (!current()) return false
        if (this.scope != scope) { this.scope = scope; accepted.clear() }
        var complete = true
        for ((key, value) in values) {
            if (!current()) return false
            if (accepted[key] == value) continue
            val success = try { apply(key, value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onError(error); false }
            if (!current()) return false
            if (success) accepted[key] = value else complete = false
        }
        return complete
    }
}
