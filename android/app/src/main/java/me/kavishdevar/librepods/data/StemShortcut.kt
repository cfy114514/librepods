package me.kavishdevar.librepods.data

/** Only public Android actions that can be requested without launcher access. */
enum class SupportedStemShortcut(val id: String) {
    CAMERA("camera"),
    ALARMS("alarms")
}

sealed interface StemShortcutTarget {
    data class App(val packageName: String) : StemShortcutTarget
    data class SystemAction(val action: SupportedStemShortcut) : StemShortcutTarget
}

enum class StemShortcutSide(val id: String) {
    LEFT("left"), RIGHT("right");

    val targetKey get() = "${id}_long_press_shortcut"
    val actionKey get() = "${id}_long_press_action"
    fun actionKey(press: StemShortcutPress) = "${id}_${press.name.lowercase()}_action"

    companion object {
        fun fromString(value: String?) = entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
    }
}

enum class StemShortcutPress {
    SINGLE_PRESS, DOUBLE_PRESS, TRIPLE_PRESS, LONG_PRESS;

    companion object {
        fun fromString(value: String?) = entries.firstOrNull { it.name == value }
    }
}

/** A small versioned format, never an arbitrary Intent URI or persisted activity component. */
object StemShortcutCodec {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    fun encode(target: StemShortcutTarget): String? = when (target) {
        is StemShortcutTarget.App -> target.packageName.takeIf {
            it.length in 1..255 && packageName.matches(it)
        }?.let { "1|app|$it" }
        is StemShortcutTarget.SystemAction -> "1|action|${target.action.id}"
    }

    fun decode(value: String?): StemShortcutTarget? {
        if (value == null || value.length > 272) return null
        val parts = value.split('|')
        if (parts.size != 3 || parts[0] != "1") return null
        return when (parts[1]) {
            "app" -> StemShortcutTarget.App(parts[2]).takeIf { encode(it) != null }
            "action" -> SupportedStemShortcut.entries.firstOrNull { it.id == parts[2] }
                ?.let(StemShortcutTarget::SystemAction)
            else -> null
        }
    }
}

/** The storage boundary is injectable so persistence and left/right isolation can be tested. */
class StemShortcutStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Boolean,
) {
    fun read(side: StemShortcutSide): StemShortcutTarget? = StemShortcutCodec.decode(readValue(side.targetKey))

    fun write(side: StemShortcutSide, target: StemShortcutTarget): Boolean {
        val encoded = StemShortcutCodec.encode(target) ?: return false
        return writeValue(side.targetKey, encoded)
    }
}

sealed interface StemLaunchRequest {
    data class Target(val target: StemShortcutTarget) : StemLaunchRequest
    data object CurrentAssistant : StemLaunchRequest
    data object AssistIntent : StemLaunchRequest
    data object VoiceCommand : StemLaunchRequest
}

enum class StemLaunchAttempt { REQUESTED_UNCONFIRMED, UNAVAILABLE, FAILED }

enum class StemShortcutResult {
    TARGET_REQUESTED_UNCONFIRMED,
    ASSISTANT_REQUESTED_UNCONFIRMED,
    ASSISTANT_UNAVAILABLE,
    LOCKED,
}

/** A nonthrowing Android launch is a request, not evidence that another UI actually opened. */
fun executeStemShortcut(
    target: StemShortcutTarget?,
    isLocked: Boolean,
    attempt: (StemLaunchRequest) -> StemLaunchAttempt,
): StemShortcutResult {
    if (isLocked) return StemShortcutResult.LOCKED
    fun request(value: StemLaunchRequest): Boolean = try {
        attempt(value) == StemLaunchAttempt.REQUESTED_UNCONFIRMED
    } catch (_: RuntimeException) {
        // A handler may disappear or deny access between resolution and execution.
        false
    }
    if (target != null && request(StemLaunchRequest.Target(target))) {
        return StemShortcutResult.TARGET_REQUESTED_UNCONFIRMED
    }
    for (assistant in listOf(
        StemLaunchRequest.CurrentAssistant,
        StemLaunchRequest.AssistIntent,
        StemLaunchRequest.VoiceCommand,
    )) {
        if (request(assistant)) return StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED
    }
    return StemShortcutResult.ASSISTANT_UNAVAILABLE
}

