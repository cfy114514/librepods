package me.kavishdevar.librepods.integration

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import me.kavishdevar.librepods.presentation.ConnectionHealthAction
import me.kavishdevar.librepods.presentation.openConnectionHealthSettings
import me.kavishdevar.librepods.presentation.performConnectionHealthAction
import me.kavishdevar.librepods.presentation.readConnectionHealthPlatform
import me.kavishdevar.librepods.data.HealthAudioProfile
import me.kavishdevar.librepods.data.HealthProfileState
import me.kavishdevar.librepods.data.HealthRowKind
import me.kavishdevar.librepods.data.HealthStatus
import me.kavishdevar.librepods.presentation.ConnectionHealthObserver
import me.kavishdevar.librepods.services.ConnectionHealthMonitor

private class PlatformContext : Context() {
    val services = mutableMapOf<Class<*>, Any>()
    val serviceReads = mutableListOf<Class<*>>()
    val permissionReads = mutableListOf<String>()
    val launches = mutableListOf<Intent>()
    var permission = PackageManager.PERMISSION_GRANTED
    var permissionError: Exception? = null
    var serviceError: Exception? = null
    var launchError: Exception? = null
    var onServiceRead: () -> Unit = {}
    override fun checkSelfPermission(permission: String): Int {
        permissionReads.add(permission)
        permissionError?.let { throw it }
        return this.permission
    }
    override fun <T> getSystemService(serviceClass: Class<T>): T? {
        serviceReads.add(serviceClass)
        onServiceRead()
        serviceError?.let { throw it }
        return services[serviceClass]?.let(serviceClass::cast)
    }
    override fun startActivity(intent: Intent) {
        launches.add(intent)
        launchError?.let { throw it }
    }
}

private fun publicPlatformReadsPreserveEachIndependentResult() {
    val context = PlatformContext()
    context.services[BluetoothManager::class.java] = object : BluetoothManager() {
        override val adapter = object : BluetoothAdapter() { override val isEnabled = true }
    }
    context.services[PowerManager::class.java] = object : PowerManager() {
        override fun isIgnoringBatteryOptimizations(packageName: String): Boolean {
            expectEquals(context.packageName, packageName, "Battery optimization query is scoped to this app")
            return false
        }
    }
    context.services[ActivityManager::class.java] = object : ActivityManager() {
        override val isBackgroundRestricted = true
    }
    SystemClock.now = 123_456
    val result = readConnectionHealthPlatform(context)
    expectEquals(123_456L, result.observedAt, "Actual public reader captures monotonic timestamp")
    expectEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), context.permissionReads, "One explicit Bluetooth permission read")
    expectEquals(listOf(BluetoothManager::class.java, PowerManager::class.java, ActivityManager::class.java), context.serviceReads, "One read of each requested system service")
    expectEquals(true, result.bluetoothPermission, "Permission read")
    expectEquals(true, result.bluetoothEnabled, "Adapter read")
    expectEquals(false, result.batteryOptimizationExempt, "Optimization read")
    expectEquals(true, result.backgroundRestricted, "Background restriction read")
    expect(context.launches.isEmpty(), "Reading health never opens settings")
}

private fun deniedOrUnavailablePermissionSkipsAdapterRead() {
    for (permissionThrows in listOf(false, true)) {
        val context = PlatformContext()
        context.permission = PackageManager.PERMISSION_DENIED
        if (permissionThrows) context.permissionError = SecurityException("permission query unavailable")
        val result = readConnectionHealthPlatform(context)
        expectEquals(if (permissionThrows) null else false, result.bluetoothPermission, "Permission denial and missing permission evidence remain distinct")
        expect(result.bluetoothEnabled == null, "Skipped adapter read stays unknown")
        expect(BluetoothManager::class.java !in context.serviceReads, "No Bluetooth service read without successful permission evidence")
        expect(context.launches.isEmpty(), "Permission read does not launch an approval flow")
    }
}

private fun missingOrThrowingServicesStayUnknown() {
    val absent = readConnectionHealthPlatform(PlatformContext())
    expectEquals(true, absent.bluetoothPermission, "Known permission survives absent service")
    expect(absent.bluetoothEnabled == null && absent.batteryOptimizationExempt == null && absent.backgroundRestricted == null,
        "Absent system service has no inferred state")
    val throwing = PlatformContext().apply { serviceError = SecurityException("service query denied") }
    val denied = readConnectionHealthPlatform(throwing)
    expectEquals(true, denied.bluetoothPermission, "A later service read failure does not rewrite earlier permission observation")
    expect(denied.bluetoothEnabled == null && denied.batteryOptimizationExempt == null && denied.backgroundRestricted == null,
        "Failed service reads stay unknown")
    val revoked = PlatformContext()
    revoked.services[BluetoothManager::class.java] = object : BluetoothManager() {
        override val adapter = object : BluetoothAdapter() {
            override val isEnabled: Boolean get() = throw SecurityException("revoked between permission and adapter read")
        }
    }
    val race = readConnectionHealthPlatform(revoked)
    expectEquals(true, race.bluetoothPermission, "Initial permission result is retained")
    expect(race.bluetoothEnabled == null, "Permission race does not invent adapter off/connected state")
}

