package me.kavishdevar.librepods.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.ServiceManager

object SleepTimerManager {
    const val ACTION_APPLY_MODE = "me.kavishdevar.librepods.APPLY_SLEEP_TIMER_MODE"

    private const val TAG = "SleepTimer"
    private const val END_AT = "sleep_timer_end_at"
    private const val TARGET_MODE = "sleep_timer_target_mode"
    private const val REQUEST_CODE = 741

    fun start(context: Context, durationMinutes: Int, targetMode: Int): Long {
        require(durationMinutes > 0)
        require(targetMode in 1..4)
        val endAt = System.currentTimeMillis() + durationMinutes * 60_000L
        preferences(context).edit {
            putLong(END_AT, endAt)
            putInt(TARGET_MODE, targetMode)
        }
        schedule(context, endAt)
        return endAt
    }

    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
        preferences(context).edit {
            remove(END_AT)
            remove(TARGET_MODE)
        }
    }

    fun endAt(context: Context): Long {
        val endAt = preferences(context).getLong(END_AT, 0L)
        return endAt.takeIf { it > System.currentTimeMillis() } ?: 0L
    }

    fun targetMode(context: Context): Int =
        preferences(context).getInt(TARGET_MODE, 1).takeIf { it in 1..4 } ?: 1

    fun restore(context: Context) {
        val endAt = preferences(context).getLong(END_AT, 0L)
        if (endAt == 0L) return
        if (endAt <= System.currentTimeMillis()) {
            fire(context)
        } else {
            schedule(context, endAt)
        }
    }

    fun remainingMinutes(endAt: Long, now: Long = System.currentTimeMillis()): Long =
        ((endAt - now).coerceAtLeast(0L) + 59_999L) / 60_000L

    fun fire(context: Context) {
        val endAt = preferences(context).getLong(END_AT, 0L)
        if (endAt == 0L) return
        if (endAt > System.currentTimeMillis()) {
            schedule(context, endAt)
            return
        }

        val targetMode = preferences(context).getInt(TARGET_MODE, 1).takeIf { it in 1..4 } ?: 1
        cancel(context)
        val service = ServiceManager.getService()
        if (service != null) {
            service.setListeningMode(targetMode)
        } else {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AirPodsService::class.java).apply {
                        action = ACTION_APPLY_MODE
                        putExtra("mode", targetMode)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start AirPods service for sleep timer", e)
            }
        }
    }

    private fun schedule(context: Context, endAt: Long) {
        alarmManager(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            endAt,
            pendingIntent(context)
        )
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun alarmManager(context: Context) =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SleepTimerReceiver::class.java).setAction(ACTION_APPLY_MODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SleepTimerManager.ACTION_APPLY_MODE) {
            SleepTimerManager.fire(context)
        }
    }
}
