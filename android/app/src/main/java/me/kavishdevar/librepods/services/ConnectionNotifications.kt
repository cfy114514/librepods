package me.kavishdevar.librepods.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.utils.LatestPublication

internal data class ConnectionNotice(val name: String, val batteries: List<Battery>, val canReconnect: Boolean,
                                     val peer: String, val selection: Long, val deviceSession: Long, val socket: android.bluetooth.BluetoothSocket)

internal object ConnectionNotifications {
    private var publisher: LatestPublication<ConnectionNotice>? = null

    @Synchronized fun claim(context: Context): LatestPublication.Lease<ConnectionNotice> {
        val application = context.applicationContext
        val updates = publisher ?: LatestPublication<ConnectionNotice>(Dispatchers.IO,
            publish = { notice, current -> publish(application, notice, current) },
            clear = { application.getSystemService(NotificationManager::class.java).cancel(2) },
            onError = { Log.w("ConnectionNotifications", "Connection notification update failed", it) }
        ).also { publisher = it }
        return updates.claim()
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun publish(context: Context, notice: ConnectionNotice, current: () -> Boolean): Boolean {
        if (!current()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        // notify() can return normally while permission/channel settings suppress delivery.
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel("airpods_connection_status")?.importance.let {
                it == null || it == NotificationManager.IMPORTANCE_NONE
            }) return false
        val contentIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun component(id: Int, label: String): String = notice.batteries.find { it.component == id }?.let {
            if (it.status == BatteryStatus.DISCONNECTED || it.level !in 0..100) "" else
                "$label: ${if (it.status == BatteryStatus.CHARGING || it.status == BatteryStatus.OPTIMIZED_CHARGING) "⚡" else ""} ${it.level}%"
        }.orEmpty()
        val notification = NotificationCompat.Builder(context, "airpods_connection_status")
            .setSmallIcon(R.drawable.airpods).setContentTitle(notice.name)
            .setContentText(listOf(component(BatteryComponent.LEFT, "L"), component(BatteryComponent.RIGHT, "R"),
                component(BatteryComponent.CASE, "Case")).filter(String::isNotEmpty).joinToString(" "))
            .setContentIntent(contentIntent).setCategory(Notification.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
        if (notice.canReconnect) notification.addAction(R.drawable.ic_bluetooth, context.getString(R.string.notification_reconnect),
            PendingIntent.getService(context, 0, Intent(context, AirPodsService::class.java).apply {
                action = "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE"
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val built = notification.build()
        if (!current()) return false
        manager.notify(2, built)
        if (!current()) return false
        manager.cancel(1)
        return current()
    }
}
