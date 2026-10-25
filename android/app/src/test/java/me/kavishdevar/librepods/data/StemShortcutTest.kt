package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class StemShortcutTest {
    private val app = StemShortcutTarget.App("org.example.player")
    private val camera = StemShortcutTarget.SystemAction(SupportedStemShortcut.CAMERA)
    private val alarms = StemShortcutTarget.SystemAction(SupportedStemShortcut.ALARMS)

    @Test fun storedTargetsRoundTripAcrossStoreRecreationAndRemainIndependentPerSide() {
        val values = mutableMapOf<String, String>()
        fun store() = StemShortcutStore(values::get) { key, value -> values[key] = value; true }
        assertTrue(store().write(StemShortcutSide.LEFT, app))
        assertTrue(store().write(StemShortcutSide.RIGHT, camera))
        assertEquals(app, store().read(StemShortcutSide.LEFT))
        assertEquals(camera, store().read(StemShortcutSide.RIGHT))
        assertTrue(store().write(StemShortcutSide.RIGHT, alarms))
        assertEquals(app, store().read(StemShortcutSide.LEFT))
        assertEquals(alarms, store().read(StemShortcutSide.RIGHT))
        assertEquals(setOf("left_long_press_shortcut", "right_long_press_shortcut"), values.keys)
    }

    @Test fun malformedUnknownAndArbitraryIntentTargetsAreRejected() {
        listOf(null, "", "org.example.app", "2|app|org.example.app", "1|intent|intent://voice",
            "1|action|unknown", "1|action|camera|extra", "1|app|", "1|app|org..example",
            "1|app| org.example", "1|app|org.example/.PrivateActivity", "1|app|org.example\n",
            "1|app|" + "a".repeat(256)).forEach { assertNull(it, StemShortcutCodec.decode(it)) }
        assertEquals(app, StemShortcutCodec.decode("1|app|org.example.player"))
        assertEquals(camera, StemShortcutCodec.decode("1|action|camera"))
        assertEquals(alarms, StemShortcutCodec.decode("1|action|alarms"))
    }

    @Test fun invalidWritesAndStorageFailuresNeverReportSaved() {
        var writes = 0
        val store = StemShortcutStore({ "1|app|org.example.player" }) { _, _ -> writes++; false }
        assertFalse(store.write(StemShortcutSide.LEFT, StemShortcutTarget.App("intent://private")))
        assertEquals(0, writes)
        assertFalse(store.write(StemShortcutSide.LEFT, camera))
        assertEquals(1, writes)
        assertEquals(app, store.read(StemShortcutSide.LEFT))
    }

    @Test fun validTargetStopsFallbackAndIsOnlyRequestedUnconfirmed() {
        val attempts = mutableListOf<StemLaunchRequest>()
        val result = executeStemShortcut(app, false) {
            attempts += it
            StemLaunchAttempt.REQUESTED_UNCONFIRMED
        }
        assertEquals(StemShortcutResult.TARGET_REQUESTED_UNCONFIRMED, result)
        assertEquals(listOf(StemLaunchRequest.Target(app)), attempts)
    }

    @Test fun uninstalledOrDisabledAppFallsBackWithoutErasingItsSavedSelection() {
        val values = mutableMapOf<String, String>()
        val store = StemShortcutStore(values::get) { key, value -> values[key] = value; true }
        store.write(StemShortcutSide.LEFT, app)
        val installed = mutableSetOf(app.packageName)
        fun launch(request: StemLaunchRequest): StemLaunchAttempt = when (request) {
            is StemLaunchRequest.Target -> if ((request.target as StemShortcutTarget.App).packageName in installed)
                StemLaunchAttempt.REQUESTED_UNCONFIRMED else StemLaunchAttempt.UNAVAILABLE
            StemLaunchRequest.CurrentAssistant -> StemLaunchAttempt.REQUESTED_UNCONFIRMED
            else -> error("Do not request another handler once the default assistant accepted")
        }
        installed.clear() // Same outcome for a disabled package or removed launcher activity.
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED,
            executeStemShortcut(store.read(StemShortcutSide.LEFT), false, ::launch))
        assertEquals(app, store.read(StemShortcutSide.LEFT))
        installed += app.packageName
        assertEquals(StemShortcutResult.TARGET_REQUESTED_UNCONFIRMED,
            executeStemShortcut(store.read(StemShortcutSide.LEFT), false, ::launch))
    }

    @Test fun removedSystemActionUsesTheSameAssistantFallback() {
        val requests = mutableListOf<StemLaunchRequest>()
        val result = executeStemShortcut(camera, false) {
            requests += it
            if (it is StemLaunchRequest.Target) StemLaunchAttempt.UNAVAILABLE else StemLaunchAttempt.REQUESTED_UNCONFIRMED
        }
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED, result)
        assertEquals(listOf(StemLaunchRequest.Target(camera), StemLaunchRequest.CurrentAssistant), requests)
    }

    @Test fun corruptOrMissingTargetStillRequestsDefaultAssistant() {
        val store = StemShortcutStore({ "broken" }) { _, _ -> error("Read only") }
        val attempts = mutableListOf<StemLaunchRequest>()
        val result = executeStemShortcut(store.read(StemShortcutSide.LEFT), false) {
            attempts += it; StemLaunchAttempt.REQUESTED_UNCONFIRMED
        }
        assertEquals(listOf(StemLaunchRequest.CurrentAssistant), attempts)
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED, result)
    }

    @Test fun assistantFallbackOrderUsesCurrentAssistantThenAssistThenVoiceCommand() {
        val requests = mutableListOf<StemLaunchRequest>()
        val result = executeStemShortcut(null, false) {
            requests += it
            if (it == StemLaunchRequest.VoiceCommand) StemLaunchAttempt.REQUESTED_UNCONFIRMED else StemLaunchAttempt.UNAVAILABLE
        }
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED, result)
        assertEquals(listOf(StemLaunchRequest.CurrentAssistant, StemLaunchRequest.AssistIntent, StemLaunchRequest.VoiceCommand), requests)
    }

    @Test fun deniedOrDisappearingHandlersDoNotCrashAndTryTheNextPublicRoute() {
        val requests = mutableListOf<StemLaunchRequest>()
        val result = executeStemShortcut(app, false) {
            requests += it
            when (it) {
                is StemLaunchRequest.Target -> throw SecurityException("exported permission changed")
                StemLaunchRequest.CurrentAssistant -> throw IllegalStateException("profile locked")
                StemLaunchRequest.AssistIntent -> throw RuntimeException("handler removed after resolution")
                StemLaunchRequest.VoiceCommand -> StemLaunchAttempt.REQUESTED_UNCONFIRMED
            }
        }
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED, result)
        assertEquals(4, requests.size)
    }

    @Test fun acceptedAssistIntentDoesNotAlsoStartVoiceCommand() {
        val requests = mutableListOf<StemLaunchRequest>()
        val result = executeStemShortcut(null, false) {
            requests += it
            if (it == StemLaunchRequest.CurrentAssistant) StemLaunchAttempt.UNAVAILABLE else StemLaunchAttempt.REQUESTED_UNCONFIRMED
        }
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED, result)
        assertEquals(listOf(StemLaunchRequest.CurrentAssistant, StemLaunchRequest.AssistIntent), requests)
    }

    @Test fun noAssistantOrAllBlockedHasAnExplicitUnavailableResult() {
        for (failure in listOf(StemLaunchAttempt.UNAVAILABLE, StemLaunchAttempt.FAILED)) {
            val requests = mutableListOf<StemLaunchRequest>()
            assertEquals(StemShortcutResult.ASSISTANT_UNAVAILABLE, executeStemShortcut(app, false) {
                requests += it; failure
            })
            assertEquals(4, requests.size)
        }
    }

    @Test fun assistantAvailabilityIsRecheckedOnEveryInvocation() {
        var defaultAssistantAvailable = true
        val attempt: (StemLaunchRequest) -> StemLaunchAttempt = {
            if (it == StemLaunchRequest.CurrentAssistant && defaultAssistantAvailable)
                StemLaunchAttempt.REQUESTED_UNCONFIRMED else StemLaunchAttempt.UNAVAILABLE
        }
        assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED, executeStemShortcut(null, false, attempt))
        defaultAssistantAvailable = false
        assertEquals(StemShortcutResult.ASSISTANT_UNAVAILABLE, executeStemShortcut(null, false, attempt))
    }

    @Test fun lockedDeviceNeverRequestsAppOrAssistant() {
        assertEquals(StemShortcutResult.LOCKED, executeStemShortcut(app, true) { error("No launch while locked") })
        assertEquals(StemShortcutResult.LOCKED, executeStemShortcut(null, true) { error("No assistant while locked") })
    }

    @Test fun backgroundAndLockedRequestsWaitForNotificationTap() {
        for ((visible, locked) in listOf(false to false, false to true, true to true)) {
            var notifications = 0
            assertEquals(StemShortcutDelivery.NOTIFICATION_REQUEST,
                deliverStemShortcut(visible, locked, { error("No background launch") }) { notifications++; true })
            assertEquals(1, notifications)
        }
    }

    @Test fun notificationPermissionAndGlobalAndChannelDisablementEachBlockDelivery() {
        for (settings in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            assertFalse(stemShortcutNotificationAllowed(settings.first, settings.second, settings.third))
            assertEquals(StemShortcutDelivery.UNAVAILABLE, deliverStemShortcut(false, false,
                { error("No background launch") },
                { stemShortcutNotificationAllowed(settings.first, settings.second, settings.third) }))
        }
        assertTrue(stemShortcutNotificationAllowed(true, true, true))
    }

    @Test fun directForegroundRequestIsNotReportedAsConfirmedAndFailedRequestCanNotify() {
        assertEquals(StemShortcutDelivery.FOREGROUND_REQUEST,
            deliverStemShortcut(true, false, { true }, { error("Do not launch twice") }))
        assertEquals(StemShortcutDelivery.NOTIFICATION_REQUEST,
            deliverStemShortcut(true, false, { false }, { true }))
        assertEquals(StemShortcutDelivery.UNAVAILABLE,
            deliverStemShortcut(true, false, { false }, { false }))
    }

    @Test fun expiredOrChangedNotificationCannotLaunchADifferentTargetOrSide() {
        val request = StemShortcutBinding(StemShortcutSide.LEFT, StemAction.CUSTOM_SHORTCUT, app)
        assertTrue(isStemShortcutRequestCurrent(request, request.copy(), 10, 60_010))
        assertFalse(isStemShortcutRequestCurrent(request, request, 10, 60_011))
        assertFalse(isStemShortcutRequestCurrent(request, request, 10, 9))
        assertFalse(isStemShortcutRequestCurrent(request, request, -1, 10))
        assertFalse(isStemShortcutRequestCurrent(request, null, 10, 20))
        assertFalse(isStemShortcutRequestCurrent(request, request.copy(target = camera), 10, 20))
        assertFalse(isStemShortcutRequestCurrent(request, request.copy(side = StemShortcutSide.RIGHT), 10, 20))
        assertFalse(isStemShortcutRequestCurrent(request,
            request.copy(action = StemAction.DIGITAL_ASSISTANT, target = null), 10, 20))
    }

    @Test fun dispatchWaitsForBothResumeAndFocusInEitherOrderAndRunsOnce() {
        val resumeFirst = StemShortcutDispatchLifecycle()
        assertFalse(resumeFirst.onResume(false))
        assertTrue(resumeFirst.onFocusChanged(true))
        assertFalse(resumeFirst.onFocusChanged(true))
        resumeFirst.onPause()
        assertFalse(resumeFirst.onResume(true))
        val focusFirst = StemShortcutDispatchLifecycle()
        assertFalse(focusFirst.onFocusChanged(true))
        assertTrue(focusFirst.onResume(true))
        assertTrue(focusFirst.handled)
    }

    @Test fun pauseBeforeFocusDefersDispatchAndRecreationDoesNotRepeatAHandledLaunch() {
        val pending = StemShortcutDispatchLifecycle()
        assertFalse(pending.onResume(false))
        pending.onPause()
        assertFalse(pending.onFocusChanged(true))
        assertTrue(pending.onResume(true))
        val recreated = StemShortcutDispatchLifecycle(pending.handled)
        assertFalse(recreated.onResume(true))
        assertFalse(recreated.onFocusChanged(true))
    }

    @Test fun existingActionNamesAndSideKeysRemainStable() {
        assertEquals(StemAction.DIGITAL_ASSISTANT, StemAction.fromString("DIGITAL_ASSISTANT"))
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.fromString("CYCLE_NOISE_CONTROL_MODES"))
        assertEquals(StemAction.CUSTOM_SHORTCUT, StemAction.fromString("CUSTOM_SHORTCUT"))
        assertEquals(StemShortcutSide.LEFT, StemShortcutSide.fromString("Left"))
        assertEquals("right_long_press_action", StemShortcutSide.RIGHT.actionKey)
        assertNull(StemShortcutSide.fromString("unknown"))
    }

    @Test fun nonLongPressAssistantMappingsSurviveWhenLongPressStillControlsNoise() {
        for (side in StemShortcutSide.entries) {
            for (press in listOf(StemShortcutPress.SINGLE_PRESS, StemShortcutPress.DOUBLE_PRESS, StemShortcutPress.TRIPLE_PRESS)) {
                val values = mapOf(side.actionKey to "CYCLE_NOISE_CONTROL_MODES",
                    side.actionKey(press) to "DIGITAL_ASSISTANT")
                val binding = readStemShortcutBinding(side, press, values::get)!!
                assertEquals(press, binding.press)
                assertEquals(StemAction.DIGITAL_ASSISTANT, binding.action)
                assertNull(binding.target)
                assertTrue(isStemShortcutRequestCurrent(binding, readStemShortcutBinding(side, press, values::get), 1, 2))
                assertEquals(StemShortcutResult.ASSISTANT_REQUESTED_UNCONFIRMED,
                    executeStemShortcut(binding.target, false) { StemLaunchAttempt.REQUESTED_UNCONFIRMED })
                assertNull(readStemShortcutBinding(side, StemShortcutPress.LONG_PRESS, values::get))
            }
        }
    }

    @Test fun unsupportedNonLongCustomMappingsCannotReuseAnUnrelatedLongPressTarget() {
        val values = mapOf("left_single_press_action" to "CUSTOM_SHORTCUT",
            "left_long_press_shortcut" to "1|action|camera")
        assertNull(readStemShortcutBinding(StemShortcutSide.LEFT, StemShortcutPress.SINGLE_PRESS, values::get))
    }

    @Test fun queuedStemEventCannotOutliveItsSourceSessionServiceOrSelectedPeer() {
        val socket = Any()
        val service = Any()
        val guard = StemShortcutEventGuard(socket, service, 7, "AA:BB")
        assertTrue(guard.isCurrent(socket, service, 7, "aa:bb", true))
        assertFalse(guard.isCurrent(Any(), service, 7, "AA:BB", true))
        assertFalse(guard.isCurrent(socket, Any(), 7, "AA:BB", true))
        assertFalse(guard.isCurrent(socket, service, 8, "AA:BB", true))
        assertFalse(guard.isCurrent(socket, service, 7, "CC:DD", true))
        assertFalse(guard.isCurrent(socket, service, 7, "AA:BB", false))
        assertFalse(guard.isCurrent(null, service, 7, "AA:BB", true))
        assertFalse(guard.isCurrent(socket, null, 7, "AA:BB", true))
    }

    @Test fun queuedStemEventRetainsOriginalTargetAndAgeUntilMainThreadDelivery() {
        val socket = Any()
        val service = Any()
        val guard = StemShortcutEventGuard(socket, service, 7, "AA:BB")
        val accepted = StemShortcutBinding(StemShortcutSide.LEFT, StemAction.CUSTOM_SHORTCUT, app)
        var current = accepted
        var now = 100L
        var currentSession = 7L
        var deliveries = 0
        val queued = {
            if (guard.isCurrent(socket, service, currentSession, "AA:BB", true) &&
                isStemShortcutRequestCurrent(accepted, current, 100, now)) deliveries++
        }
        current = accepted.copy(target = camera)
        queued()
        assertEquals(0, deliveries)
        current = accepted
        currentSession++
        queued()
        assertEquals(0, deliveries)
        currentSession = 7
        now = 60_101
        queued()
        assertEquals(0, deliveries)
        now = 101
        queued()
        assertEquals(1, deliveries)
    }
}
