/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.ExperimentalMaterial3Api
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryHistorySnapshot
import me.kavishdevar.librepods.data.BatteryHistoryStore
import me.kavishdevar.librepods.data.displayBatteries
import me.kavishdevar.librepods.services.ServiceManager
import java.text.DateFormat
import java.util.Date

class BatteryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val service = ServiceManager.getService()
        if (service != null) service.updateBatteryWidget()
        else OfflineWidgetUpdates.request(context, OfflineWidgetUpdates.Kind.BATTERY) { goAsync() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf())
        } else super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    companion object {
        private const val ACTION_REFRESH = "me.kavishdevar.librepods.REFRESH_BATTERY_WIDGETS"
        internal fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(context, BatteryWidget::class.java).setAction(ACTION_REFRESH))
        }
        @OptIn(ExperimentalMaterial3Api::class)
        internal fun createViews(
            context: Context, widgetId: Int, live: List<Battery> = emptyList(), liveAvailable: Boolean = false,
            history: BatteryHistorySnapshot = BatteryHistoryStore(context).snapshot()
        ): RemoteViews {
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val showPhone = settings.getBoolean("show_phone_battery_in_widget", true)
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val sizing = batteryWidgetSizing(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT), showPhone)
            val batteries = displayBatteries(settings.getString("mac_address", "") ?: "", live, liveAvailable, history,
                settings.getBoolean("show_battery_history", true), System.currentTimeMillis(),
                settings.getInt("battery_history_days", 7).coerceIn(1, 30) * 86_400_000L)
            val views = RemoteViews(context.packageName, R.layout.battery_widget)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.battery_widget, open)
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val textIds = intArrayOf(R.id.left_battery_widget, R.id.right_battery_widget, R.id.case_battery_widget)
            val progressIds = intArrayOf(R.id.left_battery_progress, R.id.right_battery_progress, R.id.case_battery_progress)
            val chargingIds = intArrayOf(R.id.left_charging_icon, R.id.right_charging_icon, R.id.case_charging_icon)
            val circleIds = intArrayOf(R.id.left_battery_circle, R.id.right_battery_circle, R.id.case_battery_circle, R.id.phone_battery_circle)
            val names = intArrayOf(R.string.left, R.string.right, R.string.case_alt)
            val shortNames = intArrayOf(R.string.battery_left_short, R.string.battery_right_short, R.string.battery_case_short)
            batteries.forEachIndexed { index, battery ->
                val value = battery.level?.let { "$it%${if (battery.historical) "*" else ""}${if (sizing.compact && battery.charging) "⚡" else ""}" } ?: "—"
                views.setTextViewText(textIds[index], if (sizing.compact) "${context.getString(shortNames[index])}\n$value" else value)
                views.setTextViewTextSize(textIds[index], TypedValue.COMPLEX_UNIT_SP, sizing.textSp)
                views.setProgressBar(progressIds[index], 100, battery.level ?: 0, false)
                views.setViewVisibility(chargingIds[index], if (battery.charging && !sizing.compact) View.VISIBLE else View.GONE)
                val chargingSize = if (sizing.circleDp < 90f) 16f else 20f
                views.setViewLayoutWidth(chargingIds[index], chargingSize, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(chargingIds[index], chargingSize, TypedValue.COMPLEX_UNIT_DIP)
                val description = if (battery.historical) context.getString(R.string.battery_historical_accessibility,
                    context.getString(names[index]), battery.level, dateFormat.format(Date(battery.observedAt!!)))
                else context.getString(names[index]) + ": " + value
                views.setContentDescription(textIds[index], description)
            }
            for (circle in circleIds) {
                views.setViewVisibility(circle, if (sizing.compact) View.GONE else View.VISIBLE)
                views.setViewLayoutWidth(circle, sizing.circleDp, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(circle, sizing.circleDp, TypedValue.COMPLEX_UNIT_DIP)
            }
            views.setViewVisibility(R.id.phone_battery_widget_container, if (showPhone) View.VISIBLE else View.GONE)
            if (showPhone) {
                val manager = context.getSystemService(BatteryManager::class.java)
                val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
                val charging = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                val value = level?.let { "$it%${if (sizing.compact && charging) "⚡" else ""}" } ?: "—"
                views.setTextViewText(R.id.phone_battery_widget, if (sizing.compact) "${context.getString(R.string.battery_phone_short)}\n$value" else value)
                views.setTextViewTextSize(R.id.phone_battery_widget, TypedValue.COMPLEX_UNIT_SP, sizing.textSp)
                views.setProgressBar(R.id.phone_battery_progress, 100, level ?: 0, false)
                views.setContentDescription(R.id.phone_battery_widget, context.getString(R.string.phone) + ": " + value)
                views.setViewVisibility(R.id.phone_charging_icon,
                    if (charging && !sizing.compact) View.VISIBLE else View.GONE)
                val chargingSize = if (sizing.circleDp < 90f) 16f else 20f
                views.setViewLayoutWidth(R.id.phone_charging_icon, chargingSize, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.phone_charging_icon, chargingSize, TypedValue.COMPLEX_UNIT_DIP)
            }
            val oldestHistory = batteries.filter { it.historical }.mapNotNull { it.observedAt }.minOrNull()
            views.setViewVisibility(R.id.battery_history_widget_caption, if (oldestHistory == null) View.GONE else View.VISIBLE)
            if (oldestHistory != null) views.setTextViewText(R.id.battery_history_widget_caption,
                context.getString(R.string.battery_widget_history_caption, dateFormat.format(Date(oldestHistory))))
            return views
        }
    }
}
