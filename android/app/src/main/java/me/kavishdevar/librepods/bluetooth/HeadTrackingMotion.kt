package me.kavishdevar.librepods.bluetooth

/** Decoded sensor values shared by orientation and gesture consumers. */
data class HeadTrackingMotion(
    val service: Int,
    val o1: Int,
    val o2: Int,
    val o3: Int,
    val horizontal: Int,
    val vertical: Int
)
