package me.kavishdevar.librepods.shortcuts

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.Platform
import android.os.SystemClock
import me.kavishdevar.librepods.data.*

private val app = Application()
private val binding = StemShortcutBinding(StemShortcutSide.LEFT, StemAction.CUSTOM_SHORTCUT,
    StemShortcutTarget.App("org.example.player"))

private fun dispatch(intent: Intent, focusedFirst: Boolean = false): StemShortcutDispatchActivity {
    val activity = StemShortcutDispatchActivity()
    activity.intent = intent
    activity.onCreate(null)
    if (focusedFirst) activity.onWindowFocusChanged(true)
    activity.onResume()
    if (!focusedFirst) activity.onWindowFocusChanged(true)
    return activity
}

private fun post(value: StemShortcutBinding = binding): PendingIntent {
    Platform.settings.values[value.side.actionKey(value.press)] = value.action.name
    value.target?.let { Platform.settings.values[value.side.targetKey] = StemShortcutCodec.encode(it)!! }
    StemShortcutDispatcher.request(app, value, SystemClock.now, isSourceCurrent = { true })
    return Platform.notifications.notifications.getValue(610 + value.side.ordinal).pending!!
}

private fun canceled(token: PendingIntent): Boolean = try { token.send(); false } catch (_: PendingIntent.CanceledException) { true }

private fun postWithOwner(current: () -> Boolean): PendingIntent {
    StemShortcutDispatcher.request(app, binding, SystemClock.now, current)
    return Platform.notifications.notifications.getValue(610).pending!!
}

