package me.kavishdevar.librepods.presentation.overlays

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus

private class Fixture {
    val context = Context()
    var closes = 0
    val popup = PopupWindow(context) { closes++ }
    val view get() = context.inflater.lastView!!
    val video get() = view.findViewById<VideoView>(R.id.video)
    val battery = AirPodsNotifications.BatteryNotification().apply {
        setBatteryDirect(80, false, 70, true, 60, false)
    }
    // Any? keeps this harness compilable against the old Unit-returning source.
    fun open(artwork: Int? = null): Any? = popup.open("Test buds", battery, artwork)
    fun assertReleased() {
        check(context.windowManager.attached.isEmpty()) { "Overlay leaked" }
        check(context.receivers.isEmpty()) { "Battery receiver leaked" }
        check(Handler.pending.isEmpty()) { "Auto-close task leaked" }
        check(!video.playing) { "Video playback leaked" }
        check(video.completion == null) { "Video completion listener leaked" }
        check(ObjectAnimator.running.isEmpty()) { "Animation leaked" }
    }
}

private val cases = linkedMapOf<String, () -> Unit>(
    "permission_denial_releases_resources_and_allows_retry" to {
        val f = Fixture()
        f.context.windowManager.rejectAdd = true
        val result = f.open()
        f.assertReleased()
        check(result == false) { "Failed attachment reported success" }
        check(f.closes == 0)
        f.context.windowManager.rejectAdd = false
        check(f.open() == true)
        f.popup.close(); ObjectAnimator.finishAll()
        f.assertReleased(); check(f.closes == 1)
    },
    "partial_attach_failure_removes_view" to {
        val f = Fixture()
        f.context.windowManager.failAfterAttach = true
        val result = f.open()
        f.assertReleased()
        check(result == false); check(f.closes == 0)
    },
    "receiver_registration_failure_rolls_back_attachment" to {
        val f = Fixture()
        f.context.rejectRegistration = true
        val result = f.open()
        f.assertReleased()
        check(result == false); check(f.closes == 0)
        f.context.rejectRegistration = false
        check(f.open() == true)
        f.popup.close(); ObjectAnimator.finishAll(); f.assertReleased()
    },
    "timer_rejection_rolls_back_attachment" to {
        val f = Fixture()
        Handler.rejectPosts = true
        val result = f.open()
        f.assertReleased()
        check(result == false); check(f.closes == 0)
    },
    "partial_receiver_registration_failure_releases_dispatcher" to {
        val f = Fixture()
        f.context.failAfterRegistration = true
        val result = f.open()
        f.assertReleased()
        check(result == false); check(f.closes == 0)
        f.context.failAfterRegistration = false
        check(f.open() == true)
        f.popup.close(); ObjectAnimator.finishAll(); f.assertReleased()
    },
    "media_setup_failure_is_recoverable" to {
        val f = Fixture()
        f.video.failStart = true
        val result = f.open()
        f.assertReleased()
        check(result == false); check(f.closes == 0)
        f.video.failStart = false
        check(f.open() == true)
    },
    "entry_animation_failure_rolls_back_attachment" to {
        val f = Fixture()
        ObjectAnimator.rejectStart = true
        val result = f.open()
        f.assertReleased()
        check(result == false); check(f.closes == 0)
    },
    "repeated_open_keeps_one_window_receiver_and_timeout" to {
        val f = Fixture()
        check(f.open() == true)
        val firstTask = Handler.pending.single()
        Handler.advanceBy(1000)
        check(f.open() == true)
        check(f.context.windowManager.addCalls == 1)
        check(f.context.receivers.size == 1)
        check(Handler.pending.single() == firstTask) { "Repeated open extended popup lifetime" }
    },
    "timeout_closes_once_after_twelve_seconds" to {
        val f = Fixture()
        check(f.open() == true)
        ObjectAnimator.finishAll()
        Handler.advanceBy(11999)
        check(f.context.windowManager.attached.size == 1); check(f.closes == 0)
        Handler.advanceBy(1)
        check(f.context.receivers.isEmpty()); check(!f.video.playing)
        check(f.closes == 0)
        ObjectAnimator.finishAll()
        f.assertReleased(); check(f.closes == 1)
        f.popup.close(); ObjectAnimator.finishAll(); Handler.advanceBy(12000)
        check(f.closes == 1)
    },
    "manual_close_cancels_entry_and_timeout" to {
        val f = Fixture()
        check(f.open() == true)
        val entry = ObjectAnimator.running.single()
        f.view.findViewById<ImageButton>(R.id.close_button).performClick()
        check(entry !in ObjectAnimator.running) { "Entry animation survived close" }
        f.popup.close()
        check(f.open() == false) { "Open accepted during close" }
        check(ObjectAnimator.running.size == 1)
        ObjectAnimator.finishAll(); f.assertReleased(); check(f.closes == 1)
        entry.finish(); Handler.advanceBy(12000); check(f.closes == 1)
    },
    "closed_window_reopens_visible_with_fresh_resources" to {
        val f = Fixture()
        f.open(); f.popup.close(); ObjectAnimator.finishAll()
        check(f.open() == true)
        check(f.view.visibility == View.VISIBLE) { "Reopened window remains hidden" }
        check(f.context.receivers.size == 1); check(Handler.pending.size == 1)
        f.popup.close(); ObjectAnimator.finishAll(); f.assertReleased(); check(f.closes == 2)
    },
    "close_before_open_is_a_noop" to {
        val f = Fixture()
        f.popup.close(); ObjectAnimator.finishAll()
        check(f.closes == 0) { "Never-opened popup invoked close callback" }
        check(f.context.windowManager.removeCalls == 0)
        f.assertReleased(); check(f.open() == true)
    },
    "exit_animation_failure_still_removes_window" to {
        val f = Fixture()
        check(f.open() == true)
        ObjectAnimator.finishAll()
        ObjectAnimator.rejectStart = true
        f.popup.close()
        f.assertReleased(); check(f.closes == 1)
    },
    "outside_touch_dismisses_window" to {
        val f = Fixture()
        check(f.open() == true)
        check(f.view.dispatchTouchEvent(MotionEvent(MotionEvent.ACTION_OUTSIDE))) { "Outside touch was ignored" }
        ObjectAnimator.finishAll(); f.assertReleased(); check(f.closes == 1)
    },
    "artwork_path_does_not_start_video" to {
        val f = Fixture()
        check(f.open(1234) == true)
        check(!f.video.playing); check(f.video.visibility == View.GONE)
        val image = f.view.findViewById<ImageView>(R.id.connection_artwork)
        check(image.visibility == View.VISIBLE); check(image.imageResource == 1234)
        f.popup.close(); ObjectAnimator.finishAll(); f.assertReleased()
    },
    "battery_updates_stop_after_dismissal_on_both_api_paths" to {
        for (sdk in listOf(32, 35)) {
            Build.VERSION.SDK_INT = sdk
            val f = Fixture()
            check(f.open() == true)
            val left = f.view.findViewById<TextView>(R.id.left_battery)
            check(left.text.endsWith("80%"))
            val update = Intent(AirPodsNotifications.BATTERY_DATA).apply {
                data = arrayListOf(Battery(BatteryComponent.LEFT, 42, BatteryStatus.NOT_CHARGING))
            }
            f.context.broadcast(update)
            check(left.text.endsWith("42%"))
            f.popup.close(); ObjectAnimator.finishAll(); f.assertReleased()
            update.data = arrayListOf(Battery(BatteryComponent.LEFT, 10, BatteryStatus.NOT_CHARGING))
            f.context.broadcast(update); check(left.text.endsWith("42%"))
        }
    },
    "constructor_inflation_failure_acquires_no_runtime_resources" to {
        val context = Context().apply { inflater.rejectInflation = true }
        check(runCatching { PopupWindow(context) }.isFailure)
        check(context.windowManager.attached.isEmpty()); check(context.receivers.isEmpty())
        check(Handler.pending.isEmpty()); check(ObjectAnimator.running.isEmpty())
    },
)

fun main(args: Array<String>) {
    val selected = if (args.isEmpty()) cases else linkedMapOf(args.single() to cases.getValue(args.single()))
    var failed = 0
    for ((name, test) in selected) {
        Handler.reset(); ObjectAnimator.reset(); Build.VERSION.SDK_INT = 35
        try { test(); println("PASS $name") }
        catch (failure: Throwable) { failed++; println("FAIL $name: ${failure.message}"); failure.printStackTrace() }
    }
    check(failed == 0) { "$failed/${selected.size} popup window cases failed" }
    println("PASS ${selected.size} popup window cases")
}
