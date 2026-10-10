package me.kavishdevar.librepods.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TranslationOutput { AIRPODS, CAPTIONS, SPEAKER }
internal enum class TranslationPhase { IDLE, STARTING, LISTENING, STOPPING }
internal enum class TranslationError {
    PERMISSION, MICROPHONE_BUSY, NO_RECOGNIZER, NO_TRANSLATOR, LANGUAGE_MODEL,
    NO_VOICE, NO_HEADPHONES, ROUTE_CHANGED, AUDIO, TRANSLATION, TIMEOUT, SERVICE
}
internal data class TranslationPair(val source: String, val target: String) {
    val key: String get() = "$source|$target"
}
internal data class TranslationLine(val id: Long, val original: String, val translated: String = "")
internal data class TranslationState(
    val phase: TranslationPhase = TranslationPhase.IDLE,
    val pair: TranslationPair? = null, val output: TranslationOutput = TranslationOutput.AIRPODS,
    val partial: String = "", val lines: List<TranslationLine> = emptyList(),
    val error: TranslationError? = null, val skipped: Int = 0
)

internal class TranslationSessions {
    class Lease internal constructor(val id: Long, val pair: TranslationPair, val output: TranslationOutput) {
        @Volatile var active = true
            internal set
    }
    private var nextId = 1L
    private var current: Lease? = null
    private val mutableState = MutableStateFlow(TranslationState())
    val state = mutableState.asStateFlow()

    @Synchronized fun begin(pair: TranslationPair, output: TranslationOutput): Lease? {
        if (current != null || pair.source.isBlank() || pair.target.isBlank() || pair.source == pair.target) return null
        val lease = Lease(nextId++, pair, output)
        current = lease
        mutableState.value = TranslationState(TranslationPhase.STARTING, pair, output)
        return lease
    }
    @Synchronized fun find(id: Long): Lease? = current?.takeIf { it.id == id }
    @Synchronized fun listening(lease: Lease) = update(lease) { it.copy(phase = TranslationPhase.LISTENING) }
    @Synchronized fun partial(lease: Lease, text: String) = update(lease) { it.copy(partial = text.take(1000)) }
    @Synchronized fun addLine(lease: Lease, line: TranslationLine) = update(lease) {
        it.copy(partial = "", lines = (it.lines + line).takeLast(12))
    }
    @Synchronized fun translated(lease: Lease, id: Long, text: String) = update(lease) {
        it.copy(lines = it.lines.map { line -> if (line.id == id) line.copy(translated = text.take(4000)) else line })
    }
    @Synchronized fun skipped(lease: Lease) = update(lease) { it.copy(skipped = (it.skipped + 1).coerceAtMost(999)) }
    @Synchronized fun stop(lease: Lease? = current, error: TranslationError? = null): Lease? {
        if (lease == null || lease !== current) return null
        lease.active = false
        mutableState.value = mutableState.value.copy(phase = TranslationPhase.STOPPING, partial = "",
            error = error ?: mutableState.value.error)
        return lease
    }
    @Synchronized fun finish(lease: Lease) {
        if (lease !== current) return
        lease.active = false
        current = null
        mutableState.value = mutableState.value.copy(phase = TranslationPhase.IDLE, partial = "")
    }
    private fun update(lease: Lease, transform: (TranslationState) -> TranslationState) {
        if (lease === current && lease.active) mutableState.value = transform(mutableState.value)
    }
}

internal object LiveTranslationController {
    val sessions = TranslationSessions()
    val state = sessions.state
}

/** Final sentences only; slow models cannot accumulate an unlimited conversation backlog. */
internal class TranslationSentenceQueue(private val capacity: Int = 2, private val maxCharacters: Int = 1000) {
    data class Sentence(val id: Long, val text: String)
    data class Offer(val accepted: Sentence? = null, val skipped: Boolean = false)
    private val queue = ArrayDeque<Sentence>()
    private var nextId = 1L
    init { require(capacity > 0 && maxCharacters > 0) }
    @Synchronized fun offer(text: String): Offer {
        val value = text.trim()
        if (value.isEmpty()) return Offer()
        if (value.length > maxCharacters) return Offer(skipped = true)
        val dropped = queue.size == capacity
        if (dropped) queue.removeFirst()
        val sentence = Sentence(nextId++, value)
        queue.addLast(sentence)
        return Offer(sentence, dropped)
    }
    @Synchronized fun take(): Sentence? = queue.removeFirstOrNull()
    @Synchronized fun clear() = queue.clear()
}
