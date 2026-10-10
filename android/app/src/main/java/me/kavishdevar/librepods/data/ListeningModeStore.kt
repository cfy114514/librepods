package me.kavishdevar.librepods.data

import android.content.Context
import android.content.SharedPreferences

internal const val OFF_LISTENING_MODE_OWNER = "off_listening_mode_address"

internal fun cachedOffListeningMode(values: Map<String, *>): Boolean = ownedOffListeningMode(
    values["off_listening_mode"] as? Boolean,
    values[OFF_LISTENING_MODE_OWNER] as? String ?: "",
    values["mac_address"] as? String ?: ""
)

internal fun saveOffListeningMode(prefs: SharedPreferences, address: String, enabled: Boolean): Boolean {
    val owner = batteryHistoryIdentity(address) ?: return false
    if (owner != batteryHistoryIdentity(prefs.getString("mac_address", "") ?: "")) return false
    prefs.edit().putString(OFF_LISTENING_MODE_OWNER, owner).putBoolean("off_listening_mode", enabled).apply()
    return true
}

internal fun cachedListeningModes(context: Context, allowOff: Boolean? = null): List<Int> {
    return cachedListeningModes(context.getSharedPreferences("settings", Context.MODE_PRIVATE).all, allowOff)
}

internal fun cachedListeningModes(values: Map<String, *>, allowOff: Boolean? = null): List<Int> =
    availableListeningModes(ownedListeningCapabilities(
        values["airpods_model_number"] as? String ?: "",
        values["airpods_model_address"] as? String ?: "",
        values["mac_address"] as? String ?: ""
    ), allowOff ?: cachedOffListeningMode(values))
