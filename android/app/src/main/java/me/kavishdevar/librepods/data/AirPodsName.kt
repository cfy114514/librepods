package me.kavishdevar.librepods.data

// Preserve the documented one-byte size followed by 00; do not invent a larger wire format.
internal const val MAX_AIRPODS_NAME_BYTES = 255
internal enum class AirPodsNameProblem { EMPTY, TOO_LONG, INVALID }

internal fun airPodsNameProblem(name: String): AirPodsNameProblem? {
    if (name.isBlank()) return AirPodsNameProblem.EMPTY
    if (name.length > MAX_AIRPODS_NAME_BYTES) return AirPodsNameProblem.TOO_LONG
    var index = 0
    while (index < name.length) {
        val value = name[index]
        if (value == '\u0000' || Character.isLowSurrogate(value)) return AirPodsNameProblem.INVALID
        if (Character.isHighSurrogate(value)) {
            if (index + 1 == name.length || !Character.isLowSurrogate(name[index + 1])) return AirPodsNameProblem.INVALID
            index++
        }
        index++
    }
    return if (name.toByteArray(Charsets.UTF_8).size > MAX_AIRPODS_NAME_BYTES) AirPodsNameProblem.TOO_LONG else null
}
