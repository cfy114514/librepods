package me.kavishdevar.librepods.shortcuts

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AndroidStemShortcuts
import me.kavishdevar.librepods.data.STEM_SHORTCUT_REQUEST_LIFETIME_MS
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.StemShortcutBinding
import me.kavishdevar.librepods.data.StemShortcutCodec
import me.kavishdevar.librepods.data.StemShortcutDelivery
import me.kavishdevar.librepods.data.StemShortcutDispatchLifecycle
import me.kavishdevar.librepods.data.StemShortcutResult
import me.kavishdevar.librepods.data.StemShortcutPress
import me.kavishdevar.librepods.data.StemShortcutSide
import me.kavishdevar.librepods.data.deliverStemShortcut
import me.kavishdevar.librepods.data.executeStemShortcut
import me.kavishdevar.librepods.data.isStemShortcutRequestCurrent
import me.kavishdevar.librepods.data.readStemShortcutBinding
import me.kavishdevar.librepods.data.stemShortcutNotificationAllowed
import me.kavishdevar.librepods.data.stemString

private const val CHANNEL_ID = "stem_shortcuts"
private const val EXTRA_SIDE = "stem_side"
private const val EXTRA_ACTION = "stem_action"
private const val EXTRA_TARGET = "stem_target"
private const val EXTRA_CREATED_AT = "stem_created_at"
private const val EXTRA_PRESS = "stem_press"

private fun currentBinding(context: Context, side: StemShortcutSide, press: StemShortcutPress): StemShortcutBinding? {
    val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    return readStemShortcutBinding(side, press, preferences::stemString)
}

/** Normal activity launches or notification taps only; no overlays, full-screen intents, or BAL opt-ins. */
object StemShortcutDispatcher {
    private var initialized = false
    private var resumedActivity: WeakReference<Activity>? = null

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivity = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    fun request(context: Context, side: StemShortcutSide, press: StemShortcutPress = StemShortcutPress.LONG_PRESS) {
        val binding = currentBinding(context, side, press) ?: return
        request(context, binding, SystemClock.elapsedRealtime())
    }

    internal fun request(context: Context, binding: StemShortcutBinding, createdAt: Long) {
        val side = binding.side
        val press = binding.press
        if (!isStemShortcutRequestCurrent(binding, currentBinding(context, side, press),
                createdAt, SystemClock.elapsedRealtime())) return
        val intent = Intent(context, StemShortcutDispatchActivity::class.java)
            .setAction("${context.packageName}.STEM_SHORTCUT.${side.id}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_SIDE, side.id)
            .putExtra(EXTRA_PRESS, press.name)
            .putExtra(EXTRA_ACTION, binding.action.name)
            .putExtra(EXTRA_TARGET, binding.target?.let(StemShortcutCodec::encode))
            .putExtra(EXTRA_CREATED_AT, createdAt)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val locked = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        // ProcessLifecycleOwner deliberately delays pause events; it cannot prove launch eligibility.
        val foreground = resumedActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
        val result = deliverStemShortcut(
            activityVisible = foreground != null,
            isLocked = locked,
            requestForeground = {
                try { foreground!!.startActivity(intent); true } catch (_: RuntimeException) { false }
            },
            postNotification = { postNotification(context, side, intent) },
        )
        if (result == StemShortcutDelivery.UNAVAILABLE) {
            Toast.makeText(context, R.string.stem_shortcut_notifications_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun postNotification(context: Context, side: StemShortcutSide, intent: Intent): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.stem_shortcuts_title), NotificationManager.IMPORTANCE_DEFAULT,
        ))
        if (!stemShortcutNotificationAllowed(
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                manager.areNotificationsEnabled(),
                manager.getNotificationChannel(CHANNEL_ID)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true)) {
            false
        } else {
            // Side participates in PendingIntent identity. Content is generic; a target is never mislabeled.
            val pending = PendingIntent.getActivity(context, notificationId(side), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle(context.getString(if (side == StemShortcutSide.LEFT) R.string.stem_shortcut_left_notification else R.string.stem_shortcut_right_notification))
                .setContentText(context.getString(R.string.stem_shortcut_tap_notification))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(STEM_SHORTCUT_REQUEST_LIFETIME_MS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            manager.notify(notificationId(side), notification)
            true // Posted, not proof of visibility, delivery, or activity launch.
        }
    } catch (_: RuntimeException) {
        false
    }

    private fun notificationId(side: StemShortcutSide) = 610 + side.ordinal
}

/** A direct notification destination, not a broadcast/service trampoline. */
class StemShortcutDispatchActivity : Activity() {
    private var dispatch = StemShortcutDispatchLifecycle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch = StemShortcutDispatchLifecycle(savedInstanceState?.getBoolean("handled") == true)
        if (dispatch.handled) { finish(); return }
        setContentView(TextView(this).apply {
            setText(R.string.stem_shortcut_preparing)
            gravity = Gravity.CENTER
        })
    }

    override fun onResume() {
        super.onResume()
        if (dispatch.onResume(hasWindowFocus())) dispatchOnce()
    }

    override fun onPause() {
        dispatch.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (dispatch.onFocusChanged(hasFocus)) dispatchOnce()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("handled", dispatch.handled)
        super.onSaveInstanceState(outState)
    }

    private fun dispatchOnce() {
        if (isFinishing) return
        try {
            val side = StemShortcutSide.fromString(intent.getStringExtra(EXTRA_SIDE)) ?: return
            val press = StemShortcutPress.fromString(intent.getStringExtra(EXTRA_PRESS)) ?: return
            val action = intent.getStringExtra(EXTRA_ACTION)?.let(StemAction::fromString) ?: return
            if (action != StemAction.CUSTOM_SHORTCUT && action != StemAction.DIGITAL_ASSISTANT) return
            val requested = StemShortcutBinding(side, action, StemShortcutCodec.decode(intent.getStringExtra(EXTRA_TARGET)), press)
            if (!isStemShortcutRequestCurrent(requested, currentBinding(this, side, press),
                    intent.getLongExtra(EXTRA_CREATED_AT, -1), SystemClock.elapsedRealtime())) {
                Toast.makeText(this, R.string.stem_shortcut_expired, Toast.LENGTH_LONG).show()
                return
            }
            val launcher = AndroidStemShortcuts(this)
            val keyguard = getSystemService(KeyguardManager::class.java)
            val result = executeStemShortcut(requested.target,
                keyguard.isKeyguardLocked || keyguard.isDeviceLocked) { launcher.attempt(this, it) }
            val message = when (result) {
                StemShortcutResult.LOCKED -> R.string.stem_shortcut_unlock
                StemShortcutResult.ASSISTANT_UNAVAILABLE -> R.string.stem_shortcut_assistant_unavailable
                StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED ->
                    if (action == StemAction.CUSTOM_SHORTCUT) R.string.stem_shortcut_fallback_requested else null
                StemShortcutResult.TARGET_REQUESTED_UNCONFIRMED -> null
            }
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        } finally {
            finish()
        }
    }
}
