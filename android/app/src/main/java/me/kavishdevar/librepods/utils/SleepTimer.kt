package me.kavishdevar.librepods.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.UUID
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.batteryHistoryIdentity
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.ServiceManager

object SleepTimerManager {
    const val ACTION_APPLY_MODE = "me.kavishdevar.librepods.APPLY_SLEEP_TIMER_MODE"
    const val EXTRA_TOKEN = "sleep_timer_token"
    private const val END_AT = "sleep_timer_end_at"
    private const val TARGET_MODE = "sleep_timer_target_mode"
    private const val ADDRESS = "sleep_timer_address"
    private const val END_ELAPSED = "sleep_timer_end_elapsed"
    private const val BOOT = "sleep_timer_boot"
    private const val DELIVERY_WALL = "sleep_timer_delivery_wall"
    private const val DELIVERY_ELAPSED = "sleep_timer_delivery_elapsed"
    private const val REQUEST_CODE = 741
    private const val DELIVERY_GRACE = 30_000L
    private val lock = Any()
    private var bootCount: Int? = null

    fun start(context: Context, durationMinutes: Int, targetMode: Int): Long = synchronized(lock) {
        require(durationMinutes in 1..1440)
        require(targetMode in 1..4)
        val address = batteryHistoryIdentity(selected(context))
        if (address == null) {
            Toast.makeText(context, R.string.sleep_timer_select_device, Toast.LENGTH_LONG).show()
            return@synchronized 0L
        }
        clear(context)
        val now = clock(context)
        val interval = durationMinutes * 60_000L
        val record = SleepTimerRecord(UUID.randomUUID().toString(), address, targetMode,
            now.wall + interval, now.elapsed + interval, now.boot)
        save(context, record)
        schedule(context, record)
        record.endWall
    }

    fun cancel(context: Context) = synchronized(lock) { clear(context) }

    fun endAt(context: Context): Long = synchronized(lock) {
        val record = current(context) ?: return@synchronized 0L
        val now = clock(context)
        val remaining = if (record.delivering) record.deliveryRemaining(now) else record.remaining(now)
        remaining.takeIf { it > 0 }?.let { now.wall + it } ?: 0L
    }

    fun isAwaitingDelivery(context: Context): Boolean = synchronized(lock) {
        current(context)?.let { it.delivering && it.deliveryRemaining(clock(context)) > 0 } ?: false
    }

    fun targetMode(context: Context): Int = synchronized(lock) { current(context)?.mode ?: 1 }

    internal fun ownsToken(context: Context, token: String): Boolean = synchronized(lock) {
        current(context)?.token == token
    }

    @JvmOverloads
    fun restore(context: Context, afterReboot: Boolean = false) {
        val token = synchronized(lock) {
            // BOOT_COMPLETED is authoritative even when the persisted/global counter is missing.
            if (afterReboot) bootCount = null
            val record = current(context) ?: run {
                // Legacy timers did not record a peer, so cannot safely be reassigned on upgrade.
                if (preferences(context).contains(END_AT)) clear(context)
                return@synchronized null
            }
            val now = clock(context)
            if (!record.matches(selected(context)) || record.staleAfterReboot(now, afterReboot) ||
                (record.delivering && record.deliveryRemaining(now) == 0L)) {
                clear(context)
                return@synchronized null
            }
            val rebased = record.rebase(now, afterReboot)
            save(context, rebased)
            schedule(context, rebased)
            if (rebased.delivering || rebased.remaining(now) == 0L) rebased.token else null
        }
        token?.let { fire(context, it) }
    }

    fun remainingMinutes(endAt: Long, now: Long = System.currentTimeMillis()): Long =
        ((endAt - now).coerceAtLeast(0L) + 59_999L) / 60_000L

    fun fire(context: Context, token: String?) {
        if (token == null) return // Old/unidentified alarm deliveries cannot claim a newer task.
        val record = prepareDelivery(context, token) ?: return
        val service = ServiceManager.getService()
        if (service != null) service.requestSleepTimerDelivery()
        else try {
            ContextCompat.startForegroundService(context, Intent(context, AirPodsService::class.java).apply {
                action = ACTION_APPLY_MODE
                putExtra(EXTRA_TOKEN, record.token)
            })
        } catch (error: Exception) {
            Log.w("SleepTimer", "Sleep timer is waiting for an available service", error)
        }
    }