private fun manualSettingsLaunchExactlyTheRequestedDestination() {
    val destinations = mapOf(
        ConnectionHealthAction.BLUETOOTH_SETTINGS to Settings.ACTION_BLUETOOTH_SETTINGS,
        ConnectionHealthAction.APP_SETTINGS to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        ConnectionHealthAction.BATTERY_SETTINGS to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    )
    for ((action, expectedAction) in destinations) {
        val context = PlatformContext()
        expect(performConnectionHealthAction(action, { openConnectionHealthSettings(context, it) }), "Manual action reports successful launch")
        val launched = context.launches.single()
        expectEquals(expectedAction, launched.action, "Only requested settings destination is launched")
        expect(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "Context launch carries NEW_TASK")
        expectEquals(if (action == ConnectionHealthAction.APP_SETTINGS) "package:${context.packageName}" else null,
            launched.data?.toString(), "App details are scoped; other settings are general destinations")
        expect(context.serviceReads.isEmpty() && context.permissionReads.isEmpty(), "Manual navigation does not perform unrelated transport reads")
    }
    val context = PlatformContext()
    expect(!openConnectionHealthSettings(context, ConnectionHealthAction.DIAGNOSTICS), "Diagnostics is not a settings intent")
    expect(context.launches.isEmpty(), "Unmapped destination has no settings launch")
}

private fun delayedPublicReadKeepsOriginalTimestampAndExpires() {
    val context = PlatformContext()
    SystemClock.now = MINUTE
    val monitor = ConnectionHealthMonitor { SystemClock.now }
    val selection = requireNotNull(monitor.selectPeer(PEER_A))
    monitor.started()
    monitor.profileChanged(selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
    val observer = ConnectionHealthObserver(PEER_A)
    observer.start()
    val ticket = requireNotNull(observer.beginRead())
    context.onServiceRead = { SystemClock.now = MINUTE + 20_000 }
    context.services[BluetoothManager::class.java] = object : BluetoothManager() {
        override val adapter = object : BluetoothAdapter() { override val isEnabled = true }
    }
    val platform = readConnectionHealthPlatform(context)
    expectEquals(MINUTE, platform.observedAt, "Slow public read is stamped before system calls")
    val report = requireNotNull(observer.publish(ticket, monitor.snapshot(), platform, SystemClock.now))
    expectEquals(HealthStatus.UNKNOWN, report.rows.single { it.kind == HealthRowKind.MEDIA }.status,
        "Platform evidence already expired during its own read cannot establish connected status")
    expectEquals(HealthStatus.SERVICE_RESPONDING, report.rows.single { it.kind == HealthRowKind.SERVICE }.status,
        "Separately fresh service response remains independent")
}

private fun failedActionsReturnFailureWithoutFallbackOrRetry() {
    for (failure in listOf(SecurityException("blocked activity"), IllegalStateException("activity missing"))) {
        val context = PlatformContext().apply { launchError = failure }
        expect(!performConnectionHealthAction(ConnectionHealthAction.APP_SETTINGS,
            { openConnectionHealthSettings(context, it) }), "Android launch failure reaches caller")
        expectEquals(1, context.launches.size, "One gesture does not retry or fall back to another destination")
    }
    var attempts = 0
    expect(!performConnectionHealthAction(ConnectionHealthAction.BATTERY_SETTINGS, { attempts++; false }), "Explicit navigation rejection is visible")
    expectEquals(1, attempts, "Rejected destination is attempted once")
    expect(!performConnectionHealthAction(ConnectionHealthAction.BLUETOOTH_SETTINGS,
        { throw IllegalStateException("handler failed") }), "Throwing handler is a visible failure")
    expect(!performConnectionHealthAction(ConnectionHealthAction.DIAGNOSTICS, { error("Wrong destination") }), "Missing diagnostics handler is a failure")
    var diagnostics = 0
    expect(performConnectionHealthAction(ConnectionHealthAction.DIAGNOSTICS,
        { error("Diagnostics must not open settings") }, { diagnostics++ }), "Diagnostics success")
    expectEquals(1, diagnostics, "Diagnostics callback runs once")
    expect(!performConnectionHealthAction(ConnectionHealthAction.DIAGNOSTICS,
        { error("Wrong destination") }, { throw IllegalStateException("diagnostics missing") }), "Diagnostics exception reaches caller as failure")
}

internal fun platformCases(): List<IntegrationCase> = listOf(
    "health_public_platform_reads_preserve_independent_signals" to ::publicPlatformReadsPreserveEachIndependentResult,
    "health_denied_or_missing_permission_skips_adapter_read" to ::deniedOrUnavailablePermissionSkipsAdapterRead,
    "health_missing_or_throwing_services_stay_unknown" to ::missingOrThrowingServicesStayUnknown,
    "health_delayed_public_read_keeps_receipt_time_and_expires" to ::delayedPublicReadKeepsOriginalTimestampAndExpires,
    "health_manual_settings_launch_only_requested_destination" to ::manualSettingsLaunchExactlyTheRequestedDestination,
    "health_manual_action_failures_have_no_fallback_or_retry" to ::failedActionsReturnFailureWithoutFallbackOrRetry,
)
