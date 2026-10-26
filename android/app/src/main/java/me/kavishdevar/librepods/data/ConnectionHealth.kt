package me.kavishdevar.librepods.data

/** Evidence is deliberately separate from audio routing and from connection policy. */
enum class HealthAudioProfile { MEDIA, CALLS }
enum class HealthProfileState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }
enum class HealthServiceState { STARTING, STARTED, STOPPED }

data class HealthProfileEvidence(val state: HealthProfileState, val observedAt: Long)
data class HealthControlEvidence(
    val connected: Boolean,
    val changedAt: Long,
    val lastPacketAt: Long? = null,
)

/** Process-local, monotonic timestamps. Never persist these across a reboot. */
data class ConnectionHealthSnapshot(
    val peer: String?,
    val selectionGeneration: Long,
    val service: HealthServiceState,
    val observedAt: Long,
    val profiles: Map<HealthAudioProfile, HealthProfileEvidence> = emptyMap(),
    val control: HealthControlEvidence? = null,
)

data class HealthPlatformEvidence(
    val observedAt: Long,
    val bluetoothPermission: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val batteryOptimizationExempt: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
)

enum class HealthRowKind { MEDIA, CALLS, CONTROL, SERVICE }
enum class HealthStatus {
    NO_DEVICE, PERMISSION_REQUIRED, BLUETOOTH_OFF, UNKNOWN, STALE,
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING,
    WAITING_FOR_DATA, RECEIVING_DATA, SERVICE_RESPONDING, SERVICE_STARTING, SERVICE_STOPPED,
}
enum class HealthBackgroundPolicy { UNKNOWN, RESTRICTED, OPTIMIZED, UNRESTRICTED }

data class ConnectionHealthRow(
    val kind: HealthRowKind,
    val status: HealthStatus,
    val observedAt: Long? = null,
    val lastProfileState: HealthProfileState? = null,
)

data class ConnectionHealthReport(
    val peer: String?,
    val rows: List<ConnectionHealthRow>,
    val backgroundPolicy: HealthBackgroundPolicy = HealthBackgroundPolicy.UNKNOWN,
    val permissionRequired: Boolean = false,
    val checkedAt: Long? = null,
)

internal const val HEALTH_EVIDENCE_MAX_AGE_MS = 120_000L
internal const val HEALTH_SAMPLE_MAX_AGE_MS = 15_000L

internal fun connectionHealthIdentity(address: String?): String? = address?.trim()
    ?.takeIf { it.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) }
    ?.uppercase(java.util.Locale.ROOT)

internal fun healthEvidenceFresh(observedAt: Long, now: Long, maxAge: Long): Boolean =
    observedAt >= 0 && now >= observedAt && now - observedAt <= maxAge

/** An absent or expired observation never becomes a disconnection, or a healthy connection. */
internal fun connectionHealthReport(
    selectedPeer: String?,
    snapshot: ConnectionHealthSnapshot?,
    platform: HealthPlatformEvidence?,
    now: Long,
): ConnectionHealthReport {
    val peer = connectionHealthIdentity(selectedPeer)
    val currentPlatform = platform?.takeIf { healthEvidenceFresh(it.observedAt, now, HEALTH_SAMPLE_MAX_AGE_MS) }
    val serviceSampleFresh = snapshot?.let { healthEvidenceFresh(it.observedAt, now, HEALTH_SAMPLE_MAX_AGE_MS) } == true
    val owned = snapshot?.takeIf { peer != null && connectionHealthIdentity(it.peer) == peer }
    val prerequisite = when {
        peer == null -> HealthStatus.NO_DEVICE
        currentPlatform?.bluetoothPermission == false -> HealthStatus.PERMISSION_REQUIRED
        currentPlatform?.bluetoothEnabled == false -> HealthStatus.BLUETOOTH_OFF
        currentPlatform?.bluetoothPermission != true || currentPlatform.bluetoothEnabled != true -> HealthStatus.UNKNOWN
        else -> null
    }
    fun profileRow(profile: HealthAudioProfile, kind: HealthRowKind): ConnectionHealthRow {
        prerequisite?.let { return ConnectionHealthRow(kind, it) }
        val evidence = owned?.profiles?.get(profile) ?: return ConnectionHealthRow(kind, HealthStatus.UNKNOWN)
        if (!serviceSampleFresh || owned.service == HealthServiceState.STOPPED ||
            !healthEvidenceFresh(evidence.observedAt, now, HEALTH_EVIDENCE_MAX_AGE_MS)) {
            return ConnectionHealthRow(kind, HealthStatus.STALE, evidence.observedAt, evidence.state)
        }
        val status = when (evidence.state) {
            HealthProfileState.CONNECTED -> HealthStatus.CONNECTED
            HealthProfileState.CONNECTING -> HealthStatus.CONNECTING
            HealthProfileState.DISCONNECTED -> HealthStatus.DISCONNECTED
            HealthProfileState.DISCONNECTING -> HealthStatus.DISCONNECTING
        }
        return ConnectionHealthRow(kind, status, evidence.observedAt, evidence.state)
    }
    val control = owned?.control
    val controlStatus = prerequisite ?: when {
        control == null -> HealthStatus.UNKNOWN
        !serviceSampleFresh || owned.service == HealthServiceState.STOPPED -> HealthStatus.STALE
        !control.connected -> if (healthEvidenceFresh(control.changedAt, now, HEALTH_EVIDENCE_MAX_AGE_MS))
            HealthStatus.DISCONNECTED else HealthStatus.STALE
        control.lastPacketAt != null -> if (healthEvidenceFresh(control.lastPacketAt, now, HEALTH_EVIDENCE_MAX_AGE_MS))
            HealthStatus.RECEIVING_DATA else HealthStatus.STALE
        healthEvidenceFresh(control.changedAt, now, HEALTH_EVIDENCE_MAX_AGE_MS) -> HealthStatus.WAITING_FOR_DATA
        else -> HealthStatus.STALE
    }
    val serviceStatus = when {
        snapshot == null -> HealthStatus.UNKNOWN
        !serviceSampleFresh -> HealthStatus.STALE
        snapshot.service == HealthServiceState.STOPPED -> HealthStatus.SERVICE_STOPPED
        snapshot.service == HealthServiceState.STARTING -> HealthStatus.SERVICE_STARTING
        else -> HealthStatus.SERVICE_RESPONDING
    }
    val background = when {
        currentPlatform?.backgroundRestricted == true -> HealthBackgroundPolicy.RESTRICTED
        currentPlatform?.batteryOptimizationExempt == false -> HealthBackgroundPolicy.OPTIMIZED
        currentPlatform?.backgroundRestricted == false && currentPlatform.batteryOptimizationExempt == true -> HealthBackgroundPolicy.UNRESTRICTED
        else -> HealthBackgroundPolicy.UNKNOWN
    }
    return ConnectionHealthReport(peer,
        listOf(profileRow(HealthAudioProfile.MEDIA, HealthRowKind.MEDIA),
            profileRow(HealthAudioProfile.CALLS, HealthRowKind.CALLS),
            ConnectionHealthRow(HealthRowKind.CONTROL, controlStatus,
                if (prerequisite == null) control?.lastPacketAt ?: control?.changedAt else null),
            ConnectionHealthRow(HealthRowKind.SERVICE, serviceStatus, snapshot?.observedAt)),
        background, currentPlatform?.bluetoothPermission == false, currentPlatform?.observedAt)
}
