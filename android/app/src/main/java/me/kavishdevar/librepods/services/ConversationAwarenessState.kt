package me.kavishdevar.librepods.services

/** Documented speech-start/end levels; ANC or Transparency changes are not required. */
internal class ConversationAwarenessState {
    enum class Change { LOWER, RESTORE, NONE }
    var isSpeaking = false
        private set

    fun update(level: Int): Change = when (level) {
        1, 2 -> applyDesired(true)
        3, 6, 8, 9 -> applyDesired(false)
        else -> Change.NONE // Intermediate and unknown levels cannot invent an end event.
    }

    fun applyDesired(speaking: Boolean): Change {
        if (isSpeaking == speaking) return Change.NONE
        isSpeaking = speaking
        return if (speaking) Change.LOWER else Change.RESTORE
    }

    fun reset() { isSpeaking = false }
}
