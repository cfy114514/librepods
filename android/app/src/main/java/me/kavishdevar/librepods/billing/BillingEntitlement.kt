package me.kavishdevar.librepods.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

internal data class BillingEntitlement(
    val ready: Boolean = false, val premium: Boolean = false, val failed: Boolean = false
)

/** Loading a local flag must not emit a confirmed denial to device-control observers. */
internal fun BillingProvider.entitlementChanges(): Flow<BillingEntitlement> =
    if (this is FOSSBillingProvider) entitlement
    else isPremium.map { BillingEntitlement(ready = true, premium = it) }

internal class FossEntitlementState(private val onChanged: (BillingEntitlement) -> Unit = {}) {
    private val mutable = MutableStateFlow(BillingEntitlement())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var pendingUnlock = false

    @Synchronized fun beginRead(): Long? = revision.takeUnless { pendingUnlock }

    @Synchronized fun acceptRead(ticket: Long, premium: Boolean): Boolean {
        if (pendingUnlock || ticket != revision) return false
        publish(BillingEntitlement(ready = true, premium = premium))
        return true
    }

    @Synchronized fun failRead(ticket: Long) {
        if (!pendingUnlock && ticket == revision) publish(mutable.value.copy(failed = true))
    }

    @Synchronized fun requestUnlock(): Long {
        pendingUnlock = true
        revision++
        publish(BillingEntitlement(ready = true, premium = true))
        return revision
    }

    @Synchronized fun pendingUnlockTicket(): Long? = revision.takeIf { pendingUnlock }

    @Synchronized fun completeUnlock(ticket: Long) {
        if (pendingUnlock && ticket == revision) {
            pendingUnlock = false
            revision++
            publish(mutable.value.copy(failed = false))
        }
    }

    @Synchronized fun failUnlock(ticket: Long) {
        if (pendingUnlock && ticket == revision) publish(mutable.value.copy(failed = true))
    }

    private fun publish(value: BillingEntitlement) {
        mutable.value = value
        onChanged(value)
    }
}
