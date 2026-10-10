package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class CameraForegroundStateTest {
    @Test fun privacyIndicatorDoesNotReplaceCameraButOtherAppsAndScreenOffDo() {
        val foreground = CameraForegroundState()
        foreground.windowChanged("com.sec.android.app.camera")
        assertTrue(foreground.active)
        repeat(10_000) { foreground.windowChanged("com.android.systemui"); assertTrue(foreground.active) }
        foreground.windowChanged("com.android.settings")
        assertFalse(foreground.active)
        foreground.windowChanged("com.sec.android.app.camera")
        foreground.reset()
        assertFalse(foreground.active)
        foreground.windowChanged("com.android.systemui")
        assertFalse(foreground.active)
    }

    @Test fun replacingCustomCameraDoesNotRemoveDefaultOrKeepOldCustom() {
        val foreground = CameraForegroundState()
        foreground.customPackageChanged("com.sec.android.app.camera")
        foreground.customPackageChanged("net.example.camera")
        foreground.windowChanged("com.sec.android.app.camera")
        assertTrue(foreground.active)
        foreground.windowChanged("net.example.camera")
        assertTrue(foreground.active)
        foreground.customPackageChanged(" net.example.other ")
        assertFalse(foreground.active)
        foreground.windowChanged("net.example.other")
        assertTrue(foreground.active)
        foreground.customPackageChanged(null)
        assertFalse(foreground.active)
    }

    @Test fun invalidPersistedCameraSettingsAreIgnored() {
        assertNull(cameraActionFromString("DOUBLE_PRESS"))
        assertNull(cameraActionFromString("future-action"))
        assertNull(cameraActionFromString(null))
        assertNotNull(cameraActionFromString("SINGLE_PRESS"))
        assertNotNull(cameraActionFromString("LONG_PRESS"))
        assertFalse(validCameraPackage("../bin/sh"))
        assertFalse(validCameraPackage("net.example.camera; command"))
        assertFalse(validCameraPackage(""))
        assertTrue(validCameraPackage("net.example.camera2"))
    }
}
