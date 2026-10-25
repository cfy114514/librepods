package me.kavishdevar.librepods.integration

import me.kavishdevar.librepods.data.ConnectionHealthReport
import me.kavishdevar.librepods.data.ConnectionHealthSnapshot
import me.kavishdevar.librepods.data.HealthAudioProfile
import me.kavishdevar.librepods.data.HealthBackgroundPolicy
import me.kavishdevar.librepods.data.HealthPlatformEvidence
import me.kavishdevar.librepods.data.HealthProfileState
import me.kavishdevar.librepods.data.HealthRowKind
import me.kavishdevar.librepods.data.HealthStatus
import me.kavishdevar.librepods.presentation.ConnectionHealthObserver
import me.kavishdevar.librepods.services.ConnectionHealthMonitor

private class HealthFixture {
    val clock = Clock()
    val monitor = ConnectionHealthMonitor { clock.now }
    var selection = requireNotNull(monitor.selectPeer(PEER_A))
    val observer = ConnectionHealthObserver(PEER_A)
    init { monitor.started(); observer.start() }

    fun platform(permission: Boolean? = true, enabled: Boolean? = true) =
        HealthPlatformEvidence(clock.now, bluetoothPermission = permission, bluetoothEnabled = enabled)

    fun read(snapshot: ConnectionHealthSnapshot? = monitor.snapshot(), platform: HealthPlatformEvidence? = platform()) =
        requireNotNull(observer.publish(requireNotNull(observer.beginRead()), snapshot, platform, clock.now))
}

private fun ConnectionHealthReport.status(row: HealthRowKind): HealthStatus = rows.single { it.kind == row }.status

private fun expectStatus(report: ConnectionHealthReport, row: HealthRowKind, expected: HealthStatus, context: String) =
    expectEquals(expected, report.status(row), "$context / $row")

private fun fourRowsStayIndependentThroughProfileAndControlEvents() {
    val f = HealthFixture()
    var report = f.read()
    for (row in listOf(HealthRowKind.MEDIA, HealthRowKind.CALLS, HealthRowKind.CONTROL))
        expectStatus(report, row, HealthStatus.UNKNOWN, "No transport evidence")
    expectStatus(report, HealthRowKind.SERVICE, HealthStatus.SERVICE_RESPONDING, "Bound service responded")

    expect(f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED), "Media broadcast accepted")
    report = f.read()
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.CONNECTED, "Media profile connected")
    expectStatus(report, HealthRowKind.CALLS, HealthStatus.UNKNOWN, "Media says nothing about calls")
    expectStatus(report, HealthRowKind.CONTROL, HealthStatus.UNKNOWN, "Media says nothing about control socket")

    val control = requireNotNull(f.monitor.controlOpened(f.selection))
    expect(f.monitor.profileChanged(f.selection, HealthAudioProfile.CALLS, HealthProfileState.DISCONNECTED), "Explicit calls disconnection accepted")
    report = f.read()
    expectStatus(report, HealthRowKind.CONTROL, HealthStatus.WAITING_FOR_DATA, "Open socket without inbound packet")
    expectStatus(report, HealthRowKind.CALLS, HealthStatus.DISCONNECTED, "Calls retain their own event")
    expect(f.monitor.controlPacket(control), "Incoming packet accepted")
    report = f.read()
    expectStatus(report, HealthRowKind.CONTROL, HealthStatus.RECEIVING_DATA, "Only inbound packet establishes receiving")
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.CONNECTED, "Control data does not change audio profile")
    expectStatus(report, HealthRowKind.CALLS, HealthStatus.DISCONNECTED, "Control data does not invent call profile connection")
    expect(f.monitor.controlClosed(control), "Explicit control close accepted")
    report = f.read()
    expectStatus(report, HealthRowKind.CONTROL, HealthStatus.DISCONNECTED, "Explicit control disconnection")
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.CONNECTED, "Media connection survives control close")
    expectStatus(report, HealthRowKind.SERVICE, HealthStatus.SERVICE_RESPONDING, "Control close does not stop service")
}

private fun absentOrPartialPlatformReadNeverBecomesPermissionDenied() {
    val f = HealthFixture()
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
    for (platform in listOf(null, f.platform(null, null), f.platform(true, null), f.platform(null, true))) {
        val report = f.read(platform = platform)
        expectStatus(report, HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "Incomplete platform evidence")
        expect(!report.permissionRequired, "No read is not proof of denied permission")
        expectEquals(HealthBackgroundPolicy.UNKNOWN, report.backgroundPolicy, "Missing policy evidence stays unknown")
    }
    val denied = f.read(platform = f.platform(false, null))
    expect(denied.permissionRequired, "Explicit permission denial is actionable")
    expectStatus(denied, HealthRowKind.MEDIA, HealthStatus.PERMISSION_REQUIRED, "Explicit denial")
    expectStatus(denied, HealthRowKind.SERVICE, HealthStatus.SERVICE_RESPONDING, "Bluetooth permission does not erase service response")
    val off = f.read(platform = f.platform(true, false))
    expectStatus(off, HealthRowKind.CALLS, HealthStatus.BLUETOOTH_OFF, "Adapter shutdown is explicit")
}

