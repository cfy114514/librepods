package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class TemporaryPremiumPolicyTest {
    private fun values(expiry: Long = 0, upgraded: Boolean = false) = AppSetting.snapshot(
        mapOf("premium_expiry_time" to expiry, "foss_upgraded" to upgraded))
    @Test fun existingTemporaryEntitlementKeepsItsOriginalExpiryInEitherBuild() {
        for (play in listOf(false, true)) {
            val plan = temporaryPremiumPlan(values(2000, true), play, false, 1000)
            assertEquals(1000L, plan.remainingMillis); assertTrue(plan.changes.isEmpty())
        }
    }
    @Test fun accidentalFossPlayMigrationRemains28DaysAndDoesNotRunInFoss() {
        val plan = temporaryPremiumPlan(values(upgraded = true), true, false, 1000)
        val duration = 28L * 24 * 60 * 60 * 1000
        assertEquals(duration, plan.remainingMillis); assertEquals(mapOf(AppSetting.PREMIUM_EXPIRY to 1000 + duration), plan.changes)
        assertEquals(0L, temporaryPremiumPlan(values(upgraded = true), false, false, 1000).remainingMillis)
    }
    @Test fun expirationRemovesBothKeysAtomicallyWithoutStartingAnotherMigration() {
        val plan = temporaryPremiumPlan(values(1000, true), true, false, 1000)
        assertEquals(0L, plan.remainingMillis)
        assertEquals(mapOf(AppSetting.PREMIUM_EXPIRY to null, AppSetting.FOSS_UPGRADED to null), plan.changes)
    }
    @Test fun paidCleanupPreservesFossUnlockButRemovesLegacyPlayFlag() {
        val foss = temporaryPremiumPlan(values(2000, true), false, true, 1000)
        assertEquals(mapOf(AppSetting.PREMIUM_EXPIRY to null), foss.changes)
        val play = temporaryPremiumPlan(values(2000, true), true, true, 1000)
        assertEquals(mapOf(AppSetting.PREMIUM_EXPIRY to null, AppSetting.FOSS_UPGRADED to null), play.changes)
        assertTrue(temporaryPremiumPlan(values(), true, true, 1000).changes.isEmpty())
    }
}