fun main(args: Array<String>) {
    StemShortcutDispatcher.initialize(app)
    val cases = linkedMapOf<String, () -> Unit>(
        "expired_token_cannot_be_refreshed_by_new_request" to {
            val old = post()
            SystemClock.now += STEM_SHORTCUT_REQUEST_LIFETIME_MS + 1
            val newer = post(binding.copy(target = StemShortcutTarget.SystemAction(SupportedStemShortcut.CAMERA)))
            check(canceled(old)) { "An expired token was updated into a fresh camera request" }
            dispatch(newer.send())
            check(Platform.launches.size == 1)
        },
        "notification_token_is_single_use" to {
            val token = post()
            dispatch(token.send())
            check(canceled(token)) { "The same notification token can launch its target repeatedly" }
            check(Platform.launches.size == 1)
        },
        "replayed_activity_intent_does_not_launch_twice" to {
            val intent = post().send()
            dispatch(intent)
            dispatch(intent.copy())
            check(Platform.launches.size == 1) { "Separate Activity instances each accepted the same request" }
        },
        "source_ownership_survives_notification_and_foreground_handoff" to {
            for (foreground in listOf(false, true)) {
                val visible = Activity().apply { focused = true }
                if (foreground) app.callbacks.onActivityResumed(visible)
                val source = Any()
                val service = Any()
                var currentSource: Any? = source
                var currentService: Any? = service
                var currentSession = 7L
                var currentPeer = "AA:BB"
                var sourceUsable = true
                val guard = StemShortcutEventGuard(source, service, 7, "AA:BB")
                val current = { guard.isCurrent(currentSource, currentService, currentSession, currentPeer, sourceUsable) }
                for (change in 0..4) {
                    StemShortcutDispatcher.request(app, binding, SystemClock.now, current)
                    val intent = if (foreground) Platform.starts.last() else Platform.notifications.notifications.getValue(610).pending!!.send()
                    when (change) {
                        0 -> currentPeer = "CC:DD" // Selected another device, with unchanged global binding.
                        1 -> currentSession++ // Same MAC reconnect, even if a socket reference was retained.
                        2 -> currentSource = Any() // Replaced transport.
                        3 -> currentService = Any() // Recreated service.
                        4 -> sourceUsable = false // Permission revoked or socket closed.
                    }
                    dispatch(intent)
                    check(Platform.launches.isEmpty()) { "Retired source launched after handoff (foreground=$foreground, change=$change)" }
                    currentSource = source; currentService = service; currentSession = 7; currentPeer = "AA:BB"; sourceUsable = true
                }
                if (foreground) app.callbacks.onActivityPaused(visible)
            }
        },
        "source_check_permission_exception_is_expired_and_consumed" to {
            val intent = postWithOwner { throw SecurityException("Bluetooth permission revoked") }.send()
            dispatch(intent)
            check(Platform.launches.isEmpty())
            check(Platform.toasts.isNotEmpty())
        },
        "aged_reader_event_is_rejected_before_delivery" to {
            val previous = Platform.notifications.notifications.toMap()
            StemShortcutDispatcher.request(app, binding, SystemClock.now - STEM_SHORTCUT_REQUEST_LIFETIME_MS - 1,
                isSourceCurrent = { true })
            check(Platform.notifications.notifications == previous)
            check(Platform.starts.isEmpty())
        },
        "new_press_supersedes_unlaunched_same_side_but_preserves_other_side" to {
            val old = post().send()
            val right = post(binding.copy(side = StemShortcutSide.RIGHT))
            val next = post(binding.copy(target = StemShortcutTarget.SystemAction(SupportedStemShortcut.ALARMS)))
            dispatch(old)
            check(Platform.launches.isEmpty())
            dispatch(right.send())
            dispatch(next.send())
            check(Platform.launches.size == 2)
        },
        "missing_registry_request_fails_closed_with_feedback" to {
            val unrecognized = Intent(app, StemShortcutDispatchActivity::class.java)
                .putExtra("stem_request_id", "from-a-retired-process")
            dispatch(unrecognized)
            check(Platform.launches.isEmpty())
            check(Platform.toasts.isNotEmpty())
        },
        "legacy_non_one_shot_token_cannot_resurrect_old_extras" to {
            val legacyIntent = Intent(app, StemShortcutDispatchActivity::class.java)
                .setAction("${app.packageName}.STEM_SHORTCUT.left")
                .putExtra("stem_side", "left")
                .putExtra("stem_press", "LONG_PRESS")
                .putExtra("stem_action", "CUSTOM_SHORTCUT")
                .putExtra("stem_target", "1|app|org.example.player")
                .putExtra("stem_created_at", SystemClock.now)
            val legacy = PendingIntent.getActivity(app, 610, legacyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val current = post()
            // ONE_SHOT is part of token identity: cancel-current cannot cancel a token with old flags.
            dispatch(legacy.send())
            check(Platform.launches.isEmpty())
            check(Platform.toasts.isNotEmpty())
            dispatch(current.send())
            check(Platform.launches.size == 1)
        },
        "unavailable_target_still_uses_assistant_fallback_once" to {
            Platform.targetAvailable = false
            val intent = post().send()
            dispatch(intent)
            check(Platform.launches == listOf(StemLaunchRequest.CurrentAssistant))
            dispatch(intent.copy())
            check(Platform.launches.size == 1)
        },
        "missing_assistant_reports_unavailable_without_replay" to {
            Platform.targetAvailable = false
            Platform.assistantAvailable = false
            val intent = post().send()
            dispatch(intent)
            check(Platform.launches.size == 1)
            check(Platform.toasts.isNotEmpty())
            dispatch(intent.copy())
            check(Platform.launches.size == 1)
        },
        "failed_visible_activity_request_uses_notification" to {
            val visible = Activity().apply { focused = true }
            app.callbacks.onActivityResumed(visible)
            Platform.failStart = true
            val token = post()
            Platform.failStart = false
            app.callbacks.onActivityPaused(visible)
            dispatch(token.send(), focusedFirst = true)
            check(Platform.starts.isEmpty())
            check(Platform.launches.size == 1)
        },
        "focus_and_resume_are_both_required" to {
            val intent = post().send()
            val activity = StemShortcutDispatchActivity()
            activity.intent = intent
            activity.onCreate(null)
            activity.onResume()
            check(Platform.launches.isEmpty())
            activity.onPause()
            activity.onWindowFocusChanged(true)
            check(Platform.launches.isEmpty())
            activity.onResume()
            check(Platform.launches.size == 1)
        },
        "denied_notification_paths_do_not_start_activity" to {
            for (failure in 0..3) {
                Platform.permissionGranted = failure != 0
                Platform.notifications.enabled = failure != 1
                Platform.notifications.channels["stem_shortcuts"]?.importance = if (failure == 2) 0 else 3
                Platform.notifications.failNotify = failure == 3
                val previous = Platform.notifications.notifications.toMap()
                val previousToasts = Platform.toasts.size
                StemShortcutDispatcher.request(app, binding, SystemClock.now, isSourceCurrent = { true })
                check(Platform.notifications.notifications == previous)
                check(Platform.starts.isEmpty())
                check(Platform.toasts.size == previousToasts + 1)
            }
            Platform.notifications.failNotify = false
        },
        "lock_after_notification_never_launches_target" to {
            val intent = post().send()
            Platform.keyguard.isDeviceLocked = true
            dispatch(intent)
            check(Platform.launches.isEmpty())
        },
        "re_lock_during_resolution_prevents_target_and_assistant_requests" to {
            for (deviceLock in listOf(false, true)) {
                val intent = post().send()
                Platform.onResolve = {
                    if (deviceLock) Platform.keyguard.isDeviceLocked = true
                    else Platform.keyguard.isKeyguardLocked = true
                }
                dispatch(intent)
                check(Platform.launches.isEmpty()) { "A launch was requested after re-lock during resolution" }
                check(Platform.toasts.last() == me.kavishdevar.librepods.R.string.stem_shortcut_unlock)
                Platform.onResolve = {}
                Platform.keyguard.isDeviceLocked = false; Platform.keyguard.isKeyguardLocked = false
            }
        },
        "source_or_age_change_during_resolution_cannot_launch_or_fallback" to {
            for (change in 0..2) {
                var current = true
                val intent = postWithOwner { current }.send()
                Platform.onResolve = {
                    if (change == 1) SystemClock.now += STEM_SHORTCUT_REQUEST_LIFETIME_MS + 1
                    else current = false
                    if (change == 2) Platform.targetAvailable = false
                }
                dispatch(intent)
                check(Platform.launches.isEmpty()) { "Resolution outlived request (change=$change): ${Platform.launches}" }
                Platform.onResolve = {}
                Platform.targetAvailable = true
            }
        },
        "public_foreground_request_and_non_long_press_assistant_remain_valid" to {
            val visible = Activity().apply { focused = true }
            app.callbacks.onActivityResumed(visible)
            StemShortcutDispatcher.request(app, binding.side)
            dispatch(Platform.starts.single())
            check(Platform.launches == listOf(StemLaunchRequest.Target(binding.target!!)))
            Platform.starts.clear(); Platform.launches.clear()
            Platform.settings.values[binding.side.actionKey(StemShortcutPress.DOUBLE_PRESS)] = StemAction.DIGITAL_ASSISTANT.name
            StemShortcutDispatcher.request(app, binding.side, StemShortcutPress.DOUBLE_PRESS)
            dispatch(Platform.starts.single())
            check(Platform.launches == listOf(StemLaunchRequest.CurrentAssistant))
            app.callbacks.onActivityPaused(visible)
        },
        "changed_binding_or_age_is_rejected_at_destination" to {
            val old = post().send()
            Platform.settings.values[binding.side.targetKey] = "1|action|alarms"
            dispatch(old)
            check(Platform.launches.isEmpty())
            val expired = post().send()
            SystemClock.now += STEM_SHORTCUT_REQUEST_LIFETIME_MS + 1
            dispatch(expired)
            check(Platform.launches.isEmpty())
        },
    )
    var failures = 0
    for ((name, test) in cases) {
        if (args.isNotEmpty() && args[0] != name) continue
        Platform.launches.clear(); Platform.starts.clear(); Platform.toasts.clear()
        Platform.onResolve = {}
        Platform.permissionGranted = true; Platform.notifications.enabled = true
        Platform.notifications.channels["stem_shortcuts"]?.importance = 3
        Platform.keyguard.isDeviceLocked = false; Platform.keyguard.isKeyguardLocked = false
        Platform.settings.values[binding.side.actionKey] = binding.action.name
        Platform.settings.values[binding.side.targetKey] = StemShortcutCodec.encode(binding.target!!)!!
        Platform.targetAvailable = true; Platform.assistantAvailable = true
        try { test(); println("PASS $name") }
        catch (error: Throwable) { failures++; println("FAIL $name: ${error.message}") }
    }
    check(failures == 0) { "$failures integration cases failed" }
}
