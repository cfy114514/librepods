package android.content.pm

import android.content.ComponentName
import android.content.Intent
import android.content.Platform

class ApplicationInfo {
    var enabled = true
    var flags = 0
    fun loadLabel(manager: PackageManager): CharSequence = "Test app"
    companion object { const val FLAG_SUSPENDED = 1 }
}
class ActivityInfo(val packageName: String) {
    val applicationInfo = ApplicationInfo()
    var exported = true
    var enabled = true
    val permission: String? = null
}
class ResolveInfo(val activityInfo: ActivityInfo?)
class PackageManager {
    class ResolveInfoFlags { companion object { fun of(flags: Long) = ResolveInfoFlags() } }
    class ApplicationInfoFlags { companion object { fun of(flags: Long) = ApplicationInfoFlags() } }
    class NameNotFoundException : RuntimeException()
    fun getApplicationInfo(name: String, flags: ApplicationInfoFlags) = ApplicationInfo()
    fun queryIntentActivities(intent: Intent, flags: ResolveInfoFlags) = emptyList<ResolveInfo>()
    fun getLaunchIntentForPackage(name: String): Intent? =
        if (Platform.targetAvailable) Intent(Intent.ACTION_MAIN).apply { component = ComponentName(name) } else null
    fun resolveActivity(intent: Intent, flags: ResolveInfoFlags): ResolveInfo? {
        Platform.onResolve()
        val assistant = intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND
        if (assistant && !Platform.assistantAvailable || !assistant && !Platform.targetAvailable) return null
        return ResolveInfo(ActivityInfo(intent.component?.packageName ?: "org.example.handler"))
    }
    companion object { const val PERMISSION_GRANTED = 0; const val MATCH_DEFAULT_ONLY = 1 }
}