    /** No monitor is held while the caller writes a Bluetooth packet. */
    internal fun prepareDelivery(context: Context, expectedToken: String? = null): SleepTimerRecord? = synchronized(lock) {
        val record = current(context) ?: return@synchronized null
        if (expectedToken != null && record.token != expectedToken) return@synchronized null
        val now = clock(context)
        if (!record.matches(selected(context)) || record.staleAfterReboot(now)) {
            clear(context); return@synchronized null
        }
        if (record.delivering) {
            if (record.deliveryRemaining(now) > 0) return@synchronized record
            clear(context); return@synchronized null
        }
        if (record.remaining(now) > 0) {
            if (expectedToken != null) schedule(context, record)
            return@synchronized null
        }
        val pending = record.beginDelivery(now, DELIVERY_GRACE)
        save(context, pending)
        schedule(context, pending)
        pending
    }

    internal fun canDeliver(context: Context, record: SleepTimerRecord, address: String): Boolean = synchronized(lock) {
        val current = current(context)
        current?.token == record.token && current.delivering && current.matches(address) &&
            current.matches(selected(context)) && current.deliveryRemaining(clock(context)) > 0
    }

    internal fun complete(context: Context, token: String) = synchronized(lock) {
        if (current(context)?.token == token) clear(context)
    }

    private fun current(context: Context): SleepTimerRecord? = preferences(context).let { prefs ->
        SleepTimerRecord(prefs.getString(EXTRA_TOKEN, "") ?: "", prefs.getString(ADDRESS, "") ?: "",
            prefs.getInt(TARGET_MODE, 1), prefs.getLong(END_AT, 0), prefs.getLong(END_ELAPSED, 0),
            prefs.getInt(BOOT, -1), prefs.getLong(DELIVERY_WALL, 0), prefs.getLong(DELIVERY_ELAPSED, 0))
            .takeIf { it.valid() }
    }

    private fun save(context: Context, record: SleepTimerRecord) = preferences(context).edit {
        putString(EXTRA_TOKEN, record.token); putString(ADDRESS, record.address); putInt(TARGET_MODE, record.mode)
        putLong(END_AT, record.endWall); putLong(END_ELAPSED, record.endElapsed); putInt(BOOT, record.boot)
        putLong(DELIVERY_WALL, record.deliveryWall); putLong(DELIVERY_ELAPSED, record.deliveryElapsed)
    }

    private fun clear(context: Context) {
        current(context)?.let { cancelAlarm(context, it.token) }
        cancelAlarm(context, null)
        preferences(context).edit {
            listOf(EXTRA_TOKEN, ADDRESS, END_AT, END_ELAPSED, TARGET_MODE, BOOT, DELIVERY_WALL, DELIVERY_ELAPSED)
                .forEach { remove(it) }
        }
    }

    private fun cancelAlarm(context: Context, token: String?) {
        val operation = pendingIntent(context, token)
        alarmManager(context).cancel(operation)
        operation.cancel()
    }

    private fun schedule(context: Context, record: SleepTimerRecord) {
        val monotonic = record.boot >= 0 && record.boot == clock(context).boot
        val deadline = if (record.delivering) {
            if (monotonic) record.deliveryElapsed else record.deliveryWall
        } else if (monotonic) record.endElapsed else record.endWall
        alarmManager(context).setAndAllowWhileIdle(
            if (monotonic) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP,
            deadline, pendingIntent(context, record.token))
    }

    private fun clock(context: Context) = TimerClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        bootCount ?: Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).also { bootCount = it })
    private fun selected(context: Context) = preferences(context).getString("mac_address", "") ?: ""
    private fun preferences(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun pendingIntent(context: Context, token: String?): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, SleepTimerReceiver::class.java).setAction(ACTION_APPLY_MODE).apply {
            if (token != null) {
                data = "librepods-sleep://timer/$token".toUri()
                putExtra(EXTRA_TOKEN, token)
            }
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SleepTimerManager.ACTION_APPLY_MODE) {
            SleepTimerManager.fire(context, intent.getStringExtra(SleepTimerManager.EXTRA_TOKEN))
        }
    }
}
