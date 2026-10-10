package me.kavishdevar.librepods.data

import androidx.core.content.edit
import android.util.Log
import me.kavishdevar.librepods.utils.XposedServiceHolder

class XposedRemotePrefImpl: XposedRemotePref {
    override fun isAvailable(): Boolean {
        return XposedServiceHolder.service != null
    }

    override fun getBoolean(key: String, def: Boolean): Boolean {
        val lease = XposedServiceHolder.captureConnection() ?: return def
        return try {
            val value = lease.service.getRemotePreferences("me.kavishdevar.librepods").getBoolean(key, def)
            if (XposedServiceHolder.isCurrent(lease)) value else def
        } catch (error: Exception) {
            Log.w("XposedRemotePref", "Framework preference unavailable: $key", error)
            def
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences("me.kavishdevar.librepods")
            .edit { putBoolean(key, value) }
    }
}
