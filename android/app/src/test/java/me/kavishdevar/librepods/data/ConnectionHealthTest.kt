package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthTest {
    private val peer = "AA:BB:CC:DD:EE:01"
    private val now = 200_000L
    private val platform = HealthPlatformEvidence(now, bluetoothPermission = true, bluetoothEnabled = true,
        batteryOptimizationExempt = true, backgroundRestricted = false)
    private fun snapshot(profiles: Map<HealthAudioProfile, HealthProfileEvidence> = emptyMap(),
                         control: HealthControlEvidence? = null) =
        ConnectionHealthSnapshot(peer, 1, HealthServiceState.STARTED, now, profiles, control)
    private fun ConnectionHealthReport.status(kind: HealthRowKind) = rows.first { it.kind == kind }.status

    @Test fun serviceResponseAndSocketOpenAreNotEvidenceOfAudioOrReceivedData() {
        val value = connectionHealthReport(peer, snapshot(control = HealthControlEvidence(true, now)), platform, now)
        assertEquals(HealthStatus.UNKNOWN, value.status(HealthRowKind.MEDIA))
        assertEquals(HealthStatus.UNKNOWN, value.status(HealthRowKind.CALLS))
        assertEquals(HealthStatus.WAITING_FOR_DATA, value.status(HealthRowKind.CONTROL))
        assertEquals(HealthStatus.SERVICE_RESPONDING, value.status(HealthRowKind.SERVICE))
    }

    @Test fun profileAndControlEvidenceRemainIndependent() {
        val value = connectionHealthReport(peer, snapshot(mapOf(
            HealthAudioProfile.MEDIA to HealthProfileEvidence(HealthProfileState.CONNECTED, now),
            HealthAudioProfile.CALLS to HealthProfileEvidence(HealthProfileState.DISCONNECTED, now)),
            HealthControlEvidence(false, now)), platform, now)
        assertEquals(HealthStatus.CONNECTED, value.status(HealthRowKind.MEDIA))
        assertEquals(HealthStatus.DISCONNECTED, value.status(HealthRowKind.CALLS))
        assertEquals(HealthStatus.DISCONNECTED, value.status(HealthRowKind.CONTROL))
    }

    @Test fun expiredEvidenceRetainsLastObservationWithoutClaimingCurrentState() {
        val old = now - HEALTH_EVIDENCE_MAX_AGE_MS - 1
        val value = connectionHealthReport(peer, snapshot(mapOf(
            HealthAudioProfile.MEDIA to HealthProfileEvidence(HealthProfileState.CONNECTED, old)),
            HealthControlEvidence(true, old, old)), platform, now)
        assertEquals(HealthStatus.STALE, value.status(HealthRowKind.MEDIA))
        assertEquals(HealthProfileState.CONNECTED, value.rows.first().lastProfileState)
        assertEquals(HealthStatus.STALE, value.status(HealthRowKind.CONTROL))
    }

    @Test fun recentInboundPacketIsRequiredForReceivingState() {
        val value = connectionHealthReport(peer, snapshot(control = HealthControlEvidence(true, now - 1000, now)), platform, now)
        assertEquals(HealthStatus.RECEIVING_DATA, value.status(HealthRowKind.CONTROL))
    }

    @Test fun anotherPeerCannotSupplyAudioOrControlEvidence() {
        val other = snapshot(mapOf(HealthAudioProfile.MEDIA to HealthProfileEvidence(HealthProfileState.CONNECTED, now)),
            HealthControlEvidence(true, now, now)).copy(peer = "AA:BB:CC:DD:EE:02")
        val value = connectionHealthReport(peer, other, platform, now)
        assertEquals(HealthStatus.UNKNOWN, value.status(HealthRowKind.MEDIA))
        assertEquals(HealthStatus.UNKNOWN, value.status(HealthRowKind.CONTROL))
    }

    @Test fun missingPermissionAndBluetoothOffOverrideCachedConnections() {
        val connected = snapshot(mapOf(HealthAudioProfile.MEDIA to HealthProfileEvidence(HealthProfileState.CONNECTED, now)),
            HealthControlEvidence(true, now, now))
        val denied = connectionHealthReport(peer, connected, platform.copy(bluetoothPermission = false), now)
        assertTrue(denied.permissionRequired)
        assertEquals(HealthStatus.PERMISSION_REQUIRED, denied.status(HealthRowKind.MEDIA))
        assertEquals(HealthStatus.PERMISSION_REQUIRED, denied.status(HealthRowKind.CONTROL))
        assertEquals(HealthStatus.SERVICE_RESPONDING, denied.status(HealthRowKind.SERVICE))
        val off = connectionHealthReport(peer, connected, platform.copy(bluetoothEnabled = false), now)
        assertEquals(HealthStatus.BLUETOOTH_OFF, off.status(HealthRowKind.CALLS))
    }

    @Test fun unavailablePlatformAndStalePermissionChecksCannotBecomeDenialOrConnection() {
        val connected = snapshot(mapOf(HealthAudioProfile.MEDIA to HealthProfileEvidence(HealthProfileState.CONNECTED, now)))
        val unknown = connectionHealthReport(peer, connected, null, now)
        assertFalse(unknown.permissionRequired)
        assertEquals(HealthStatus.UNKNOWN, unknown.status(HealthRowKind.MEDIA))
        val expired = connectionHealthReport(peer, connected,
            platform.copy(observedAt = now - HEALTH_SAMPLE_MAX_AGE_MS - 1, bluetoothPermission = false), now)
        assertFalse(expired.permissionRequired)
        assertEquals(HealthStatus.UNKNOWN, expired.status(HealthRowKind.MEDIA))
    }

    @Test fun staleServiceSnapshotCannotRevalidateCachedEvidence() {
        val connected = snapshot(mapOf(HealthAudioProfile.MEDIA to HealthProfileEvidence(HealthProfileState.CONNECTED, now)),
            HealthControlEvidence(true, now, now)).copy(observedAt = now - HEALTH_SAMPLE_MAX_AGE_MS - 1)
        val value = connectionHealthReport(peer, connected, platform, now)
        assertEquals(HealthStatus.STALE, value.status(HealthRowKind.MEDIA))
        assertEquals(HealthStatus.STALE, value.status(HealthRowKind.CONTROL))
        assertEquals(HealthStatus.STALE, value.status(HealthRowKind.SERVICE))
    }

    @Test fun backgroundRestrictionsAreSeparateFromForegroundServiceResponsiveness() {
        val value = connectionHealthReport(peer, snapshot(), platform.copy(backgroundRestricted = true), now)
        assertEquals(HealthBackgroundPolicy.RESTRICTED, value.backgroundPolicy)
        assertEquals(HealthStatus.SERVICE_RESPONDING, value.status(HealthRowKind.SERVICE))
        assertEquals(HealthBackgroundPolicy.UNKNOWN, connectionHealthReport(peer, snapshot(),
            platform.copy(backgroundRestricted = null), now).backgroundPolicy)
    }

    @Test fun noSelectedDeviceStillAllowsIndependentServiceObservation() {
        val value = connectionHealthReport(null, snapshot(), platform, now)
        assertEquals(HealthStatus.NO_DEVICE, value.status(HealthRowKind.CONTROL))
        assertEquals(HealthStatus.SERVICE_RESPONDING, value.status(HealthRowKind.SERVICE))
    }

    @Test fun futureOrNegativeTimestampsAreNotFresh() {
        assertFalse(healthEvidenceFresh(-1, now, HEALTH_EVIDENCE_MAX_AGE_MS))
        assertFalse(healthEvidenceFresh(now + 1, now, HEALTH_EVIDENCE_MAX_AGE_MS))
        assertTrue(healthEvidenceFresh(now - HEALTH_EVIDENCE_MAX_AGE_MS, now, HEALTH_EVIDENCE_MAX_AGE_MS))
    }
}