private fun repeatedRefreshCannotRefreshOldProfileOrPacketEvidence() {
    val f = HealthFixture()
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
    val control = requireNotNull(f.monitor.controlOpened(f.selection))
    f.monitor.controlPacket(control)
    val evidenceAt = f.clock.now
    repeat(121) { f.clock.advance(1_000); f.read() }
    val report = f.read()
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.STALE, "Fresh service reads cannot refresh old media event")
    expectStatus(report, HealthRowKind.CONTROL, HealthStatus.STALE, "Fresh UI ticks cannot refresh old inbound packet")
    expectStatus(report, HealthRowKind.CALLS, HealthStatus.UNKNOWN, "Never-observed calls remain unknown")
    expectStatus(report, HealthRowKind.SERVICE, HealthStatus.SERVICE_RESPONDING, "Service read remains distinct fresh evidence")
    expectEquals(evidenceAt, report.rows.single { it.kind == HealthRowKind.MEDIA }.observedAt, "Profile original timestamp is retained")
    expectEquals(HealthProfileState.CONNECTED, report.rows.single { it.kind == HealthRowKind.MEDIA }.lastProfileState, "Stale state remains labelled as last known")
}

private fun oldServiceAndPlatformSnapshotsExpireIndependently() {
    val f = HealthFixture()
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
    val service = f.monitor.snapshot()
    val platform = f.platform()
    f.clock.advance(15_001)
    val oldService = f.read(snapshot = service)
    expectStatus(oldService, HealthRowKind.SERVICE, HealthStatus.STALE, "Old service object is not a current response")
    expectStatus(oldService, HealthRowKind.MEDIA, HealthStatus.STALE, "Recent event cannot compensate for old service response")
    val oldPlatform = f.read(platform = platform)
    expectStatus(oldPlatform, HealthRowKind.SERVICE, HealthStatus.SERVICE_RESPONDING, "New service read remains fresh")
    expectStatus(oldPlatform, HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "Expired permission/adapter read cannot support connection claim")
}

private fun newerBroadcastWinsOverAlreadyRunningProfileQuery() {
    val f = HealthFixture()
    val query = requireNotNull(f.monitor.beginProfileObservation(f.selection, HealthAudioProfile.MEDIA))
    f.clock.advance()
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.DISCONNECTED)
    expect(!f.monitor.profileObserved(query, HealthProfileState.CONNECTED), "Old query result cannot override newer selected-peer broadcast")
    expectStatus(f.read(), HealthRowKind.MEDIA, HealthStatus.DISCONNECTED, "Newest event remains diagnostic evidence")
    val calls = requireNotNull(f.monitor.beginProfileObservation(f.selection, HealthAudioProfile.CALLS))
    expect(f.monitor.profileObserved(calls, HealthProfileState.CONNECTED), "Independent current calls query may complete")
    val report = f.read()
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.DISCONNECTED, "Calls query does not touch media")
    expectStatus(report, HealthRowKind.CALLS, HealthStatus.CONNECTED, "Calls query reports only its own profile")
}

private fun peerSelectionAndControlReplacementRejectLateEvidence() {
    val f = HealthFixture()
    val original = f.selection
    val originalControl = requireNotNull(f.monitor.controlOpened(original))
    val originalQuery = requireNotNull(f.monitor.beginProfileObservation(original, HealthAudioProfile.CALLS))
    f.monitor.selectPeer(PEER_B)
    f.selection = requireNotNull(f.monitor.selectPeer(PEER_A))
    expect(!f.monitor.profileChanged(original, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED), "A-to-B-to-A invalidates old same-address selection ticket")
    expect(!f.monitor.profileObserved(originalQuery, HealthProfileState.CONNECTED), "Old peer query cannot resurface after reselection")
    expect(!f.monitor.controlPacket(originalControl), "Old control packet cannot resurface after reselection")
    expectStatus(f.read(), HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "Reselection starts without profile evidence")
    val first = requireNotNull(f.monitor.controlOpened(f.selection))
    val second = requireNotNull(f.monitor.controlOpened(f.selection))
    expect(!f.monitor.controlClosed(first), "Late close cannot disconnect replacement socket")
    expect(!f.monitor.controlPacket(first), "Late packet cannot make replacement socket look active")
    expectStatus(f.read(), HealthRowKind.CONTROL, HealthStatus.WAITING_FOR_DATA, "Replacement socket has no invented packet evidence")
    expect(f.monitor.controlPacket(second), "Replacement socket owns its packet")
    expectStatus(f.read(), HealthRowKind.CONTROL, HealthStatus.RECEIVING_DATA, "Replacement packet is current")
}

