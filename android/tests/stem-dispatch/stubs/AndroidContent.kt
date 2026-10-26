package android.content

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.pm.PackageManager

object Platform {
    val settings = SharedPreferences()
    val notifications = NotificationManager()
    val keyguard = KeyguardManager()
    val starts = mutableListOf<Intent>()
    val launches = mutableListOf<Any>()
    val toasts = mutableListOf<Int>()
    var targetAvailable = true
    var assistantAvailable = true
    var onResolve: () -> Unit = {}
    var permissionGranted = true
    var failStart = false
}

open class Context {
    val packageManager = PackageManager()
    val packageName = "me.kavishdevar.librepods"
    fun getSharedPreferences(name: String, mode: Int) = Platform.settings
    fun getString(resource: Int) = resource.toString()
    fun checkSelfPermission(permission: String) = if (Platform.permissionGranted) PackageManager.PERMISSION_GRANTED else -1
    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(type: Class<T>): T = when (type) {
        NotificationManager::class.java -> Platform.notifications
        KeyguardManager::class.java -> Platform.keyguard
        else -> error("Unexpected service $type")
    } as T
    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences {
    val values = mutableMapOf<String, String>()
    fun getString(key: String, fallback: String?) = values[key] ?: fallback
    fun edit() = Editor()
    inner class Editor {
        fun putString(key: String, value: String) = apply { values[key] = value }
        fun commit() = true
    }
}

class Intent(val context: Context, val destination: Class<out Activity>) {
    constructor(action: String) : this(Context(), Activity::class.java) { this.action = action }
    var component: ComponentName? = null
    var action: String? = null
    val extras = mutableMapOf<String, Any?>()
    fun setAction(value: String) = apply { action = value }
    fun addFlags(value: Int) = this
    fun addCategory(value: String) = this
    fun putExtra(key: String, value: String?) = apply { extras[key] = value }
    fun putExtra(key: String, value: Long) = apply { extras[key] = value }
    fun getStringExtra(key: String) = extras[key] as? String
    fun getLongExtra(key: String, fallback: Long) = extras[key] as? Long ?: fallback
    fun copy() = Intent(context, destination).also { it.action = action; it.component = component; it.extras.putAll(extras) }
    companion object {
        const val FLAG_ACTIVITY_NEW_TASK = 1
        const val ACTION_MAIN = "main"
        const val CATEGORY_LAUNCHER = "launcher"
        const val ACTION_ASSIST = "assist"
        const val ACTION_VOICE_COMMAND = "voice_command"
    }
}

data class ComponentName(val packageName: String)
