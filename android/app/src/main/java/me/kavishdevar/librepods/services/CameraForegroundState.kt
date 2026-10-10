package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType

internal val defaultCameraPackages = setOf(
    "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera",
    "com.oppo.camera", "com.motorola.camera2", "org.codeaurora.snapcam"
)

/** Custom package replacement must never remove a built-in camera from the set. */
internal class CameraForegroundState(private val defaults: Set<String> = defaultCameraPackages) {
    private var foregroundPackage: String? = null
    private var customPackage: String? = null
    var active = false
        private set

    fun windowChanged(packageName: String) {
        // Privacy indicator windows appear above a camera without replacing it.
        if (packageName == "com.android.systemui") return
        foregroundPackage = packageName
        update()
    }

    fun customPackageChanged(packageName: String?) {
        customPackage = packageName?.trim()?.takeIf { validCameraPackage(it) }
        update()
    }

    fun reset() { foregroundPackage = null; active = false }

    private fun update() {
        active = foregroundPackage != null && (foregroundPackage in defaults || foregroundPackage == customPackage)
    }
}

private val cameraPackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

internal fun validCameraPackage(value: String): Boolean =
    value.length <= 255 && value.matches(cameraPackagePattern)

internal fun cameraActionFromString(value: String?): StemPressType? =
    StemPressType.entries.firstOrNull {
        it.name == value && (it == StemPressType.SINGLE_PRESS || it == StemPressType.LONG_PRESS)
    }
