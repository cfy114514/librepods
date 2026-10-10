package me.kavishdevar.librepods.utils

/** One preference snapshot per accepted phrase; malformed values fail before audio is touched. */
internal data class ConversationVolumeSettings(
    val relative: Boolean = true,
    val percent: Int = 43,
    val pause: Boolean = false
) {
    companion object {
        fun from(values: Map<String, *>): ConversationVolumeSettings = ConversationVolumeSettings(
            relative = values["relative_conversational_awareness_volume"]?.let { it as Boolean } ?: true,
            percent = (values["conversational_awareness_volume"]?.let { it as Int } ?: 43).coerceIn(0, 100),
            pause = values["conversational_awareness_pause_music"]?.let { it as Boolean } ?: false
        )
    }
}
