package android.net

class Uri private constructor(private val value: String) {
    override fun toString(): String = value
    companion object {
        fun fromParts(scheme: String, part: String, fragment: String?): Uri =
            Uri("$scheme:$part" + (fragment?.let { "#$it" } ?: ""))
    }
}
