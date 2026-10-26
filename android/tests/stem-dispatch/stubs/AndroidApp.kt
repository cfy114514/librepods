package android.app

import android.content.Context
import android.content.Intent
import android.content.Platform
import android.os.Bundle
import me.kavishdevar.librepods.data.*

open class Activity : Context() {
    lateinit var intent: Intent
    var isFinishing = false
    var isDestroyed = false
    var focused = false
    open fun onCreate(state: Bundle?) = Unit
    open fun onResume() = Unit
    open fun onPause() = Unit
    open fun onWindowFocusChanged(hasFocus: Boolean) { focused = hasFocus }
    open fun onSaveInstanceState(outState: Bundle) = Unit
    fun hasWindowFocus() = focused
    fun setContentView(view: Any) = Unit
    fun startActivity(intent: Intent) {
        if (Platform.failStart) throw SecurityException("Launch denied")
        if (intent.destination != Activity::class.java) Platform.starts += intent.copy()
        else Platform.launches += when (intent.action) {
            Intent.ACTION_ASSIST -> StemLaunchRequest.AssistIntent
            Intent.ACTION_VOICE_COMMAND -> StemLaunchRequest.VoiceCommand
            "camera" -> StemLaunchRequest.Target(StemShortcutTarget.SystemAction(SupportedStemShortcut.CAMERA))
            "alarms" -> StemLaunchRequest.Target(StemShortcutTarget.SystemAction(SupportedStemShortcut.ALARMS))
            else -> StemLaunchRequest.Target(StemShortcutTarget.App(intent.component!!.packageName))
        }
    }
    fun showAssist(bundle: Bundle): Boolean {
        Platform.launches += StemLaunchRequest.CurrentAssistant
        return Platform.assistantAvailable
    }
    fun finish() { isFinishing = true }
}

class Application : Context() {
    lateinit var callbacks: ActivityLifecycleCallbacks
    fun registerActivityLifecycleCallbacks(value: ActivityLifecycleCallbacks) { callbacks = value }
    interface ActivityLifecycleCallbacks {
        fun onActivityResumed(activity: Activity)
        fun onActivityPaused(activity: Activity)
        fun onActivityDestroyed(activity: Activity)
        fun onActivityCreated(activity: Activity, state: Bundle?)
        fun onActivityStarted(activity: Activity)
        fun onActivityStopped(activity: Activity)
        fun onActivitySaveInstanceState(activity: Activity, state: Bundle)
    }
}

class KeyguardManager { var isKeyguardLocked = false; var isDeviceLocked = false }
class NotificationChannel(val id: String, val name: String, var importance: Int)
class Notification(val pending: PendingIntent?) {
    class Builder(context: Context, channel: String) {
        private var pending: PendingIntent? = null
        fun setSmallIcon(icon: Int) = this
        fun setContentTitle(text: String) = this
        fun setContentText(text: String) = this
        fun setContentIntent(value: PendingIntent) = apply { pending = value }
        fun setAutoCancel(value: Boolean) = this
        fun setOnlyAlertOnce(value: Boolean) = this
        fun setTimeoutAfter(value: Long) = this
        fun setVisibility(value: Int) = this
        fun build() = Notification(pending)
    }
    companion object { const val VISIBILITY_PRIVATE = 0 }
}
class NotificationManager {
    var failNotify = false
    var enabled = true
    val channels = mutableMapOf<String, NotificationChannel>()
    val notifications = mutableMapOf<Int, Notification>()
    fun createNotificationChannel(channel: NotificationChannel) { channels.putIfAbsent(channel.id, channel) }
    fun getNotificationChannel(id: String) = channels[id]
    fun areNotificationsEnabled() = enabled
    fun notify(id: Int, notification: Notification) {
        if (failNotify) throw SecurityException("Notification denied")
        notifications[id] = notification
    }
    companion object { const val IMPORTANCE_DEFAULT = 3; const val IMPORTANCE_NONE = 0 }
}

/** Implements documented identity/update/cancel/one-shot behavior; it does not simulate Android BAL. */
class PendingIntent private constructor(private var intent: Intent, private val flags: Int) {
    var canceled = false
        private set
    fun send(): Intent {
        if (canceled) throw CanceledException()
        if (flags and FLAG_ONE_SHOT != 0) canceled = true
        return intent.copy()
    }
    class CanceledException : RuntimeException()
    companion object {
        const val FLAG_UPDATE_CURRENT = 1
        const val FLAG_IMMUTABLE = 2
        const val FLAG_CANCEL_CURRENT = 4
        const val FLAG_ONE_SHOT = 8
        private data class Key(val code: Int, val action: String?, val destination: Class<*>, val identityFlags: Int)
        private val tokens = mutableMapOf<Key, PendingIntent>()
        fun getActivity(context: Context, code: Int, intent: Intent, flags: Int): PendingIntent {
            val key = Key(code, intent.action, intent.destination, flags and (FLAG_ONE_SHOT or FLAG_IMMUTABLE))
            val previous = tokens[key]?.takeUnless { it.canceled }
            if (previous != null && flags and FLAG_CANCEL_CURRENT != 0) previous.canceled = true
            else if (previous != null) {
                if (flags and FLAG_UPDATE_CURRENT != 0) previous.intent = intent.copy()
                return previous
            }
            return PendingIntent(intent.copy(), flags).also { tokens[key] = it }
        }
    }
}
