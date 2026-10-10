/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import android.util.Log
import me.kavishdevar.librepods.utils.KeyedWorkSession

class FOSSBillingProvider(context: Context): BillingProvider {
    private val _isPremium = MutableStateFlow(false)
    override val isPremium: StateFlow<Boolean> = _isPremium

    private val _price = MutableStateFlow(context.getString(R.string.name_your_own_price))
    override val price: StateFlow<String> = _price

    private val appContext = context.applicationContext
    private val sharedPreferences by lazy { appContext.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val entitlementState = FossEntitlementState { if (it.ready) _isPremium.value = it.premium }
    internal val entitlement = entitlementState.state
    private enum class WorkKind { READ, UNLOCK }
    private data class Work(val kind: WorkKind, val ticket: Long = 0)
    private val worker = KeyedWorkSession<WorkKind, Work>(WorkKind.entries.toSet(), Dispatchers.IO,
        consume = { work ->
            when (work.kind) {
                WorkKind.READ -> readEntitlement()
                WorkKind.UNLOCK -> persistUnlock(work.ticket)
            }
        })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var purchaseJob: Job? = null

    init {
        queryPurchases()
    }

    override fun purchase(activity: Activity) {
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, "https://github.com/sponsors/kavishdevar".toUri())
        )

        purchaseJob?.cancel()

        purchaseJob = scope.launch {
            delay(5_000)
            unlock()
        }
    }

    override fun queryPurchases() {
        val pending = entitlementState.pendingUnlockTicket()
        if (pending == null) worker.offer(WorkKind.READ, Work(WorkKind.READ))
        else worker.offer(WorkKind.UNLOCK, Work(WorkKind.UNLOCK, pending))
    }

    override fun restorePurchases() {
        unlock()
    }

    private fun unlock() {
        val ticket = entitlementState.requestUnlock()
        worker.offer(WorkKind.UNLOCK, Work(WorkKind.UNLOCK, ticket))
    }

    private fun readEntitlement() {
        val ticket = entitlementState.beginRead() ?: return
        try { entitlementState.acceptRead(ticket, sharedPreferences.getBoolean("foss_upgraded", false)) }
        catch (error: Exception) {
            entitlementState.failRead(ticket)
            Log.w("FOSSBillingProvider", "Could not load local purchase status", error)
        }
    }

    private fun persistUnlock(ticket: Long) {
        if (entitlementState.pendingUnlockTicket() != ticket) return
        try {
            sharedPreferences.edit { putBoolean("foss_upgraded", true) }
            entitlementState.completeUnlock(ticket)
        } catch (error: Exception) {
            entitlementState.failUnlock(ticket)
            Log.w("FOSSBillingProvider", "Could not save local purchase status", error)
        }
    }
}
