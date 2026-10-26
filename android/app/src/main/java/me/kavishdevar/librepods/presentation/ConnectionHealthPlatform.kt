package me.kavishdevar.librepods.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import me.kavishdevar.librepods.data.HealthPlatformEvidence

/** Read-only public Android signals. In particular, this never calls getProfileProxy. */
@SuppressLint("MissingPermission")
internal fun readConnectionHealthPlatform(context: Context): HealthPlatformEvidence {
    val observedAt = SystemClock.elapsedRealtime()
    val permission = runCatching {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }.getOrNull()
    val enabled = if (permission == true) runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    }.getOrNull() else null
    return HealthPlatformEvidence(observedAt, permission, enabled,
        batteryOptimizationExempt = runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull(),
        backgroundRestricted = runCatching {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
        }.getOrNull())
}

internal fun openConnectionHealthSettings(context: Context, action: ConnectionHealthAction): Boolean {
    val intent = when (action) {
        ConnectionHealthAction.BLUETOOTH_SETTINGS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        ConnectionHealthAction.APP_SETTINGS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
        ConnectionHealthAction.BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ConnectionHealthAction.DIAGNOSTICS -> return false
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}
