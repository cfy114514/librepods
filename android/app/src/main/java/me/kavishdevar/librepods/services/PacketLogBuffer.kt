package me.kavishdevar.librepods.services

/** Insertion-ordered, bounded diagnostic history. Callers serialize access. */
internal class PacketLogBuffer(private val capacity: Int = 1000) {
    private val entries = LinkedHashSet<String>()

    init {
        require(capacity > 0)
    }

    fun add(entry: String): Boolean {
        if (!entries.add(entry)) return false
        if (entries.size > capacity) {
            entries.iterator().apply { next(); remove() }
        }
        return true
    }

    fun snapshot(): Set<String> = entries.toSet()

    fun clear() = entries.clear()
}
