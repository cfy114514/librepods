package me.kavishdevar.librepods.data

internal data class TemporaryPremiumPlan(
    val remainingMillis: Long, val changes: Map<AppSetting, Any?> = emptyMap()
)

/** Retains the existing 28-day Play migration and build-specific paid cleanup. */
internal fun temporaryPremiumPlan(values: Map<AppSetting, Any>, play: Boolean, paid: Boolean, now: Long): TemporaryPremiumPlan {
    val expiry = values.getValue(AppSetting.PREMIUM_EXPIRY) as Long
    val upgraded = values.getValue(AppSetting.FOSS_UPGRADED) as Boolean
    if (paid) return TemporaryPremiumPlan(0, buildMap {
        if (expiry != 0L) put(AppSetting.PREMIUM_EXPIRY, null)
        if (play && upgraded) put(AppSetting.FOSS_UPGRADED, null)
    })
    if (expiry > 0L) return if (expiry > now) TemporaryPremiumPlan(expiry - now)
        else TemporaryPremiumPlan(0, mapOf(AppSetting.PREMIUM_EXPIRY to null, AppSetting.FOSS_UPGRADED to null))
    if (play && upgraded) {
        val duration = 28L * 24 * 60 * 60 * 1000
        return TemporaryPremiumPlan(duration, mapOf(AppSetting.PREMIUM_EXPIRY to now + duration))
    }
    return TemporaryPremiumPlan(0)
}
