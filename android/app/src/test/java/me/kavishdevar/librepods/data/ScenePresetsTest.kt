package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class ScenePresetsTest {
    private val allCapabilities = Capability.entries.toSet()

    @Test fun unsupportedModelsHaveNoSceneControls() {
        assertTrue(scenePresetPlans(emptySet(), true).isEmpty())
        assertTrue(scenePresetPlans(setOf(Capability.ADAPTIVE_VOLUME), true).isEmpty())
    }

    @Test fun adaptivePresetIsHiddenOnModelsWithOnlyBasicListeningModes() {
        val plans = scenePresetPlans(setOf(Capability.LISTENING_MODE), true)
        assertFalse(plans.any { it.id == ScenePresetId.BALANCED_FOCUS })
        assertTrue(plans.all { it.settings.single().setting == SceneSetting.LISTENING_MODE })
        assertTrue(plans.all { it.omitted.values.all { reason -> reason == SceneOmission.MODEL_UNSUPPORTED } })
        assertEquals(SceneGroup.entries.toSet(), plans.map { it.group }.toSet())
    }

    @Test fun premiumOnlySettingsAreOmittedWithReasonsEvenWhenTurningThemOff() {
        scenePresetPlans(allCapabilities, false).forEach { plan ->
            assertEquals(listOf(SceneSetting.LISTENING_MODE), plan.settings.map { it.setting })
            assertEquals(mapOf(SceneSetting.CONVERSATION_AWARENESS to SceneOmission.PREMIUM_REQUIRED,
                SceneSetting.PERSONALIZED_VOLUME to SceneOmission.PREMIUM_REQUIRED), plan.omitted)
        }
    }

    @Test fun eachPresetUsesOnlyExistingLegalModesAndBooleanEncodings() {
        val plans = scenePresetPlans(allCapabilities, true)
        assertEquals(5, plans.size)
        assertEquals(5, plans.map { it.id }.toSet().size)
        plans.forEach { plan ->
            assertEquals(3, plan.settings.map { it.setting }.toSet().size)
            assertTrue(plan.omitted.isEmpty())
            plan.settings.forEach { assertTrue(sceneSettingSupported(it, allCapabilities, true)) }
            assertTrue(plan.settings.none { it.setting == SceneSetting.LISTENING_MODE && it.value == 1 })
        }
    }

    @Test fun gatesAreRecheckedForIndividualPendingCommands() {
        val mode = SceneValue(SceneSetting.LISTENING_MODE, 4)
        assertFalse(sceneSettingSupported(mode, setOf(Capability.LISTENING_MODE), true))
        assertFalse(sceneSettingSupported(SceneValue(SceneSetting.CONVERSATION_AWARENESS, 1), allCapabilities, false))
        assertFalse(sceneSettingSupported(SceneValue(SceneSetting.PERSONALIZED_VOLUME, 3), allCapabilities, true))
        assertFalse(sceneSettingSupported(SceneValue(SceneSetting.LISTENING_MODE, 0), allCapabilities, true))
    }
}