enum class StemShortcutDelivery { FOREGROUND_REQUEST, NOTIFICATION_REQUEST, UNAVAILABLE }

fun stemShortcutNotificationAllowed(permissionGranted: Boolean, appEnabled: Boolean, channelEnabled: Boolean): Boolean =
    permissionGranted && appEnabled && channelEnabled

data class StemShortcutBinding(
    val side: StemShortcutSide,
    val action: StemAction,
    val target: StemShortcutTarget?,
    val press: StemShortcutPress = StemShortcutPress.LONG_PRESS,
)

fun readStemShortcutBinding(
    side: StemShortcutSide,
    press: StemShortcutPress,
    readValue: (String) -> String?,
): StemShortcutBinding? {
    val action = readValue(side.actionKey(press))?.let(StemAction::fromString)
    if (action != StemAction.DIGITAL_ASSISTANT && action != StemAction.CUSTOM_SHORTCUT) return null
    // This UI configures custom app targets for long press only. Existing assistant mappings retain all press types.
    if (action == StemAction.CUSTOM_SHORTCUT && press != StemShortcutPress.LONG_PRESS) return null
    val target = if (action == StemAction.CUSTOM_SHORTCUT) StemShortcutCodec.decode(readValue(side.targetKey)) else null
    return StemShortcutBinding(side, action, target, press)
}

const val STEM_SHORTCUT_REQUEST_LIFETIME_MS = 60_000L

/** Window focus and resume can arrive in either order. Consume a dispatch only once. */
class StemShortcutDispatchLifecycle(handled: Boolean = false) {
    var handled = handled
        private set
    private var resumed = false
    private var focused = false

    fun onResume(windowFocused: Boolean): Boolean {
        resumed = true
        focused = windowFocused
        return takeRequest()
    }

    fun onFocusChanged(windowFocused: Boolean): Boolean {
        focused = windowFocused
        return takeRequest()
    }

    fun onPause() { resumed = false }

    private fun takeRequest(): Boolean {
        if (handled || !resumed || !focused) return false
        handled = true
        return true
    }
}

/** Retains the source of a packet while its UI work is queued on the main thread. */
class StemShortcutEventGuard(
    private val source: Any,
    private val service: Any,
    private val deviceSession: Long,
    private val peer: String,
) {
    fun isCurrent(
        currentSource: Any?,
        currentService: Any?,
        currentSession: Long,
        currentPeer: String?,
        sourceUsable: Boolean,
    ): Boolean = source === currentSource && service === currentService && deviceSession == currentSession &&
        peer.equals(currentPeer, ignoreCase = true) && sourceUsable
}

fun isStemShortcutRequestCurrent(
    requested: StemShortcutBinding,
    current: StemShortcutBinding?,
    createdAt: Long,
    now: Long,
): Boolean = requested == current && createdAt >= 0 && now >= createdAt &&
    now - createdAt <= STEM_SHORTCUT_REQUEST_LIFETIME_MS

/** A service running in the foreground is not itself a visible activity or a BAL exemption. */
fun deliverStemShortcut(
    activityVisible: Boolean,
    isLocked: Boolean,
    requestForeground: () -> Boolean,
    postNotification: () -> Boolean,
): StemShortcutDelivery {
    if (activityVisible && !isLocked && requestForeground()) return StemShortcutDelivery.FOREGROUND_REQUEST
    return if (postNotification()) StemShortcutDelivery.NOTIFICATION_REQUEST else StemShortcutDelivery.UNAVAILABLE
}
