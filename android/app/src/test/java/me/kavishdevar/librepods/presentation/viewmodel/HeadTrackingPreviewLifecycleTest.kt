package me.kavishdevar.librepods.presentation.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import me.kavishdevar.librepods.services.HeadTrackingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class HeadTrackingPreviewLifecycleTest {
    private class Owner(initial: Lifecycle.State = Lifecycle.State.CREATED) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = initial }
    }

    @Test fun previewOnlyRunsWhileThePageIsStartedAndResumesOnReturn() {
        val owner = Owner()
        val viewModel = AirPodsViewModel()
        val preview = viewModel.beginHeadTrackingPreview(owner.lifecycle)
        assertFalse(viewModel.uiState.value.headTrackingActive)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(viewModel.uiState.value.headTrackingActive)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertFalse(viewModel.uiState.value.headTrackingActive)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(viewModel.uiState.value.headTrackingActive)
        preview.close()
        assertFalse(viewModel.uiState.value.headTrackingActive)
        assertEquals(0, owner.lifecycle.observerCount)
    }

    @Test fun manualPauseSurvivesBackgroundAndForeground() {
        val owner = Owner(Lifecycle.State.STARTED)
        val viewModel = AirPodsViewModel()
        val preview = viewModel.beginHeadTrackingPreview(owner.lifecycle)
        viewModel.stopHeadTracking()
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertFalse(viewModel.uiState.value.headTrackingActive)
        viewModel.startHeadTracking()
        assertTrue(viewModel.uiState.value.headTrackingActive)
        preview.close()
    }

    @Test fun startingWhileStoppedWaitsUntilThePageIsVisible() {
        val owner = Owner(Lifecycle.State.STARTED)
        val viewModel = AirPodsViewModel()
        val preview = viewModel.beginHeadTrackingPreview(owner.lifecycle)
        viewModel.stopHeadTracking()
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        viewModel.startHeadTracking()
        assertFalse(viewModel.uiState.value.headTrackingActive)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(viewModel.uiState.value.headTrackingActive)
        preview.close()
    }

    @Test fun replacedPageCannotStopOrRestartTheCurrentPreview() {
        val first = Owner(Lifecycle.State.STARTED)
        val replacement = Owner(Lifecycle.State.STARTED)
        val viewModel = AirPodsViewModel()
        val oldPreview = viewModel.beginHeadTrackingPreview(first.lifecycle)
        val preview = viewModel.beginHeadTrackingPreview(replacement.lifecycle)
        assertEquals(0, first.lifecycle.observerCount)
        first.lifecycle.currentState = Lifecycle.State.CREATED
        oldPreview.close()
        assertTrue(viewModel.uiState.value.headTrackingActive)
        replacement.lifecycle.currentState = Lifecycle.State.CREATED
        first.lifecycle.currentState = Lifecycle.State.STARTED
        assertFalse(viewModel.uiState.value.headTrackingActive)
        replacement.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(viewModel.uiState.value.headTrackingActive)
        preview.close()
    }

    @Test fun stoppedPageReleasesItsPreviewWithoutStoppingAnActiveGesture() {
        val owner = Owner(Lifecycle.State.STARTED)
        val viewModel = AirPodsViewModel()
        val preview = viewModel.beginHeadTrackingPreview(owner.lifecycle)
        val session = HeadTrackingSession()
        val previewLease = session.acquire(HeadTrackingSession.Consumer.PREVIEW, "AA")!!
        val gesture = session.acquire(HeadTrackingSession.Consumer.GESTURE, "AA")!!
        var releases = 0
        // The JVM test supplies the service-owned handle without constructing an Android Service.
        AirPodsViewModel::class.java.getDeclaredField("headPreviewLease").apply {
            isAccessible = true
            set(viewModel, Closeable { releases++; session.release(previewLease) })
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(1, releases)
        assertTrue(session.capture().enabled)
        preview.close()
        assertEquals(1, releases)
        assertFalse(session.release(gesture)!!.enabled)
    }

    @Test fun destroyAndLateDisposalReleaseLifecycleObservation() {
        val owner = Owner(Lifecycle.State.STARTED)
        val viewModel = AirPodsViewModel()
        val preview = viewModel.beginHeadTrackingPreview(owner.lifecycle)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertFalse(viewModel.uiState.value.headTrackingActive)
        assertEquals(0, owner.lifecycle.observerCount)
        preview.close()
        preview.close()
        assertFalse(viewModel.uiState.value.headTrackingActive)
    }

    @Test fun alreadyDestroyedPageCannotAcquireOrRetainAPreview() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        val viewModel = AirPodsViewModel()
        val preview = viewModel.beginHeadTrackingPreview(owner.lifecycle)
        assertFalse(viewModel.uiState.value.headTrackingActive)
        assertEquals(0, owner.lifecycle.observerCount)
        preview.close()
        assertFalse(viewModel.uiState.value.headTrackingActive)
    }
}
