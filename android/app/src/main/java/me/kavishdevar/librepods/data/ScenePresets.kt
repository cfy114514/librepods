package me.kavishdevar.librepods.data

enum class SceneGroup { FOCUS, TRAVEL, CONVERSATION }
enum class ScenePresetId { DEEP_FOCUS, BALANCED_FOCUS, QUIET_JOURNEY, AWARE_JOURNEY, TALK_NEARBY }
enum class SceneSetting { LISTENING_MODE, CONVERSATION_AWARENESS, PERSONALIZED_VOLUME }
data class SceneValue(val setting: SceneSetting, val value: Int)
enum class SceneOmission { MODEL_UNSUPPORTED, PREMIUM_REQUIRED }

data class ScenePresetPlan(
    val id: ScenePresetId,
    val group: SceneGroup,
    val settings: List<SceneValue>,
    val omitted: Map<SceneSetting, SceneOmission>
)

/** Only existing controls and their existing entitlement rules are composed into presets. */
internal fun sceneSettingSupported(value: SceneValue, capabilities: Set<Capability>, premium: Boolean): Boolean =
    when (value.setting) {
        SceneSetting.LISTENING_MODE -> value.value in availableListeningModes(capabilities, allowOff = false)
        SceneSetting.CONVERSATION_AWARENESS -> premium && Capability.CONVERSATION_AWARENESS in capabilities && value.value in 1..2
        SceneSetting.PERSONALIZED_VOLUME -> premium && Capability.ADAPTIVE_VOLUME in capabilities && value.value in 1..2
    }

internal fun scenePresetPlans(capabilities: Set<Capability>, premium: Boolean): List<ScenePresetPlan> {
    if (Capability.LISTENING_MODE !in capabilities) return emptyList()
    fun preset(id: ScenePresetId, group: SceneGroup, mode: Int, conversation: Int, volume: Int): ScenePresetPlan? {
        val modeValue = SceneValue(SceneSetting.LISTENING_MODE, mode)
        if (!sceneSettingSupported(modeValue, capabilities, premium)) return null
        val requested = listOf(modeValue,
            SceneValue(SceneSetting.CONVERSATION_AWARENESS, conversation),
            SceneValue(SceneSetting.PERSONALIZED_VOLUME, volume))
        val (included, omitted) = requested.partition { sceneSettingSupported(it, capabilities, premium) }
        return ScenePresetPlan(id, group, included, omitted.associate { target ->
            val capability = when (target.setting) {
                SceneSetting.CONVERSATION_AWARENESS -> Capability.CONVERSATION_AWARENESS
                SceneSetting.PERSONALIZED_VOLUME -> Capability.ADAPTIVE_VOLUME
                SceneSetting.LISTENING_MODE -> Capability.LISTENING_MODE
            }
            target.setting to if (capability in capabilities) SceneOmission.PREMIUM_REQUIRED else SceneOmission.MODEL_UNSUPPORTED
        })
    }
    return listOfNotNull(
        preset(ScenePresetId.DEEP_FOCUS, SceneGroup.FOCUS, 2, 2, 2),
        preset(ScenePresetId.BALANCED_FOCUS, SceneGroup.FOCUS, 4, 2, 2),
        preset(ScenePresetId.QUIET_JOURNEY, SceneGroup.TRAVEL, 2, 2, 1),
        preset(ScenePresetId.AWARE_JOURNEY, SceneGroup.TRAVEL, 3, 1, 1),
        preset(ScenePresetId.TALK_NEARBY, SceneGroup.CONVERSATION, 3, 1, 2)
    )
}
