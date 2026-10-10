package me.kavishdevar.librepods.services

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Closing a retired snapshot is independent of the service's cancelled job. */
internal fun closeResourcesOnIo(resources: List<Closeable>) {
    if (resources.isEmpty()) return
    CoroutineScope(NonCancellable + Dispatchers.IO).launch {
        resources.forEach { runCatching { it.close() } }
    }
}