private fun permissionAndAdapterInvalidationRequireFreshEvidence() {
    val f = HealthFixture()
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
    val oldSelection = f.selection
    val oldQuery = requireNotNull(f.monitor.beginProfileObservation(oldSelection, HealthAudioProfile.CALLS))
    val oldControl = requireNotNull(f.monitor.controlOpened(oldSelection))
    f.monitor.controlPacket(oldControl)
    f.monitor.invalidateBluetooth()
    expectStatus(f.read(platform = f.platform(false, null)), HealthRowKind.MEDIA, HealthStatus.PERMISSION_REQUIRED, "Revoked permission explains missing status")
    expect(!f.monitor.controlPacket(oldControl), "Packet admitted before invalidation is retired")
    expect(!f.monitor.profileObserved(oldQuery, HealthProfileState.CONNECTED), "Query admitted before invalidation is retired")
    expect(!f.monitor.profileChanged(oldSelection, HealthAudioProfile.CALLS, HealthProfileState.CONNECTED), "Broadcast ticket captured before invalidation is retired")
    var report = f.read()
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "Granting permission does not restore stale profile evidence")
    expectStatus(report, HealthRowKind.CONTROL, HealthStatus.UNKNOWN, "Granting permission does not resurrect old socket")
    f.selection = requireNotNull(f.monitor.selection())
    f.monitor.profileChanged(f.selection, HealthAudioProfile.CALLS, HealthProfileState.CONNECTED)
    report = f.read()
    expectStatus(report, HealthRowKind.CALLS, HealthStatus.CONNECTED, "New selected-peer event restores only known profile")
    expectStatus(report, HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "New calls event does not restore old media event")
    f.monitor.invalidateBluetooth()
    expectStatus(f.read(platform = f.platform(true, false)), HealthRowKind.CALLS, HealthStatus.BLUETOOTH_OFF, "Adapter off is visible")
    expectStatus(f.read(), HealthRowKind.CALLS, HealthStatus.UNKNOWN, "Adapter restart requires fresh profile observation")
}

private fun observerStopRestartAndNewerReadFenceLateResults() {
    val f = HealthFixture()
    val oldRead = requireNotNull(f.observer.beginRead())
    f.observer.stop()
    expect(f.observer.beginRead() == null, "Stopped UI does not create reads")
    f.observer.start()
    expect(f.observer.publish(oldRead, f.monitor.snapshot(), f.platform(), f.clock.now) == null, "Stopped UI read cannot publish after restart")
    val slow = requireNotNull(f.observer.beginRead())
    val newer = requireNotNull(f.observer.beginRead())
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.DISCONNECTED)
    val fresh = requireNotNull(f.observer.publish(newer, f.monitor.snapshot(), f.platform(), f.clock.now))
    expectStatus(fresh, HealthRowKind.MEDIA, HealthStatus.DISCONNECTED, "Newer read publishes")
    expect(f.observer.publish(slow, f.monitor.snapshot(), f.platform(), f.clock.now) == null, "Older completion cannot overwrite newer published read")
    val finalRead = requireNotNull(f.observer.beginRead())
    f.observer.close()
    expect(!f.observer.start(), "Disposed observer cannot restart")
    expect(f.observer.publish(finalRead, f.monitor.snapshot(), f.platform(), f.clock.now) == null, "Disposed observer cannot publish captured read")
}

private fun recreationRejectsForeignServiceAndObserverTickets() {
    val old = HealthFixture()
    val oldControl = requireNotNull(old.monitor.controlOpened(old.selection))
    val oldRead = requireNotNull(old.observer.beginRead())
    old.monitor.close()
    old.observer.close()
    val replacement = HealthFixture()
    expect(!replacement.monitor.controlPacket(oldControl), "Old service socket ticket is not owned by new monitor")
    expect(!replacement.monitor.profileChanged(old.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED), "Old service selection cannot update new instance")
    expect(replacement.observer.publish(oldRead, old.monitor.snapshot(), old.platform(), old.clock.now) == null, "Old UI read cannot publish into replacement observer")
    expectStatus(replacement.read(), HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "Recreated service does not inherit live evidence")
    expectStatus(replacement.read(), HealthRowKind.CONTROL, HealthStatus.UNKNOWN, "Recreated service does not inherit old control state")
    expect(!old.monitor.controlPacket(oldControl), "Retired service cannot accept its own late callback")
}

