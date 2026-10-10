package me.kavishdevar.librepods.data

/** Only live buds contribute to the system's unified headset battery; the case is independent. */
internal fun systemHeadsetBatteryLevel(batteries: List<Battery>): Int? = batteries.asSequence()
    .filter { (it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT) && it.isAvailableReading() }
    .minOfOrNull { it.level }

/** Android decodes IPHONEACCEV values 0..9 as 10..100%. Zero has no representation. */
internal fun appleHeadsetBatteryIndicator(percent: Int): Int? =
    percent.takeIf { it in 1..100 }?.let { (it - 1) / 10 }
