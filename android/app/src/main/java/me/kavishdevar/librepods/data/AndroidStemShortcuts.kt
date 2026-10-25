package me.kavishdevar.librepods.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import me.kavishdevar.librepods.R

data class StemShortcutOption(val target: StemShortcutTarget, val label: String, val detail: String)
data class StemShortcutDescription(val label: String, val available: Boolean)

internal fun SharedPreferences.stemString(key: String): String? = try {
    getString(key, null)
} catch (_: ClassCastException) {
    null
}

fun stemShortcutStore(preferences: SharedPreferences) = StemShortcutStore(
    readValue = preferences::stemString,
    writeValue = { key, value -> preferences.edit().putString(key, value).commit() },
)

/** Uses intent-scoped package visibility; never enumerates every installed package. */
class AndroidStemShortcuts(private val context: Context) {
    private val packages = context.packageManager

    fun options(): List<StemShortcutOption> {
        val actions = SupportedStemShortcut.entries.mapNotNull { action ->
            val target = StemShortcutTarget.SystemAction(action)
            if (intentFor(target) == null) null else StemShortcutOption(
                target, actionLabel(action), context.getString(R.string.stem_shortcut_android_action),
            )
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packages.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { match ->
                val info = match.activityInfo ?: return@mapNotNull null
                val target = StemShortcutTarget.App(info.packageName)
                if (intentFor(target) == null) return@mapNotNull null
                val label = try { info.applicationInfo.loadLabel(packages).toString() }
                catch (_: RuntimeException) { info.packageName }
                StemShortcutOption(target, label, info.packageName)
            }.distinctBy { it.target }.sortedBy { it.label.lowercase() }
        return actions + apps
    }

    fun describe(target: StemShortcutTarget): StemShortcutDescription {
        val label = when (target) {
            is StemShortcutTarget.SystemAction -> actionLabel(target.action)
            is StemShortcutTarget.App -> try {
                packages.getApplicationInfo(target.packageName, PackageManager.ApplicationInfoFlags.of(0))
                    .loadLabel(packages).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                target.packageName
            } catch (_: RuntimeException) {
                target.packageName
            }
        }
        return StemShortcutDescription(label, intentFor(target) != null)
    }

    private fun actionLabel(action: SupportedStemShortcut) = context.getString(when (action) {
        SupportedStemShortcut.CAMERA -> R.string.stem_shortcut_camera
        SupportedStemShortcut.ALARMS -> R.string.stem_shortcut_alarms
    })

    fun attempt(activity: Activity, request: StemLaunchRequest): StemLaunchAttempt {
        if (request == StemLaunchRequest.CurrentAssistant) {
            // Public API for the currently configured assistant, including voice-interaction services.
            // The caller must be the foreground activity. A true return still does not prove voice startup.
            return if (activity.showAssist(Bundle())) StemLaunchAttempt.REQUESTED_UNCONFIRMED
            else StemLaunchAttempt.UNAVAILABLE
        }
        val intent = when (request) {
            is StemLaunchRequest.Target -> intentFor(request.target)
            StemLaunchRequest.AssistIntent -> availableIntent(Intent(Intent.ACTION_ASSIST))
            StemLaunchRequest.VoiceCommand -> availableIntent(Intent(Intent.ACTION_VOICE_COMMAND))
            StemLaunchRequest.CurrentAssistant -> null
        } ?: return StemLaunchAttempt.UNAVAILABLE
        // The pure dispatcher catches disappearing handlers and SecurityException before trying fallback.
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return StemLaunchAttempt.REQUESTED_UNCONFIRMED
    }

    private fun intentFor(target: StemShortcutTarget): Intent? = try {
        if (StemShortcutCodec.encode(target) == null) null else when (target) {
            is StemShortcutTarget.App -> packages.getLaunchIntentForPackage(target.packageName)
                ?.takeIf { it.component?.packageName == target.packageName }
                ?.let(::availableIntent)
            is StemShortcutTarget.SystemAction -> availableIntent(Intent(when (target.action) {
                SupportedStemShortcut.CAMERA -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                SupportedStemShortcut.ALARMS -> AlarmClock.ACTION_SHOW_ALARMS
            }))
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun availableIntent(intent: Intent): Intent? {
        val info = packages.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            ?.activityInfo ?: return null
        return intent.takeIf {
            info.exported && info.enabled && info.applicationInfo.enabled &&
                info.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED == 0 &&
                (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
        }
    }
}