private fun stalledRefreshStillExpiresPreviouslyPublishedEvidence() {
    val f = HealthFixture()
    f.monitor.profileChanged(f.selection, HealthAudioProfile.MEDIA, HealthProfileState.CONNECTED)
    val control = requireNotNull(f.monitor.controlOpened(f.selection))
    f.monitor.controlPacket(control)
    val initial = f.read()
    expectStatus(initial, HealthRowKind.MEDIA, HealthStatus.CONNECTED, "Initial profile evidence is fresh")
    expectStatus(initial, HealthRowKind.CONTROL, HealthStatus.RECEIVING_DATA, "Initial control evidence is fresh")
    val stalled = requireNotNull(f.observer.beginRead())
    val frozenService = f.monitor.snapshot()
    val frozenPlatform = f.platform()
    f.clock.advance(15_001)
    val expired = requireNotNull(f.observer.currentReport(f.clock.now))
    expectStatus(expired, HealthRowKind.SERVICE, HealthStatus.STALE, "Display-time expiry runs while another read remains incomplete")
    expectStatus(expired, HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "Expired platform sample cannot hold a healthy profile label")
    expectStatus(expired, HealthRowKind.CONTROL, HealthStatus.UNKNOWN, "Expired prerequisites cannot hold receiving-data label")
    val late = requireNotNull(f.observer.publish(stalled, frozenService, frozenPlatform, f.clock.now))
    expectStatus(late, HealthRowKind.SERVICE, HealthStatus.STALE, "Late read keeps its original observation timestamp")
    val stopping = requireNotNull(f.observer.beginRead())
    f.observer.stop()
    val stopped = requireNotNull(f.observer.currentReport(f.clock.now))
    expectStatus(stopped, HealthRowKind.SERVICE, HealthStatus.UNKNOWN, "Lifecycle stop immediately withdraws previous service response")
    expect(f.observer.publish(stopping, f.monitor.snapshot(), f.platform(), f.clock.now) == null,
        "Read that outlives lifecycle stop cannot restore display state")
    f.observer.close()
    expect(f.observer.currentReport(f.clock.now) == null, "Disposed observer has no current report")
}

private fun repeatedOpenCloseWithoutEvidenceStaysUnknown() {
    val f = HealthFixture()
    repeat(250) {
        f.observer.stop()
        expect(f.observer.start(), "UI observer restarts")
        f.clock.advance(1_000)
        val report = f.read()
        expectEquals(4, report.rows.size, "One row per independently observed surface")
        expectStatus(report, HealthRowKind.MEDIA, HealthStatus.UNKNOWN, "No callback never becomes disconnected or connected")
        expectStatus(report, HealthRowKind.CALLS, HealthStatus.UNKNOWN, "No call callback stays unknown")
        expectStatus(report, HealthRowKind.CONTROL, HealthStatus.UNKNOWN, "Refresh never opens a control session")
    }
}

internal fun healthCases(): List<IntegrationCase> = listOf(
    "health_media_calls_control_and_service_are_independent" to ::fourRowsStayIndependentThroughProfileAndControlEvents,
    "health_absent_platform_read_is_unknown_not_denied" to ::absentOrPartialPlatformReadNeverBecomesPermissionDenied,
    "health_repeated_refresh_does_not_refresh_transport_evidence" to ::repeatedRefreshCannotRefreshOldProfileOrPacketEvidence,
    "health_service_and_platform_evidence_expire_independently" to ::oldServiceAndPlatformSnapshotsExpireIndependently,
    "health_newer_broadcast_wins_over_old_query" to ::newerBroadcastWinsOverAlreadyRunningProfileQuery,
    "health_peer_and_socket_changes_reject_late_evidence" to ::peerSelectionAndControlReplacementRejectLateEvidence,
    "health_permission_and_adapter_recovery_need_fresh_events" to ::permissionAndAdapterInvalidationRequireFreshEvidence,
    "health_observer_restart_and_newer_read_reject_late_results" to ::observerStopRestartAndNewerReadFenceLateResults,
    "health_recreation_rejects_foreign_tickets" to ::recreationRejectsForeignServiceAndObserverTickets,
    "health_stalled_refresh_still_expires_published_evidence" to ::stalledRefreshStillExpiresPreviouslyPublishedEvidence,
    "health_repeated_ui_restart_without_evidence_stays_unknown" to ::repeatedOpenCloseWithoutEvidenceStaysUnknown,
)
