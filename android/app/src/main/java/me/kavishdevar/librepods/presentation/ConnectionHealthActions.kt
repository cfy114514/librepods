package me.kavishdevar.librepods.presentation

enum class ConnectionHealthAction { BLUETOOTH_SETTINGS, APP_SETTINGS, BATTERY_SETTINGS, DIAGNOSTICS }

/** One user gesture performs at most one navigation. Missing handlers remain visible failures. */
internal fun performConnectionHealthAction(
    action: ConnectionHealthAction,
    openSettings: (ConnectionHealthAction) -> Boolean,
    openDiagnostics: (() -> Unit)? = null,
): Boolean = try {
    if (action == ConnectionHealthAction.DIAGNOSTICS) {
        if (openDiagnostics == null) false else { openDiagnostics(); true }
    } else openSettings(action)
} catch (_: Exception) {
    false
}
