package me.kavishdevar.librepods.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers

/** One application store; opening/reading/registering/committing is deferred to its IO worker. */
internal object AppSettingsPreferenceStores {
    private var current: AppSettingsPreferences? = null
    @Synchronized fun get(context: Context): AppSettingsPreferences = current ?: run {
        val application = context.applicationContext
        AppSettingsPreferences(Dispatchers.IO) { AndroidBackend(application) }.also { current = it }
    }

    private class AndroidBackend(context: Context) : AppSettingsPreferenceBackend {
        private val prefs by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
        private val keys = AppSetting.entries.map { it.nameInPreferences }.toSet()
        private var registered: SharedPreferences.OnSharedPreferenceChangeListener? = null
        override fun read() = AppSetting.snapshot(prefs.all)
        override fun write(changes: Map<AppSetting, Any?>): Boolean {
            val editor = prefs.edit()
            changes.forEach { (key, value) ->
                val name = key.nameInPreferences
                when (value) {
                    null -> editor.remove(name)
                    is Boolean -> editor.putBoolean(name, value)
                    is Int -> editor.putInt(name, value)
                    is Long -> editor.putLong(name, value)
                    is String -> editor.putString(name, value)
                }
            }
            return editor.commit()
        }
        override fun register(listener: () -> Unit) {
            check(registered == null)
            val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == null || key in keys) listener() }
            registered = callback
            prefs.registerOnSharedPreferenceChangeListener(callback)
        }
        override fun unregister(listener: () -> Unit) {
            registered?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
            registered = null
        }
    }
}
